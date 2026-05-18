package com.amar.vault.agent.runtime.injection.strategies

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.runtime.injection.InjectionStrategy
import com.amar.vault.agent.runtime.state.SemanticIdentity

/**
 * Clipboard virtualization strategy:
 *   1. Save current clipboard content (to restore later — privacy commitment).
 *   2. Set clipboard to [text].
 *   3. Dispatch ACTION_PASTE to the focused node.
 *   4. Caller (InjectionEngine) restores original clipboard AFTER verification.
 *
 * Why this works when SET_TEXT doesn't:
 *   ACTION_PASTE routes through Android's standard paste pipeline, which
 *   apps with custom input handling (Flutter, Compose-with-custom-IME,
 *   some WhatsApp text fields) still honor because they bind to the system
 *   clipboard for cut/copy/paste UX. SET_TEXT bypasses that pipeline and
 *   hits the view directly — easy for apps to ignore.
 *
 * Clipboard restoration:
 *   We expose [saveClipboard] and [restoreClipboard] separately because the
 *   engine must restore AFTER verification (which may take 500-1500ms).
 *   Restoring inside inject() would erase the just-pasted text in apps that
 *   re-read clipboard on focus events.
 *
 * Privacy:
 *   The clipboard contains user secrets (passwords, addresses, OTPs).
 *   We snapshot+restore so the user never notices we touched it. If
 *   verification fails AND we never restore, the user's clipboard now
 *   contains our injection payload — handle this in the engine's finally.
 */
class ClipboardPasteStrategy(
    private val context: Context
) : InjectionStrategy {

    override val name: String = "CLIPBOARD_PASTE"

    private val cm: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun inject(
        node: AccessibilityNodeInfo,
        text: String,
        identity: SemanticIdentity
    ): Boolean {
        return try {
            // Step 1: set clipboard.
            cm.setPrimaryClip(ClipData.newPlainText("agent_inject", text))

            // Step 2: paste. The node should be focused; ACTION_PASTE routes
            // through the InputConnection if one is bound, otherwise through
            // the view's onTextContextMenuItem.
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Snapshot the current clipboard so the engine can restore it after
     * verification. Returns null if clipboard is empty or unreadable.
     *
     * Called by InjectionEngine BEFORE inject().
     */
    fun saveClipboard(): ClipData? {
        return try { cm.primaryClip } catch (t: Throwable) { null }
    }

    /**
     * Restore a previously-saved clipboard snapshot. No-op if [saved] is null.
     * Called by InjectionEngine in finally, AFTER verification.
     */
    fun restoreClipboard(saved: ClipData?) {
        if (saved == null) return
        try {
            cm.setPrimaryClip(saved)
        } catch (_: Throwable) {
            // Best-effort.
        }
    }
}