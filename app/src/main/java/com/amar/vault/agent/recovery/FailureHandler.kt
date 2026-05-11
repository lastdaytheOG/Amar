package com.amar.vault.agent.recovery

import com.amar.vault.agent.capability.ExecutionPlan
import com.amar.vault.agent.execution.ExecutionOutcome
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 9: Failure Handling.
 *
 * Cross-step recovery. Distinct from the ControlLayer's RetryPolicy (which
 * handles within-action retries with backoff). This handles:
 *
 *   - "Native search failed, try UI search instead."
 *   - "UI search failed, try opening the app and stopping there."
 *   - "App launch failed, try the next candidate package."
 *
 * Exposes a deterministic recovery plan. The coordinator consults this
 * handler when a step fails; it returns the next thing to try, or null if
 * we've exhausted alternatives.
 *
 * Design notes:
 *   - The fallback LADDERS are intentionally short (2-3 steps). Longer
 *     ladders make failure modes harder to reason about.
 *   - Each ladder step downgrades capability (native → UI → open-only),
 *     not the other way round, so we never escalate to more-risky actions.
 *   - NEVER fall back to "try a different app." That's a hallucination
 *     path. User asked for X; if we can't do X on app X, we FAIL honestly
 *     and tell the user.
 */
@Singleton
class FailureHandler @Inject constructor() {

    /**
     * Given an execution plan that just failed, return the next plan to
     * attempt, or null if we've exhausted our fallback ladder.
     */
    fun nextFallback(
        failed: ExecutionPlan,
        outcome: ExecutionOutcome,
        attemptsSoFar: Int
    ): ExecutionPlan? {
        if (attemptsSoFar >= MAX_FALLBACK_ATTEMPTS) return null

        return when (failed) {
            is ExecutionPlan.NativeSearch -> handleNativeSearchFail(failed, outcome)
            is ExecutionPlan.UiSearch     -> handleUiSearchFail(failed, outcome)
            is ExecutionPlan.OpenApp      -> handleOpenAppFail(failed, outcome)
            is ExecutionPlan.OrderWorkflow -> handleWorkflowFail(failed, outcome)
        }
    }

    private fun handleNativeSearchFail(
        failed: ExecutionPlan.NativeSearch,
        outcome: ExecutionOutcome
    ): ExecutionPlan? = when (outcome) {
        // Intent not handled → UI fallback is the right next step.
        is ExecutionOutcome.NotSupported ->
            ExecutionPlan.UiSearch(failed.packageId, failed.query)

        // Intent threw → try UI as a best-effort recovery.
        is ExecutionOutcome.Failed ->
            ExecutionPlan.UiSearch(failed.packageId, failed.query)

        // Already succeeded — no fallback needed.
        is ExecutionOutcome.Started -> null
    }

    private fun handleUiSearchFail(
        failed: ExecutionPlan.UiSearch,
        outcome: ExecutionOutcome
    ): ExecutionPlan? = when (outcome) {
        // UI path failed → open the app and let the user continue manually.
        is ExecutionOutcome.NotSupported,
        is ExecutionOutcome.Failed ->
            ExecutionPlan.OpenApp(failed.packageId)

        is ExecutionOutcome.Started -> null
    }

    private fun handleOpenAppFail(
        failed: ExecutionPlan.OpenApp,
        outcome: ExecutionOutcome
    ): ExecutionPlan? {
        // Open-app is the bottom of the ladder — nothing simpler to
        // degrade to. Return null; caller surfaces the failure to user.
        return null
    }

    private fun handleWorkflowFail(
        failed: ExecutionPlan.OrderWorkflow,
        outcome: ExecutionOutcome
    ): ExecutionPlan? {
        // If the hardcoded workflow broke (UI changed, network error), at
        // minimum we can still open the app so the user completes manually.
        return ExecutionPlan.OpenApp(failed.packageId)
    }

    companion object {
        /**
         * Total fallback ladder length. 2 is deliberate: one degrade from
         * native → UI, then one degrade from UI → open-only. Beyond that
         * we're flailing; stop and tell the user.
         */
        private const val MAX_FALLBACK_ATTEMPTS = 2
    }
}