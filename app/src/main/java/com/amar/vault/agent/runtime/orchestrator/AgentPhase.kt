package com.amar.vault.agent.runtime.orchestrator

/**
 * Phases a workflow moves through. The PhaseOrchestrator is the state
 * machine over these.
 *
 * # Lifecycle
 * Normal happy path:
 *   IDLE → PLANNING → EXECUTING → VERIFYING → COMPLETE
 *
 * Recovery cycle when an overlay interrupts:
 *   ...any phase... → RECOVERING → (back to previous phase or ABORTED)
 *
 * # Why phases at all (when UiSearchExecutor already works)
 * Phases give the orchestrator a single place to enforce invariants:
 *   - "Never inject during RECOVERING."
 *   - "Always validate WorldState before transitioning to EXECUTING."
 *   - "If we re-enter RECOVERING within 5s of leaving it, abort —
 *     we're in a recovery loop."
 * Without phases, every executor has to enforce these itself. Phases
 * let the orchestrator do it once.
 *
 * # Today
 * The orchestrator EXISTS but doesn't wrap existing executors yet. Step 12
 * is scaffolding — Step 13+ will migrate executors onto it.
 */
enum class AgentPhase {
    /** No active workflow. Default initial state. */
    IDLE,

    /** Resolving intent → execution plan. Cheap; no UI interaction. */
    PLANNING,

    /** Running the actual UI automation. Sensitive to interruption. */
    EXECUTING,

    /** Confirming the goal succeeded (text appeared, results loaded). */
    VERIFYING,

    /** Goal completed successfully. Terminal. */
    COMPLETE,

    /** Goal aborted (overlay, cancellation, error). Terminal. */
    ABORTED,

    /**
     * Workflow paused: an overlay is blocking, or another error needs
     * recovery. RecoveryEngine acts during this phase; orchestrator
     * resumes the prior phase on success.
     */
    RECOVERING
}

/**
 * Reason a workflow transitioned to ABORTED or RECOVERING. Stored on
 * the workflow context so callers can decide whether to retry.
 */
sealed class PhaseTransitionReason {

    /** Normal flow — no special reason. */
    data object Normal : PhaseTransitionReason()

    /** An OverlayDetected event fired during execution. */
    data class OverlayBlocked(val overlayType: String) : PhaseTransitionReason()

    /** Foreground package unexpectedly changed (user navigated away). */
    data class PackageChanged(val expected: String, val actual: String?) : PhaseTransitionReason()

    /** RecoveryEngine attempted dismissal and failed. */
    data class RecoveryFailed(val attempts: Int) : PhaseTransitionReason()

    /** Recovery cycle: we just left RECOVERING and immediately re-entered. */
    data class RecoveryLoop(val withinMs: Long) : PhaseTransitionReason()

    /** Explicit cancel from caller. */
    data object UserCancelled : PhaseTransitionReason()

    /** Phase-specific timeout. */
    data class Timeout(val phase: AgentPhase, val limitMs: Long) : PhaseTransitionReason()
}