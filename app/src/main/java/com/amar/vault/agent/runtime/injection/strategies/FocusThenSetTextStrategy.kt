package com.amar.vault.agent.runtime.injection.strategies

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.runtime.injection.InjectionStrategy
import com.amar.vault.agent.runtime.state.SemanticIdentity

/**
 * Force input focus via ACTION_FOCUS, then ACTION_SET_TEXT.
 *
 * Use case:
 *   Some views accept SET_TEXT only when input-focused. The bus's
 *   ViewFocused event indicates accessibility focus, which is NOT the
 *   same as input focus. This strategy bridges the gap.
 *
 * Caveat:
 *   ACTION_FOCUS doesn't show the keyboard. The caller (InjectionEngine)
 *   must have already verified inputConnectionReady = true before invoking
 *   this strategy. We don't show the keyboard ourselves because:
 *     (a) we can't from a non-UI thread
 *     (b) the user's intent is to type, not to look at the IME
 */
class FocusThenSetTextStrategy : InjectionStrategy {

    override val name: String = "FOCUS+SET_TEXT"

    override fun inject(
        node: AccessibilityNodeInfo,
        text: String,
        identity: SemanticIdentity
    ): Boolean {
        return try {
            val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (!focused) return false

            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            false
        }
    }
}