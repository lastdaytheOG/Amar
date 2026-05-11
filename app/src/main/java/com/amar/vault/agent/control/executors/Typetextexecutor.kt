package com.amar.vault.agent.control.executors

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.control.ActionExecutor
import com.amar.vault.agent.control.ExecutionResult
import com.amar.vault.agent.control.ExecutorTier
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.SnapshotCache
import com.amar.vault.agent.perception.UiElementType

/**
 * Types text into an input field.
 *
 * Strategy:
 *   ACTION_SET_TEXT with ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE. Works on every
 *   modern EditText and TextInputLayout. Does NOT require focus — Android routes
 *   SET_TEXT directly to the view, not through the IME.
 *
 * Empty text semantics:
 *   Empty text = clear the field. Native behavior, no special case.
 *
 * Fallback NOT implemented (deferred from v1):
 *   Clipboard paste for custom inputs (Flutter, some Compose IMEs). Marked
 *   TODO(type-text-v2). Requires clipboard permissions and a real failing case
 *   to justify the complexity.
 *
 * Self-verification:
 *   Read the target's text back after setting. If it matches exactly, return
 *   ExecutedAndVerified — skipping the envelope's verify block saves 150-1500ms
 *   on the fast path. If read-back differs (IME buffering, password masking,
 *   transformation), fall through to Executed and let VerificationEngine handle it.
 *
 * Path fallback DISABLED:
 *   Unlike Click, typing into the wrong element because of path drift is a
 *   serious failure (wrong field filled, potentially with sensitive data).
 *   Rather fail cleanly and let retry re-resolve via resourceId/text.
 *
 * Tier: ACCESSIBILITY.
 */
class TypeTextExecutor(
    private val snapshotCache: SnapshotCache
) : ActionExecutor {

    override val handles: ActionKind = ActionKind.TYPE_TEXT
    override val tier: ExecutorTier = ExecutorTier.ACCESSIBILITY

    override fun isAvailable(): Boolean = PerceptionService.get() != null

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        val type = action as? AgentAction.TypeText ?: return ExecutionResult.FatalFailure(
            reason = FailureReason.Unexpected("TypeTextExecutor received ${action.kind}"),
            durationMs = 0
        )

        val started = System.currentTimeMillis()
        val service = PerceptionService.get() ?: return ExecutionResult.Failed(
            reason = FailureReason.AccessibilityUnavailable,
            durationMs = 0
        )

        val resolve = TargetResolver.resolve(type.target, type.strategy, snapshotCache)
        val element = resolve.element ?: return ExecutionResult.Failed(
            reason = FailureReason.TargetNotFound(
                target = type.target,
                strategiesTried = resolve.strategiesTried
            ),
            durationMs = System.currentTimeMillis() - started
        )

        // The target should have been an editable field. If it wasn't, that's a
        // plan-quality issue — return TargetNotFound rather than silently typing
        // into a non-editable view and failing verify later.
        if (!element.editable && element.type != UiElementType.INPUT) {
            return ExecutionResult.Failed(
                reason = FailureReason.TargetNotFound(
                    target = type.target,
                    strategiesTried = resolve.strategiesTried + "target_not_editable"
                ),
                durationMs = System.currentTimeMillis() - started
            )
        }

        val liveNode = LiveNodeFinder.find(
            service = service,
            element = element,
            preferEditable = true,
            allowPathFallback = false   // path fallback too risky for text entry
        ) ?: return ExecutionResult.Failed(
            reason = FailureReason.TargetNotFound(
                target = type.target,
                strategiesTried = resolve.strategiesTried + "live_refind_failed"
            ),
            durationMs = System.currentTimeMillis() - started
        )

        if (!liveNode.isEnabled) {
            LiveNodeFinder.safeRecycle(liveNode)
            return ExecutionResult.Failed(
                reason = FailureReason.TargetNotFound(
                    target = type.target,
                    strategiesTried = resolve.strategiesTried + "target_disabled"
                ),
                durationMs = System.currentTimeMillis() - started
            )
        }

        val setOk = try {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    type.text
                )
            }
            liveNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            false
        }

        if (!setOk) {
            LiveNodeFinder.safeRecycle(liveNode)
            return ExecutionResult.Failed(
                reason = FailureReason.SystemError(
                    "ACTION_SET_TEXT rejected for target '${type.target}'. " +
                            "Field may use a custom input surface (Flutter/Compose custom IME)."
                ),
                durationMs = System.currentTimeMillis() - started
            )
        }

        val readBackMatches = try {
            liveNode.text?.toString().orEmpty() == type.text
        } catch (t: Throwable) {
            false
        }

        LiveNodeFinder.safeRecycle(liveNode)

        val dur = System.currentTimeMillis() - started
        val resultData = mapOf(
            "target" to type.target,
            "length" to type.text.length.toString()
        )

        return if (readBackMatches) {
            ExecutionResult.ExecutedAndVerified(dur, resultData)
        } else {
            // performAction returned true but text didn't round-trip.
            // Executor is done; VerificationEngine can take over.
            ExecutionResult.Executed(dur, resultData)
        }
    }
}