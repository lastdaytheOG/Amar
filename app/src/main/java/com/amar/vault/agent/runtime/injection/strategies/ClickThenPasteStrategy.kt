package com.amar.vault.agent.runtime.injection.strategies

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.runtime.injection.InjectionStrategy
import com.amar.vault.agent.runtime.state.SemanticIdentity

/**
 * Click the editable to ensure full focus + InputConnection, then paste.
 *
 * Use case:
 *   Last-resort for stubborn fields where FOCUS+SET_TEXT and CLIPBOARD_PASTE
 *   both fail. Clicking forces the view through its complete focus pipeline
 *   (the same path as a real user tap), guaranteeing InputConnection is
 *   bound before the paste.
 *
 * Risk:
 *   If the node's bounds happen to be wrong or the node has moved due to
 *   keyboard slide-up, clicking can hit an adjacent widget. The
 *   [InjectionEngine] mitigates this by re-acquiring the node freshly
 *   right before each strategy attempt.
 */
class ClickThenPasteStrategy(
    private val context: Context
) : InjectionStrategy {

    override val name: String = "CLICK+PASTE"

    private val cm: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun inject(
        node: AccessibilityNodeInfo,
        text: String,
        identity: SemanticIdentity
    ): Boolean {
        return try {
            // Step 1: click to ensure InputConnection.
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) return false

            // Step 2: load clipboard.
            cm.setPrimaryClip(ClipData.newPlainText("agent_inject", text))

            // Step 3: paste.
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (t: Throwable) {
            false
        }
    }
}