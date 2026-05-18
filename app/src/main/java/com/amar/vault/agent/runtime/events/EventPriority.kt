package com.amar.vault.agent.runtime.events

/**
 * Priority levels for event dispatch. Higher priority events preempt lower-priority
 * subscribers (the bus delivers them first when subscribers are draining).
 *
 * Ordering rationale:
 *   CRITICAL — system-corrupting events (overlay popups, package change mid-action).
 *              Must be processed before anything else can act on stale state.
 *   HIGH     — phase transitions, recovery dispatches, cancellations.
 *   NORMAL   — accessibility events, executor lifecycle.
 *   LOW      — diagnostics, heartbeats, telemetry that doesn't affect logic.
 */
enum class EventPriority(val rank: Int) {
    CRITICAL(0),
    HIGH(1),
    NORMAL(2),
    LOW(3);

    companion object {
        /**
         * Default priority for an event type. Overridable per-emission via
         * AccessibilityEventBus.publish(event, priority = ...).
         */
        fun defaultFor(event: AgentEvent): EventPriority = when (event) {
            is AgentEvent.Runtime.OverlayDetected,
            is AgentEvent.Runtime.WorkflowCancelled -> CRITICAL

            is AgentEvent.Runtime.RecoveryStarted,
            is AgentEvent.Runtime.RecoveryCompleted,
            is AgentEvent.Runtime.SearchActivated,
            is AgentEvent.Runtime.InputReadiness -> HIGH

            is AgentEvent.Accessibility,
            is AgentEvent.Executor,
            is AgentEvent.Runtime.ImeVisibilityChanged -> NORMAL

            is AgentEvent.Diagnostic -> LOW
        }
    }
}