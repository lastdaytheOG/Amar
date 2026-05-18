package com.amar.vault.agent.control.executors

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import com.amar.vault.agent.control.ActionExecutor
import com.amar.vault.agent.control.ExecutionResult
import com.amar.vault.agent.control.ExecutorTier
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.SnapshotCache
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Stage 2d fallback: when ACTION_CLICK is a no-op (target's interaction is
 * wired via OnTouchListener or gesture detector, not the a11y semantic
 * action), this executor injects a real touch gesture via
 * AccessibilityService.dispatchGesture(). Generates MotionEvent DOWN/UP
 * through the normal input pipeline, indistinguishable from a human tap.
 *
 * Resolution flow:
 *   1. Resolve target via TargetResolver (same as ClickExecutor)
 *   2. Re-resolve to live AccessibilityNodeInfo for fresh bounds
 *   3. Tap the bounds center with a single 60ms stroke
 *
 * Notes:
 *   - Does NOT walk to a clickable ancestor. The whole point is that the
 *     visible node isn't a11y-clickable; we don't care about isClickable.
 *   - Requires android:canPerformGestures="true" in the service config XML.
 *     (Already enabled in perception_service_config.xml.)
 *   - dispatchGesture is asynchronous; we await completion via callback
 *     wrapped in suspendCancellableCoroutine.
 */
class GestureTapExecutor(
    private val snapshotCache: SnapshotCache
) : ActionExecutor {

    override val handles: ActionKind = ActionKind.GESTURE_TAP
    override val tier: ExecutorTier = ExecutorTier.ACCESSIBILITY

    override fun isAvailable(): Boolean = PerceptionService.get() != null

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        val tap = action as? AgentAction.GestureTap ?: return ExecutionResult.FatalFailure(
            reason = FailureReason.Unexpected("GestureTapExecutor received ${action.kind}"),
            durationMs = 0
        )

        val started = System.currentTimeMillis()
        val service = PerceptionService.get() ?: return ExecutionResult.Failed(
            reason = FailureReason.AccessibilityUnavailable,
            durationMs = 0
        )

        val resolve = TargetResolver.resolve(tap.target, tap.strategy, snapshotCache)
        val element = resolve.element ?: return ExecutionResult.Failed(
            reason = FailureReason.TargetNotFound(
                target = tap.target,
                strategiesTried = resolve.strategiesTried
            ),
            durationMs = System.currentTimeMillis() - started
        )

        // Get the freshest bounds by re-resolving against the live tree.
        val liveNode = LiveNodeFinder.find(service, element, allowPathFallback = true)
            ?: return ExecutionResult.Failed(
                reason = FailureReason.TargetNotFound(
                    target = tap.target,
                    strategiesTried = resolve.strategiesTried + "live_refind_failed"
                ),
                durationMs = System.currentTimeMillis() - started
            )

        val liveBounds = Rect()
        try {
            liveNode.getBoundsInScreen(liveBounds)
        } catch (t: Throwable) {
            LiveNodeFinder.safeRecycle(liveNode)
            return ExecutionResult.Failed(
                reason = FailureReason.Unexpected("getBoundsInScreen threw: ${t.message?.take(100)}"),
                durationMs = System.currentTimeMillis() - started
            )
        }
        LiveNodeFinder.safeRecycle(liveNode)

        if (liveBounds.isEmpty) {
            return ExecutionResult.Failed(
                reason = FailureReason.TargetNotFound(
                    target = tap.target,
                    strategiesTried = resolve.strategiesTried + "empty_bounds"
                ),
                durationMs = System.currentTimeMillis() - started
            )
        }

        val cx = liveBounds.centerX().toFloat()
        val cy = liveBounds.centerY().toFloat()

        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val completed = suspendCancellableCoroutine<Boolean> { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = try {
                service.dispatchGesture(gesture, callback, null)
            } catch (t: Throwable) {
                false
            }
            if (!dispatched && cont.isActive) cont.resume(false)
        }

        val dur = System.currentTimeMillis() - started
        return if (completed) {
            ExecutionResult.Executed(
                durationMs = dur,
                resultData = mapOf(
                    "target" to tap.target,
                    "resolved_type" to element.type.name,
                    "tap_x" to cx.toInt().toString(),
                    "tap_y" to cy.toInt().toString()
                )
            )
        } else {
            ExecutionResult.Failed(
                reason = FailureReason.SystemError(
                    "dispatchGesture rejected/cancelled for target '${tap.target}' at ($cx,$cy)"
                ),
                durationMs = dur
            )
        }
    }

    companion object {
        // 60ms is a typical human tap. Shorter risks being filtered as a glitch
        // by some apps' tap detectors; longer can trigger long-press.
        private const val TAP_DURATION_MS = 60L
    }
}