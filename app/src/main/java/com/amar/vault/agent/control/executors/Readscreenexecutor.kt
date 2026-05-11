package com.amar.vault.agent.control.executors

import com.amar.vault.agent.control.ActionExecutor
import com.amar.vault.agent.control.ExecutionResult
import com.amar.vault.agent.control.ExecutorTier
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.perception.CaptureReason
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.UiSnapshot

/**
 * Captures the current UI state and returns a compact summary as result data.
 *
 * Purpose in the plan:
 *   The Brain uses read_screen when it needs to make a decision based on
 *   current UI — "did the send succeed?", "what confirmation dialog appeared?",
 *   "which item in this list matches the user's query?". read_screen is the
 *   primary way the Brain gets perceptual feedback into its next planning step.
 *
 * Output shape (resultData map):
 *   package         → foreground package id
 *   window_class    → foreground activity class (or null)
 *   element_count   → number of noteworthy elements
 *   truncated       → "true" if the walker hit the cap
 *   top_text        → up to TOP_TEXT_COUNT visible text strings, joined with " | "
 *   top_buttons     → up to TOP_TEXT_COUNT button labels, joined with " | "
 *   top_inputs      → hints/text of up to TOP_TEXT_COUNT input fields, joined with " | "
 *
 * Why summary-form and not the full element list:
 *   resultData is Map<String, String> (see TaskState.Succeeded) because it has
 *   to survive checkpoint serialization and logs. Dumping 200 elements would
 *   blow checkpoint sizes and produce unreadable logs. The Brain doesn't need
 *   the full tree every time — it needs salient signals. If a future Brain
 *   needs richer perception it can call the SnapshotCache directly via a
 *   perception-aware Tool (out of scope for v1 DSL).
 *
 * Tier: ACCESSIBILITY (requires the service to walk the tree).
 *
 * Verification:
 *   Inherently self-verifying — we either got a snapshot or we didn't. Returns
 *   ExecutedAndVerified on success to skip the envelope's verify block.
 */
class ReadScreenExecutor : ActionExecutor {

    override val handles: ActionKind = ActionKind.READ_SCREEN
    override val tier: ExecutorTier = ExecutorTier.ACCESSIBILITY

    override fun isAvailable(): Boolean = PerceptionService.get() != null

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        if (action !is AgentAction.ReadScreen) {
            return ExecutionResult.FatalFailure(
                reason = FailureReason.Unexpected("ReadScreenExecutor received ${action.kind}"),
                durationMs = 0
            )
        }

        val service = PerceptionService.get() ?: return ExecutionResult.Failed(
            reason = FailureReason.AccessibilityUnavailable,
            durationMs = 0
        )

        val started = System.currentTimeMillis()

        // Force a fresh walk. read_screen is about the CURRENT state, not a
        // cached approximation — the Brain is going to condition on this.
        val snapshot = try {
            service.forceSnapshot(CaptureReason.ON_DEMAND)
        } catch (t: Throwable) {
            return ExecutionResult.Failed(
                reason = FailureReason.SystemError("forceSnapshot threw: ${t.message}"),
                durationMs = System.currentTimeMillis() - started
            )
        }

        val dur = System.currentTimeMillis() - started
        return ExecutionResult.ExecutedAndVerified(
            durationMs = dur,
            resultData = summarize(snapshot)
        )
    }

    private fun summarize(snap: UiSnapshot): Map<String, String> {
        val topText = snap.elements
            .asSequence()
            .filter { it.type == com.amar.vault.agent.perception.UiElementType.TEXT }
            .mapNotNull { it.text }
            .take(TOP_TEXT_COUNT)
            .joinToString(SEP)

        val topButtons = snap.elements
            .asSequence()
            .filter { it.type == com.amar.vault.agent.perception.UiElementType.BUTTON || it.clickable }
            .mapNotNull { it.text ?: it.contentDesc }
            .filter { it.isNotBlank() }
            .take(TOP_TEXT_COUNT)
            .joinToString(SEP)

        val topInputs = snap.elements
            .asSequence()
            .filter { it.editable || it.type == com.amar.vault.agent.perception.UiElementType.INPUT }
            .map { el ->
                // Prefer the visible current-value, but fall back to hint when empty.
                el.text?.takeIf { it.isNotBlank() } ?: el.hint ?: el.contentDesc ?: "<input>"
            }
            .take(TOP_TEXT_COUNT)
            .joinToString(SEP)

        return buildMap {
            put("package", snap.packageId ?: "")
            put("window_class", snap.windowClass ?: "")
            put("element_count", snap.size.toString())
            put("truncated", snap.truncated.toString())
            put("top_text", topText.take(MAX_SUMMARY_LEN))
            put("top_buttons", topButtons.take(MAX_SUMMARY_LEN))
            put("top_inputs", topInputs.take(MAX_SUMMARY_LEN))
        }
    }

    companion object {
        private const val TOP_TEXT_COUNT = 12
        private const val MAX_SUMMARY_LEN = 800   // per-field cap — keeps checkpoint rows reasonable
        private const val SEP = " | "
    }
}