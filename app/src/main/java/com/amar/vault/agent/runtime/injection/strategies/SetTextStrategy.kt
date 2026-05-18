package com.amar.vault.agent.runtime.injection.strategies

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.runtime.injection.InjectionStrategy
import com.amar.vault.agent.runtime.state.SemanticIdentity

/**
 * Plain ACTION_SET_TEXT. Equivalent to the legacy [TypeTextExecutor]'s
 * approach. Listed FIRST in the cascade because it's the cheapest and
 * works on most stock-Android EditText views.
 *
 * Fails silently on:
 *   - Compose TextField without proper accessibility plumbing
 *   - Flutter EditableText
 *   - Some custom EditText subclasses that intercept SET_TEXT
 *
 * The [ConfidenceEngine] will detect the silent failure and the engine
 * will advance to the next strategy.
 */
class SetTextStrategy : InjectionStrategy {

    override val name: String = "ACTION_SET_TEXT"

    override fun inject(
        node: AccessibilityNodeInfo,
        text: String,
        identity: SemanticIdentity
    ): Boolean {
        return try {
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