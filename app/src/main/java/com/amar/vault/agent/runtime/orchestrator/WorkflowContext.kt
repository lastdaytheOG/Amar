package com.amar.vault.agent.runtime.orchestrator

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-workflow state container. Holds the current [AgentPhase], history
 * of phase transitions, recovery counters, and metadata for diagnostics.
 *
 * # Design
 * Each call to [PhaseOrchestrator.runWorkflow] creates one WorkflowContext.
 * The context is passed into executor functions so they can:
 *   - read the current phase
 *   - check cancellation
 *   - report progress
 *   - request a transition via the orchestrator (NOT mutate phase directly)
 *
 * # Immutability of phase
 * The current phase is held in an AtomicReference. Only the orchestrator
 * mutates it. Executors observe but cannot write. This prevents executors
 * from accidentally bypassing the state machine's invariant checks.
 */
class WorkflowContext(
    val workflowId: String = UUID.randomUUID().toString().take(8),
    val goal: String,
    val targetPackage: String,
    val startedAtMillis: Long = System.currentTimeMillis()
) {

    private val _phase = AtomicReference(AgentPhase.IDLE)

    /** Current phase. Read-only for callers; only orchestrator writes. */
    private val historyLock = Any()
    private val _history = mutableListOf<PhaseHistoryEntry>()

    /** Full transition history. Snapshotted on read; safe to inspect. */
    val history: List<PhaseHistoryEntry>
        get() = synchronized(historyLock) { _history.toList() }

    /** Current phase. Read-only for callers; only orchestrator writes. */
    val phase: AgentPhase get() = _phase.get()

    private val _recoveryEntries = AtomicInteger(0)

    /** Number of times this workflow has entered RECOVERING. */
    val recoveryEntries: Int get() = _recoveryEntries.get()

    private val _lastRecoveryEndedAtMs = AtomicLong(0L)

    /** Last timestamp when we left RECOVERING. Used for loop detection. */
    val lastRecoveryEndedAtMs: Long get() = _lastRecoveryEndedAtMs.get()

    /**
     * Orchestrator-only: transition to a new phase. Returns the previous
     * phase. Records history. Increments recovery counter if entering
     * RECOVERING.
     */
    internal fun transitionTo(
        next: AgentPhase,
        reason: PhaseTransitionReason
    ): AgentPhase {
        val prev = _phase.getAndSet(next)
        synchronized(historyLock) {
            _history.add(PhaseHistoryEntry(prev, next, reason, System.currentTimeMillis()))
        }
        if (next == AgentPhase.RECOVERING) {
            _recoveryEntries.incrementAndGet()
        }
        if (prev == AgentPhase.RECOVERING && next != AgentPhase.RECOVERING) {
            _lastRecoveryEndedAtMs.set(System.currentTimeMillis())
        }
        return prev
    }

    data class PhaseHistoryEntry(
        val from: AgentPhase,
        val to: AgentPhase,
        val reason: PhaseTransitionReason,
        val atMillis: Long
    )

    /** Total wall-clock time since workflow start. */
    fun elapsedMs(): Long = System.currentTimeMillis() - startedAtMillis
}