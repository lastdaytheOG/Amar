package com.amar.vault.agent.runtime.events

/**
 * Strongly-typed sealed hierarchy of every event the agent runtime emits.
 *
 * Design:
 *   - Sealed: compiler enforces exhaustive when-handling in reducers.
 *   - Immutable: events are facts about what happened, never mutated.
 *   - Timestamped: every event carries its emission time for causality analysis.
 *   - Categorized: events fall into Accessibility (raw OS), Executor (action lifecycle),
 *     Runtime (orchestration), or Diagnostic (telemetry).
 *
 * Adding events:
 *   When a new event type is needed, add it under the appropriate category. The
 *   reducer in Step 3 will be updated to handle it. NEVER add untyped events
 *   (no Map<String, Any>, no String payloads) — the type system is the contract.
 */
sealed class AgentEvent {

    /** Wall-clock time the event was emitted, ms since epoch. */
    abstract val atMillis: Long

    /**
     * Monotonic sequence number assigned by the bus when published.
     * 0 means not yet published. Used for replay ordering and dedup.
     */
    open val sequence: Long = 0L

    // ========================================================================
    // ACCESSIBILITY EVENTS — raw OS facts, no interpretation
    // ========================================================================

    sealed class Accessibility : AgentEvent() {

        data class WindowStateChanged(
            val packageId: String?,
            val windowClass: String?,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Accessibility()

        data class WindowContentChanged(
            val packageId: String?,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Accessibility()

        data class WindowsChanged(
            val packageIds: List<String>,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Accessibility()

        data class ViewFocused(
            val packageId: String?,
            val resourceId: String?,
            val className: String?,
            val isEditable: Boolean,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Accessibility()

        data class TextChanged(
            val packageId: String?,
            val resourceId: String?,
            val beforeText: String?,
            val afterText: String?,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Accessibility()

        data class TextSelectionChanged(
            val packageId: String?,
            val resourceId: String?,
            val selectionStart: Int,
            val selectionEnd: Int,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Accessibility()
    }

    // ========================================================================
    // EXECUTOR EVENTS — action lifecycle, emitted by executors
    // ========================================================================

    sealed class Executor : AgentEvent() {

        data class InjectionStarted(
            val executorName: String,
            val strategy: String,
            val targetIdentity: String,
            val payload: String,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Executor()

        data class InjectionSucceeded(
            val executorName: String,
            val strategy: String,
            val targetIdentity: String,
            val confidence: Float,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Executor()

        data class InjectionFailed(
            val executorName: String,
            val strategy: String,
            val targetIdentity: String,
            val reason: String,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Executor()
    }

    // ========================================================================
    // RUNTIME EVENTS — orchestration, phases, overlays
    // ========================================================================

    sealed class Runtime : AgentEvent() {

        data class SearchActivated(
            val packageId: String,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Runtime()

        data class ImeVisibilityChanged(
            val visible: Boolean,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Runtime()

        data class InputReadiness(
            val ready: Boolean,
            val reason: String,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Runtime()

        data class RecoveryStarted(
            val cause: String,
            val fromPhase: String,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Runtime()

        data class RecoveryCompleted(
            val success: Boolean,
            val resumedPhase: String,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Runtime()

        data class OverlayDetected(
            val overlayType: String,
            val dismissed: Boolean,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Runtime()

        data class WorkflowCancelled(
            val workflowId: String,
            val cause: String,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Runtime()
    }

    // ========================================================================
    // DIAGNOSTIC EVENTS — telemetry, never affects state
    // ========================================================================

    sealed class Diagnostic : AgentEvent() {

        data class Heartbeat(
            val component: String,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Diagnostic()

        data class CriticalViolation(
            val component: String,
            val description: String,
            override val atMillis: Long = System.currentTimeMillis(),
            override val sequence: Long = 0L
        ) : Diagnostic()
    }
}