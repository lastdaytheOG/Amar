package com.amar.vault.agent.state

import android.util.Log
import com.amar.vault.agent.dsl.ActionKind
import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth for the agent's current phase.
 *
 * Thread-safe via AtomicReference. PhaseController is NOT a Hilt singleton
 * by design — each task gets its own instance so concurrent tasks don't
 * interfere. UiSearchExecutor creates one per execute() call.
 *
 * Action gating:
 *   isActionAllowed(action) returns false if the action would violate the
 *   current phase's rules. The caller (executor or coordinator) must
 *   refuse to execute when this returns false and either:
 *     - escalate to RECOVERY phase, or
 *     - log a CRITICAL VIOLATION (architecture bug)
 */
class PhaseController(initial: AgentPhase = AgentPhase.NAVIGATION) {

    private val current = AtomicReference(initial)
    private val transitions = mutableListOf<Transition>()

    val phase: AgentPhase get() = current.get()

    /**
     * Attempt a phase transition. Returns true if accepted, false if rejected.
     */
    fun transitionTo(target: AgentPhase, reason: String): Boolean {
        val from = current.get()
        if (!isLegal(from, target)) {
            Log.w(TAG, "PHASE_TRANSITION_REJECTED $from → $target ($reason)")
            return false
        }
        if (!current.compareAndSet(from, target)) {
            return transitionTo(target, reason)
        }
        synchronized(transitions) {
            transitions += Transition(from, target, reason, System.currentTimeMillis())
        }
        Log.i(TAG, "PHASE_TRANSITION $from → $target ($reason)")
        return true
    }

    /**
     * Force a transition regardless of legality. Used only by RecoveryEngine
     * (Phase 5) when rolling back from RECOVERY to a known-good phase.
     */
    fun forceTransition(target: AgentPhase, reason: String) {
        val from = current.getAndSet(target)
        synchronized(transitions) {
            transitions += Transition(from, target, "FORCE: $reason", System.currentTimeMillis())
        }
        Log.w(TAG, "PHASE_TRANSITION_FORCED $from → $target ($reason)")
    }

    /**
     * Is this ActionKind allowed in the current phase?
     *
     * Rules:
     *   NAVIGATION: open_app, click, gesture_tap, scroll, search_app, home, sequence
     *   INPUT:      type_text, gesture_tap (only on locked target), wait, read_screen
     *               click on non-locked target = VIOLATION (enforced in Phase 4)
     *   EXECUTION:  read_screen, wait only
     *   RECOVERY:   home, wait, read_screen
     *   FAILURE:    nothing allowed
     */
    fun isActionAllowed(kind: ActionKind): Boolean = when (current.get()) {
        AgentPhase.NAVIGATION -> kind in NAV_ALLOWED
        AgentPhase.INPUT      -> kind in INPUT_ALLOWED
        AgentPhase.EXECUTION  -> kind in EXEC_ALLOWED
        AgentPhase.RECOVERY   -> kind in RECOVERY_ALLOWED
        AgentPhase.FAILURE    -> false
    }

    fun history(): List<Transition> = synchronized(transitions) { transitions.toList() }

    data class Transition(
        val from: AgentPhase,
        val to: AgentPhase,
        val reason: String,
        val atMillis: Long
    )

    companion object {
        private const val TAG = "PhaseController"

        private fun isLegal(from: AgentPhase, to: AgentPhase): Boolean = when (from) {
            AgentPhase.NAVIGATION -> to in setOf(AgentPhase.INPUT, AgentPhase.RECOVERY, AgentPhase.FAILURE)
            AgentPhase.INPUT      -> to in setOf(AgentPhase.EXECUTION, AgentPhase.RECOVERY, AgentPhase.FAILURE)
            AgentPhase.EXECUTION  -> to in setOf(AgentPhase.INPUT, AgentPhase.RECOVERY, AgentPhase.FAILURE)
            AgentPhase.RECOVERY   -> to in setOf(AgentPhase.INPUT, AgentPhase.NAVIGATION, AgentPhase.FAILURE)
            AgentPhase.FAILURE    -> false
        }

        // Aligned to ActionKind enum names that actually exist in your codebase.
        private val NAV_ALLOWED = setOf(
            ActionKind.OPEN_APP,
            ActionKind.SEARCH_APP,
            ActionKind.HOME,
            ActionKind.CLICK,
            ActionKind.GESTURE_TAP,
            ActionKind.SCROLL,
            ActionKind.WAIT,
            ActionKind.READ_SCREEN,
            ActionKind.SEQUENCE
        )

        private val INPUT_ALLOWED = setOf(
            ActionKind.TYPE_TEXT,
            ActionKind.GESTURE_TAP,   // allowed only on the locked target (enforced Phase 4)
            ActionKind.WAIT,
            ActionKind.READ_SCREEN
        )

        private val EXEC_ALLOWED = setOf(
            ActionKind.READ_SCREEN,
            ActionKind.WAIT
        )

        private val RECOVERY_ALLOWED = setOf(
            ActionKind.HOME,
            ActionKind.WAIT,
            ActionKind.READ_SCREEN
        )
    }
}