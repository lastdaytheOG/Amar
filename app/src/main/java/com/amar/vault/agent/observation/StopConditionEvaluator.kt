package com.amar.vault.agent.observation

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 6: Stop-condition evaluation.
 *
 * Separated into its own class so the stop rules are reviewable, testable,
 * and changeable without editing the coordinator. The coordinator asks
 * "should I halt?" and this tells it yes/no with a reason.
 *
 * Rules encoded:
 *   1. Payment screen → ALWAYS stop. Never auto-pay anything.
 *   2. Confirmation gate → stop, ask user.
 *   3. Explicit goal marker → stop (set by workflow when it knows it's done).
 *   4. Consecutive failures → stop to avoid harm from a broken loop.
 *   5. User cancellation → stop immediately.
 *
 * Design notes:
 *   - Rules are checked in priority order; first match wins.
 *   - Result includes a human-readable reason for UI display ("stopped at
 *     payment — your confirmation required").
 *   - This class is pure logic; it holds no mutable state. All inputs come
 *     from the caller per evaluation.
 */
@Singleton
class StopConditionEvaluator @Inject constructor() {

    fun evaluate(
        worldState: AgentWorldState,
        consecutiveFailures: Int,
        userCancelled: Boolean,
        workflowSignalsDone: Boolean
    ): StopDecision {
        // Rule 5: user override is always respected first.
        if (userCancelled) {
            return StopDecision.Stop(
                reason = StopReason.USER_CANCELLED,
                humanMessage = "cancelled by user"
            )
        }

        // Rule 1: payment gate.
        if (worldState.screen == ScreenType.PAYMENT) {
            return StopDecision.Stop(
                reason = StopReason.PAYMENT_GATE,
                humanMessage = "stopped at payment — your confirmation required"
            )
        }

        // Rule 2: explicit confirmation screens.
        if (worldState.screen == ScreenType.CONFIRMATION_REQUIRED) {
            return StopDecision.Stop(
                reason = StopReason.CONFIRMATION_GATE,
                humanMessage = "confirmation required — taking over is unsafe"
            )
        }

        // Rule 3: workflow declared completion.
        if (workflowSignalsDone) {
            return StopDecision.Stop(
                reason = StopReason.GOAL_COMPLETE,
                humanMessage = "goal complete"
            )
        }

        // Rule 4: too many failures in a row.
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            return StopDecision.Stop(
                reason = StopReason.REPEATED_FAILURE,
                humanMessage = "too many failed attempts; stopping to avoid unintended side-effects"
            )
        }

        return StopDecision.Continue
    }

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }
}

sealed class StopDecision {
    data object Continue : StopDecision()
    data class Stop(
        val reason: StopReason,
        val humanMessage: String
    ) : StopDecision()
}

enum class StopReason {
    PAYMENT_GATE,
    CONFIRMATION_GATE,
    GOAL_COMPLETE,
    REPEATED_FAILURE,
    USER_CANCELLED
}