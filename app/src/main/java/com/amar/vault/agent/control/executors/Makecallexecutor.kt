package com.amar.vault.agent.control.executors

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.amar.vault.agent.control.ActionExecutor
import com.amar.vault.agent.control.ExecutionResult
import com.amar.vault.agent.control.ExecutorTier
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.AgentAction

/**
 * Initiates a phone call.
 *
 * Uses Intent.ACTION_CALL (not ACTION_DIAL) — directly dials without opening
 * the dialer UI. This requires CALL_PHONE runtime permission.
 *
 * Why not ACTION_DIAL (no permission needed):
 *   ACTION_DIAL opens the dialer prefilled but requires USER TAP on the green
 *   call button. Not agent-appropriate — defeats the whole automation purpose.
 *   If the user hasn't granted CALL_PHONE, [isAvailable] returns false and
 *   ControlLayer surfaces PermissionDenied. The Brain can then decide whether
 *   to fall back to a different plan (e.g., open_app("phone") + type_text the
 *   number + click dial — slower, but works without permission).
 *
 * Tier: SYSTEM_API. No direct UI fallback in this executor; cross-tier
 * fallback to Accessibility-driven dialing is a future plan-level composition.
 *
 * Verification:
 *   The call intent IS asynchronous — startActivity returns immediately; the
 *   call connection happens over seconds. We don't wait for connection; we
 *   return Executed with the target number, and VerificationEngine (or the
 *   Brain) can poll foreground == dialer/in-call UI if it cares.
 *
 * Emergency number handling:
 *   Platforms restrict ACTION_CALL for emergency numbers (112/911/etc) —
 *   they require system-level privileges we don't have. If the user asks
 *   the agent to call an emergency number, this executor will likely fail
 *   with a SecurityException. We don't add special handling because:
 *     (a) agents shouldn't dial emergency services without explicit user
 *         confirmation anyway (policy decision for the Brain layer),
 *     (b) silently succeeding here would be worse than failing loudly.
 *   The Brain layer's safety policy (spec §8) is the right place for this.
 */
class MakeCallExecutor(
    private val context: Context
) : ActionExecutor {

    override val handles: ActionKind = ActionKind.MAKE_CALL
    override val tier: ExecutorTier = ExecutorTier.SYSTEM_API

    override fun isAvailable(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        val call = action as? AgentAction.MakeCall ?: return ExecutionResult.FatalFailure(
            reason = FailureReason.Unexpected("MakeCallExecutor received ${action.kind}"),
            durationMs = 0
        )

        if (!isAvailable()) {
            return ExecutionResult.FatalFailure(
                reason = FailureReason.PermissionDenied(Manifest.permission.CALL_PHONE),
                durationMs = 0
            )
        }

        val started = System.currentTimeMillis()

        // Clean the number — Android's tel: URI wants digits, +, and -.
        // Spaces and parentheses are tolerated by most dialers but we normalize
        // defensively in case the Brain emitted a pretty-formatted number.
        val number = call.to.trim()
        if (number.isBlank()) {
            return ExecutionResult.Failed(
                reason = FailureReason.SystemError("empty phone number after normalization"),
                durationMs = System.currentTimeMillis() - started
            )
        }

        val uri = Uri.parse("tel:${Uri.encode(number)}")
        val intent = Intent(Intent.ACTION_CALL, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val launched = try {
            context.startActivity(intent)
            true
        } catch (se: SecurityException) {
            // Most commonly: trying to dial an emergency number.
            return ExecutionResult.FatalFailure(
                reason = FailureReason.SystemError(
                    "ACTION_CALL SecurityException: ${se.message?.take(120)} — " +
                            "possibly an emergency/restricted number"
                ),
                durationMs = System.currentTimeMillis() - started
            )
        } catch (t: Throwable) {
            false
        }

        val dur = System.currentTimeMillis() - started

        return if (launched) {
            ExecutionResult.Executed(
                durationMs = dur,
                resultData = mapOf(
                    "to" to number,
                    "uri" to uri.toString()
                )
            )
        } else {
            ExecutionResult.Failed(
                reason = FailureReason.SystemError("startActivity failed for tel: URI"),
                durationMs = dur
            )
        }
    }
}