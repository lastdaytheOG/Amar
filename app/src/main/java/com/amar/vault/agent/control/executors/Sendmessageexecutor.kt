package com.amar.vault.agent.control.executors

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.amar.vault.agent.control.ActionExecutor
import com.amar.vault.agent.control.ExecutionResult
import com.amar.vault.agent.control.ExecutorTier
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.perception.PackageWatchlist
import java.util.Locale

/**
 * Sends a message through a named app (WhatsApp, Telegram, etc).
 *
 * Strategy — deep-link first, UI fallback second:
 *
 *   Tier 1 (this executor, INTENT tier): use the app's URL-scheme deep link to
 *     open the chat with the message prefilled. Two patterns are supported in
 *     v1:
 *       WhatsApp  → https://wa.me/<number>?text=<encoded>
 *       Telegram  → tg://resolve?domain=<handle>&text=<encoded>
 *     (Others fall through to fail-fast so the Brain can re-plan.)
 *
 *     The deep link opens the chat with the message typed in; the user still
 *     has to tap Send. That's NOT automation — so this executor returns
 *     Executed (not ExecutedAndVerified) and leaves the actual send to the
 *     next step in the plan, typically a follow-up Click("Send") action.
 *
 *   Tier 2 (NOT in this executor — separate ACCESSIBILITY-tier SendMessageUiExecutor,
 *     deferred): full UI automation — open app, click contact, type message,
 *     click send. Much slower and more fragile. Composable from the 10 v1
 *     primitives (open_app + click + type_text + click), so the Brain can do
 *     this decomposition itself as a backup plan. We don't hide that complexity
 *     in a single executor because the failure modes are too varied to handle
 *     generically.
 *
 * Why one executor instead of two:
 *   v1 ships the INTENT-tier path only. This keeps the send_message action
 *   "useful for the common case, composable for the hard case." If the Brain
 *   targets an app without a deep link, we return TargetNotFound so the Brain
 *   knows to decompose into primitives.
 *
 * WhatsApp specifics:
 *   - The `to` field must be a phone number in international format (no +
 *     prefix, no spaces). We normalize defensively.
 *   - WhatsApp's wa.me handles both WhatsApp and WhatsApp Business — it
 *     resolves to whichever is installed.
 *
 * Telegram specifics:
 *   - tg:// handles usernames (@handle or bare handle); does not resolve
 *     phone numbers reliably. If `to` looks like a number we short-circuit
 *     to TargetNotFound and let the Brain decompose.
 *
 * Tier: INTENT.
 *
 * Verification:
 *   Executed only, NEVER ExecutedAndVerified. The deep link opened the chat
 *   but the user or a follow-up action has to actually send. VerifySpec of
 *   type UiContains("sent" | "✓") is a reasonable follow-up check, driven
 *   by the Brain.
 */
class SendMessageExecutor(
    private val context: Context,
    private val watchlist: PackageWatchlist
) : ActionExecutor {

    override val handles: ActionKind = ActionKind.SEND_MESSAGE
    override val tier: ExecutorTier = ExecutorTier.INTENT

    override fun isAvailable(): Boolean = true

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        val msg = action as? AgentAction.SendMessage ?: return ExecutionResult.FatalFailure(
            reason = FailureReason.Unexpected("SendMessageExecutor received ${action.kind}"),
            durationMs = 0
        )

        val started = System.currentTimeMillis()
        val appKey = msg.app.trim().lowercase(Locale.ROOT)

        val intent = when (appKey) {
            "whatsapp", "wa"      -> whatsAppIntent(msg.to, msg.content)
            "telegram", "tg"      -> telegramIntent(msg.to, msg.content)
            else -> null
        } ?: return ExecutionResult.Failed(
            reason = FailureReason.TargetNotFound(
                target = "send_message:${msg.app}",
                strategiesTried = listOf("whatsapp_deeplink", "telegram_deeplink")
            ),
            durationMs = System.currentTimeMillis() - started
        )

        // Check the intent resolves BEFORE launching — avoids the ugly
        // ActivityNotFoundException toast on devices where the app isn't installed.
        val resolved = try {
            context.packageManager.resolveActivity(intent, 0)
        } catch (t: Throwable) { null }
        if (resolved == null) {
            return ExecutionResult.Failed(
                reason = FailureReason.SystemError(
                    "No activity resolves the ${appKey} deep link — app likely not installed"
                ),
                durationMs = System.currentTimeMillis() - started
            )
        }

        // Add the resolved package to the watchlist so Perception observes the
        // subsequent window-change events. Released by WatchlistReleaser on
        // terminal, same mechanism as OpenAppExecutor.
        val resolvedPackage = resolved.activityInfo?.packageName
        resolvedPackage?.let { watchlist.acquire(it) }

        val launched = try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            resolvedPackage?.let { watchlist.release(it) }
            false
        }

        val dur = System.currentTimeMillis() - started

        return if (launched) {
            ExecutionResult.Executed(
                durationMs = dur,
                resultData = mapOf(
                    "app" to appKey,
                    "to" to msg.to,
                    "content_len" to msg.content.length.toString(),
                    "package" to (resolvedPackage ?: "")
                )
            )
        } else {
            ExecutionResult.Failed(
                reason = FailureReason.SystemError("startActivity failed for ${appKey} deep link"),
                durationMs = dur
            )
        }
    }

    // -------------------------------------------------------------------------
    // Deep link construction
    // -------------------------------------------------------------------------

    private fun whatsAppIntent(to: String, content: String): Intent? {
        // wa.me requires digits only, no + prefix.
        val normalized = to.replace(Regex("[\\s\\-()+]"), "")
        if (normalized.isEmpty() || !normalized.all { it.isDigit() }) return null
        if (normalized.length !in 6..15) return null

        val uri = Uri.parse("https://wa.me/$normalized?text=${Uri.encode(content)}")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Pin to WhatsApp specifically — the link is also an https URL so
            // Chrome can handle it. We want WhatsApp to win. Setting package
            // explicitly short-circuits the chooser.
            setPackage("com.whatsapp")
        }
    }

    private fun telegramIntent(to: String, content: String): Intent? {
        // Telegram deep links work with @handle, not phone numbers.
        val handle = to.trim().removePrefix("@")
        if (handle.isEmpty()) return null
        if (handle.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }) {
            // Looks like a phone number — tg://resolve doesn't accept those.
            // Brain should decompose to open_app + UI actions for phone-number
            // Telegram contacts.
            return null
        }

        val uri = Uri.parse("tg://resolve?domain=$handle&text=${Uri.encode(content)}")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}