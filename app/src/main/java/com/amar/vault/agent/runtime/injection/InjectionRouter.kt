package com.amar.vault.agent.runtime.injection

import com.amar.vault.agent.runtime.state.SemanticIdentity
import com.amar.vault.agent.runtime.state.WorldStateStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin entry point the executor uses to delegate text injection to the
 * elite engine. Provides a "try by current focused identity" helper that
 * reads WorldState for the expected identity rather than requiring the
 * executor to know about SemanticIdentity directly.
 *
 * Kept separate from InjectionEngine so we can swap out the engine
 * implementation behind a feature flag without touching executors.
 */
@Singleton
class InjectionRouter @Inject constructor(
    private val engine: InjectionEngine,
    private val store: WorldStateStore
) {

    /**
     * Inject [text] into whichever editable WorldState currently believes is
     * focused. Returns a [SimpleResult] that callers can branch on.
     *
     * Returns NoIdentity if WorldState has no focusedEditableIdentity at all,
     * which means readiness checks aren't going to succeed and the executor
     * should fall back to its legacy cascade.
     */
    suspend fun injectIntoFocusedEditable(text: String): SimpleResult {
        val identity = store.current().focusedEditableIdentity
            ?: return SimpleResult.NoIdentity

        val result = engine.inject(text, identity)
        return when (result) {
            is InjectionResult.Verified -> SimpleResult.Verified(
                viaStrategy = result.viaStrategy,
                confidence = result.finalConfidence,
                durationMs = result.durationMs
            )
            is InjectionResult.Failed -> SimpleResult.Failed(
                reason = result.reason,
                attempts = result.attempts.size,
                durationMs = result.durationMs
            )
        }
    }

    sealed class SimpleResult {
        data class Verified(
            val viaStrategy: String,
            val confidence: Float,
            val durationMs: Long
        ) : SimpleResult()

        data class Failed(
            val reason: String,
            val attempts: Int,
            val durationMs: Long
        ) : SimpleResult()

        data object NoIdentity : SimpleResult()
    }
}