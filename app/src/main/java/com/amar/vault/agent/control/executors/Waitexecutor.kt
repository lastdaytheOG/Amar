package com.amar.vault.agent.control.executors

import com.amar.vault.agent.control.ActionExecutor
import com.amar.vault.agent.control.ExecutionResult
import com.amar.vault.agent.control.ExecutorTier
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.AgentAction
import kotlinx.coroutines.delay

/**
 * Pauses for a specified number of milliseconds.
 *
 * Why this exists as a DSL action:
 *   Some apps need settle time between actions — a dialog opening, a list
 *   re-rendering, a toast dismissing. Letting the Brain insert explicit waits
 *   keeps those pauses VISIBLE in the plan instead of hidden inside executors
 *   where they can't be tuned or skipped. A wait in the plan is tunable
 *   evidence that the planner understood a timing requirement.
 *
 * Bounds:
 *   ActionValidator enforces 50ms ≤ ms ≤ 15000ms. Anything outside has been
 *   rejected before we're called. We still re-check defensively because nothing
 *   else in the call chain guarantees it.
 *
 * Cancellation:
 *   kotlinx `delay()` is cancellation-aware. If ControlLayer times out or the
 *   user cancels, the coroutine unwinds cleanly; we report that via the
 *   standard CancellationException propagation (handled by the ControlLayer).
 *
 * Tier:
 *   INTENT is the natural fit — there's no UI interaction, no system API call.
 *   It's pure time. Marking it INTENT keeps the registry from having to
 *   consider fallbacks for it.
 */
class WaitExecutor : ActionExecutor {

    override val handles: ActionKind = ActionKind.WAIT
    override val tier: ExecutorTier = ExecutorTier.INTENT

    override fun isAvailable(): Boolean = true

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        val wait = action as? AgentAction.Wait ?: return ExecutionResult.FatalFailure(
            reason = FailureReason.Unexpected("WaitExecutor received ${action.kind}"),
            durationMs = 0
        )

        // Defensive re-check (validator already enforced, but we don't trust
        // upstream callers to be perfect).
        if (wait.ms !in 0..60_000) {
            return ExecutionResult.FatalFailure(
                reason = FailureReason.Unexpected("wait.ms out of sanity bounds: ${wait.ms}"),
                durationMs = 0
            )
        }

        val started = System.currentTimeMillis()

        // Interruptible sleep. kotlinx delay polls cancellation internally.
        // We also poll ctx.isCancelRequested between slices so user-driven
        // cancels unwind even if the coroutine cancellation got ignored
        // somewhere upstream (belt and suspenders — cheap, no downside).
        var remaining = wait.ms.toLong()
        val slice = 100L
        while (remaining > 0) {
            if (ctx.isCancelRequested) {
                return ExecutionResult.Cancelled(
                    ctx.pendingCancellationReason
                        ?: com.amar.vault.agent.control.CancellationReason.UserRequested
                )
            }
            val step = minOf(slice, remaining)
            delay(step)
            remaining -= step
        }

        val dur = System.currentTimeMillis() - started

        // Waits self-verify trivially — we were asked to spend time, we spent it.
        // Returning ExecutedAndVerified skips the verify block (which would be
        // meaningless for a pure-time action anyway).
        return ExecutionResult.ExecutedAndVerified(
            durationMs = dur,
            resultData = mapOf("waited_ms" to dur.toString())
        )
    }
}