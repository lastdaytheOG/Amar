package com.amar.vault.agent.control.executors

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.amar.vault.agent.control.ActionExecutor
import com.amar.vault.agent.control.ExecutionResult
import com.amar.vault.agent.control.ExecutorTier
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.AgentAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Sends an SMS via SmsManager.
 *
 * Self-verification via PendingIntent broadcast:
 *   SmsManager.sendTextMessage takes a sent-intent + delivered-intent. We register
 *   a BroadcastReceiver for the sent-intent, wait up to SEND_TIMEOUT_MS for it
 *   to fire, and report ExecutedAndVerified on RESULT_OK. This gives the Brain
 *   a definitive yes/no on delivery without polling UI (which often doesn't
 *   reflect SMS send state at all on modern Android).
 *
 * Why two intents:
 *   sent-intent    → carrier accepted the message (we return after this).
 *   delivered-intent → carrier confirmed delivery (we DON'T wait for this —
 *                     can take seconds/minutes, and v1 doesn't need it).
 *
 * Permissions required:
 *   android.permission.SEND_SMS — runtime permission on API 23+.
 *   Must be declared in manifest AND granted by the user. [isAvailable]
 *   returns false if not granted — ControlLayer surfaces that as
 *   PermissionDenied via diagnoseUnavailable.
 *
 * Long messages:
 *   Messages over 160 characters (70 for Unicode) get split into multiple
 *   segments by divideMessage. Each segment gets its own sent-intent.
 *   v1 simplification: we wait for the FIRST segment's sent-intent and return
 *   ExecutedAndVerified. If a later segment fails, the message is partially sent.
 *   This matches 99% of "quick reply" use cases; bulk SMS isn't in scope.
 *
 * Tier: SYSTEM_API. No UI tier fallback — if SEND_SMS is denied, there's no
 * programmatic substitute. The Brain can fall back to send_message (WhatsApp
 * etc.) or ask the user to send manually.
 *
 * Why not Intent.ACTION_SENDTO:
 *   That opens the SMS app with the message prefilled but requires a USER TAP
 *   on Send. Not agent-suitable. SmsManager sends directly, which is the
 *   whole point of having SEND_SMS permission.
 */
class SendSmsExecutor(
    private val context: Context
) : ActionExecutor {

    override val handles: ActionKind = ActionKind.SEND_SMS
    override val tier: ExecutorTier = ExecutorTier.SYSTEM_API

    override fun isAvailable(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        val sms = action as? AgentAction.SendSms ?: return ExecutionResult.FatalFailure(
            reason = FailureReason.Unexpected("SendSmsExecutor received ${action.kind}"),
            durationMs = 0
        )

        if (!isAvailable()) {
            return ExecutionResult.FatalFailure(
                reason = FailureReason.PermissionDenied(Manifest.permission.SEND_SMS),
                durationMs = 0
            )
        }

        val started = System.currentTimeMillis()
        val smsManager = getSmsManager() ?: return ExecutionResult.Failed(
            reason = FailureReason.SystemError("SmsManager unavailable on this device"),
            durationMs = System.currentTimeMillis() - started
        )

        // Unique correlation id per send so we only listen for OUR broadcast.
        // (Two concurrent SMS tasks would otherwise step on each other's intents.)
        val correlationId = "sms-" + UUID.randomUUID().toString().substring(0, 8)
        val sentAction = "$ACTION_SMS_SENT.$correlationId"

        val sentResult = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == sentAction && !sentResult.isCompleted) {
                    sentResult.complete(resultCode)
                }
            }
        }

        val filter = IntentFilter(sentAction)
        val registered = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ requires explicit export flag.
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            true
        } catch (t: Throwable) {
            false
        }

        if (!registered) {
            return ExecutionResult.Failed(
                reason = FailureReason.SystemError("Could not register SMS send receiver"),
                durationMs = System.currentTimeMillis() - started
            )
        }

        try {
            val sentIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(sentAction).setPackage(context.packageName),
                pendingIntentFlags()
            )

            // Split long messages into segments. The SmsManager API handles the
            // actual carrier-level segmentation; we just provide the callback array.
            val parts = smsManager.divideMessage(sms.body)
            try {
                if (parts.size <= 1) {
                    smsManager.sendTextMessage(
                        sms.to,
                        null,   // sender (null = default)
                        sms.body,
                        sentIntent,
                        null    // deliveredIntent — not wired in v1
                    )
                } else {
                    val sentIntents = ArrayList<PendingIntent>(parts.size).apply {
                        repeat(parts.size) { add(sentIntent) }
                    }
                    smsManager.sendMultipartTextMessage(
                        sms.to,
                        null,
                        parts,
                        sentIntents,
                        null
                    )
                }
            } catch (t: Throwable) {
                return ExecutionResult.Failed(
                    reason = FailureReason.SystemError(
                        "sendTextMessage threw: ${t.javaClass.simpleName}: ${t.message}"
                    ),
                    durationMs = System.currentTimeMillis() - started
                )
            }

            // Wait for the sent-intent broadcast.
            val resultCode = withTimeoutOrNull(SEND_TIMEOUT_MS) { sentResult.await() }

            val dur = System.currentTimeMillis() - started

            return when (resultCode) {
                null -> ExecutionResult.Failed(
                    reason = FailureReason.Timeout(ActionKind.SEND_SMS, SEND_TIMEOUT_MS),
                    durationMs = dur
                )
                android.app.Activity.RESULT_OK -> ExecutionResult.ExecutedAndVerified(
                    durationMs = dur,
                    resultData = mapOf(
                        "to" to sms.to,
                        "parts" to parts.size.toString(),
                        "body_len" to sms.body.length.toString()
                    )
                )
                else -> ExecutionResult.Failed(
                    reason = FailureReason.SystemError(
                        "SMS send result=${smsResultCodeName(resultCode)}"
                    ),
                    durationMs = dur
                )
            }
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) { /* already unregistered */ }
        }
    }

    @Suppress("DEPRECATION")
    private fun getSmsManager(): SmsManager? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    } catch (t: Throwable) {
        null
    }

    private fun pendingIntentFlags(): Int {
        // FLAG_MUTABLE is required on API 31+ when the intent is used for a
        // result callback. FLAG_UPDATE_CURRENT ensures we see the latest extras.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun smsResultCodeName(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE"
        SmsManager.RESULT_ERROR_NO_SERVICE      -> "NO_SERVICE"
        SmsManager.RESULT_ERROR_NULL_PDU        -> "NULL_PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF       -> "RADIO_OFF"
        else -> "code=$code"
    }

    companion object {
        private const val ACTION_SMS_SENT = "com.amar.vault.agent.SMS_SENT"

        /**
         * Upper bound on how long we wait for the sent-intent broadcast. Most
         * carriers respond within 2-4 seconds. Longer tails exist on bad networks;
         * the envelope's own timeout_ms is the real ceiling and cancels us if hit.
         */
        private const val SEND_TIMEOUT_MS = 10_000L
    }
}