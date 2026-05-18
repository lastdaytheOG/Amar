package com.amar.vault.agent.control.executors

import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.control.ActionExecutor
import com.amar.vault.agent.control.ExecutionResult
import com.amar.vault.agent.control.ExecutorTier
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.AgentStateHolder
import com.amar.vault.agent.control.AgentPhase
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.SnapshotCache
import com.amar.vault.agent.perception.UiReadinessWaiter

/**
 * Clicks a UI element identified by its DSL target.
 *
 * Flow:
 *   1. Resolve target → UiElement via TargetResolver.
 *   2. Re-resolve to live AccessibilityNodeInfo via LiveNodeFinder.
 *   3. Walk up to 2 ancestors if the matched node isn't itself clickable
 *      (Compose-based apps often mark a touch region's ROOT clickable with
 *      non-clickable content children).
 *   4. Dispatch ACTION_CLICK.
 *
 * Does NOT self-verify — returns Executed so VerificationEngine gets the
 * final word via the envelope's verify block.
 *
 * Tier: ACCESSIBILITY. No higher-tier path for generic click in v1.
 */
class ClickExecutor(
    private val snapshotCache: SnapshotCache
) : ActionExecutor {

    override val handles: ActionKind = ActionKind.CLICK
    override val tier: ExecutorTier = ExecutorTier.ACCESSIBILITY

    override fun isAvailable(): Boolean = PerceptionService.get() != null

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        val click = action as? AgentAction.Click ?: return ExecutionResult.FatalFailure(
            reason = FailureReason.Unexpected("ClickExecutor received ${action.kind}"),
            durationMs = 0
        )

        if (AgentStateHolder.phase == AgentPhase.INPUT) {
            return ExecutionResult.Failed(
                reason = FailureReason.Unexpected("Click exploration frozen during INPUT phase"),
                durationMs = 0
            )
        }

        val started = System.currentTimeMillis()
        val service = PerceptionService.get() ?: return ExecutionResult.Failed(
            reason = FailureReason.AccessibilityUnavailable,
            durationMs = 0
        )

        // Wait for UI motion to settle before resolving the target.
        // This solves race conditions where the target moves (e.g., bottom-sheet slides up) mid-click.
        UiReadinessWaiter.waitForStableUi(service)

        val resolve = TargetResolver.resolve(click.target, click.strategy, snapshotCache)
        val element = resolve.element ?: return ExecutionResult.Failed(
            reason = FailureReason.TargetNotFound(
                target = click.target,
                strategiesTried = resolve.strategiesTried
            ),
            durationMs = System.currentTimeMillis() - started
        )

        val liveNode = LiveNodeFinder.find(service, element, allowPathFallback = true)
            ?: return ExecutionResult.Failed(
                reason = FailureReason.TargetNotFound(
                    target = click.target,
                    strategiesTried = resolve.strategiesTried + "live_refind_failed"
                ),
                durationMs = System.currentTimeMillis() - started
            )

        // A matched node may not itself be clickable in Compose-based UIs where
        // the clickable wrapper sits several levels above the visually-interactive child.
        val clickable = LiveNodeFinder.findAncestor(liveNode, maxHops = 5) { it.isClickable }
        if (clickable == null) {
            LiveNodeFinder.safeRecycle(liveNode)
            return ExecutionResult.Failed(
                reason = FailureReason.TargetNotFound(
                    target = click.target,
                    strategiesTried = resolve.strategiesTried + "no_clickable_ancestor"
                ),
                durationMs = System.currentTimeMillis() - started
            )
        }

        // Disabled check AFTER finding the clickable target — a disabled wrapper
        // shouldn't block clicks to an enabled child.
        if (!clickable.isEnabled) {
            if (clickable !== liveNode) LiveNodeFinder.safeRecycle(clickable)
            LiveNodeFinder.safeRecycle(liveNode)
            return ExecutionResult.Failed(
                reason = FailureReason.TargetNotFound(
                    target = click.target,
                    strategiesTried = resolve.strategiesTried + "target_disabled"
                ),
                durationMs = System.currentTimeMillis() - started
            )
        }

        val clicked = try {
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (t: Throwable) {
            false
        } finally {
            if (clickable !== liveNode) LiveNodeFinder.safeRecycle(clickable)
            LiveNodeFinder.safeRecycle(liveNode)
        }

        val dur = System.currentTimeMillis() - started
        return if (clicked) {
            ExecutionResult.Executed(
                durationMs = dur,
                resultData = mapOf(
                    "target" to click.target,
                    "resolved_type" to element.type.name
                )
            )
        } else {
            ExecutionResult.Failed(
                reason = FailureReason.SystemError(
                    "ACTION_CLICK rejected by system for target '${click.target}'"
                ),
                durationMs = dur
            )
        }
    }
}