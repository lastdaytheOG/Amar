package com.amar.vault.agent.control.executors

import android.accessibilityservice.AccessibilityService
import com.amar.vault.agent.control.ActionExecutor
import com.amar.vault.agent.control.ExecutionResult
import com.amar.vault.agent.control.ExecutorTier
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.perception.PerceptionService

/**
 * Navigates to the launcher (Home screen).
 *
 * Implementation: dispatches GLOBAL_ACTION_HOME via the AccessibilityService.
 * This is the ONLY reliable way to reach the home screen programmatically since
 * Android 10 — using Intent.ACTION_MAIN + CATEGORY_HOME works but triggers a
 * "for security reasons" UI on some OEMs, and ACTION_CLOSE_SYSTEM_DIALOGS was
 * restricted in API 31.
 *
 * Tier: ACCESSIBILITY — there is no Intent-tier alternative that works
 * consistently across devices.
 *
 * Use cases:
 *   - Cancel a multi-step flow and return to a known state.
 *   - Escape an app that's not responding to back navigation.
 *   - Plan primitive for "after doing X in App A, go home before starting App B"
 *     — occasionally necessary because direct app-to-app transitions preserve
 *     state in ways that break verify.
 *
 * Verification:
 *   Android's home action is synchronous at the dispatch level (returns true
 *   if the system accepted the gesture), but the actual transition takes
 *   200-500ms. We return Executed, not ExecutedAndVerified, so the envelope's
 *   verify block can poll for foreground package == launcher if desired.
 */
class HomeExecutor : ActionExecutor {

    override val handles: ActionKind = ActionKind.HOME
    override val tier: ExecutorTier = ExecutorTier.ACCESSIBILITY

    override fun isAvailable(): Boolean = PerceptionService.get() != null

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        if (action !is AgentAction.Home) {
            return ExecutionResult.FatalFailure(
                reason = FailureReason.Unexpected("HomeExecutor received ${action.kind}"),
                durationMs = 0
            )
        }

        val service = PerceptionService.get() ?: return ExecutionResult.Failed(
            reason = FailureReason.AccessibilityUnavailable,
            durationMs = 0
        )

        val started = System.currentTimeMillis()
        val ok = try {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        } catch (t: Throwable) {
            false
        }
        val dur = System.currentTimeMillis() - started

        return if (ok) {
            ExecutionResult.Executed(
                durationMs = dur,
                resultData = mapOf("action" to "home")
            )
        } else {
            ExecutionResult.Failed(
                reason = FailureReason.SystemError(
                    "GLOBAL_ACTION_HOME rejected — service may be suspended by system"
                ),
                durationMs = dur
            )
        }
    }
}