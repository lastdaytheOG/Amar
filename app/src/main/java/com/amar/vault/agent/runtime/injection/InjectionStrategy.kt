package com.amar.vault.agent.runtime.injection

import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.runtime.state.SemanticIdentity

/**
 * Abstract injection strategy. Each concrete strategy attempts ONE specific
 * approach to put text into a focused editable.
 *
 * Strategies are stateless and reusable across attempts. They receive a
 * fresh [AccessibilityNodeInfo] for every attempt — NEVER cache nodes;
 * they expire on every UI mutation.
 *
 * Contract:
 *   - inject() is atomic: either it succeeded mechanically (returned true)
 *     or it didn't try / failed (returned false). It does NOT verify the
 *     text actually appeared — that's the [ConfidenceEngine]'s job.
 *   - Strategies do NOT log. The InjectionEngine wraps each call with
 *     structured logging.
 *   - Strategies do NOT recycle the node passed in. The caller owns
 *     lifecycle.
 *
 * Why strategies are not coroutines:
 *   These are tight synchronous operations (performAction, dispatchGesture
 *   bookkeeping). The caller suspends around them as needed for waits.
 *   Keeping the strategy API non-suspend simplifies testing.
 */
interface InjectionStrategy {

    val name: String

    /**
     * Try this strategy. Returns true on mechanical success (the underlying
     * Android API accepted the action). Returns false if it could not even
     * attempt (e.g. wrong node type, missing capability).
     *
     * @param node  freshly-acquired focused-editable node. Caller owns recycling.
     * @param text  the text to inject. May be empty (means "clear field").
     * @param identity the semantic identity this node represents. Strategies
     *                 may consult identity for app-specific behavior, but most
     *                 should be identity-agnostic.
     */
    fun inject(
        node: AccessibilityNodeInfo,
        text: String,
        identity: SemanticIdentity
    ): Boolean
}

/**
 * Result of a strategy attempt. Reported by [InjectionEngine] to its callers.
 */
data class StrategyAttempt(
    val strategy: String,
    val mechanicalSuccess: Boolean,
    val confidence: Float,
    val verified: Boolean,
    val durationMs: Long,
    val notes: String? = null
)