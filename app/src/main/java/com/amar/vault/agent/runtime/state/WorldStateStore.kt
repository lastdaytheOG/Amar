package com.amar.vault.agent.runtime.state

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current [WorldState] and exposes a StateFlow for subscribers.
 *
 * Responsibilities:
 *   - Single owner of the state instance.
 *   - Atomic mutation via [update] — no torn reads, no race windows.
 *   - History buffer of the last N states (default 32) for replayable
 *     debugging. Buffer is NOT persisted; reset on process restart.
 *
 * NOT responsible for:
 *   - Deciding HOW to compute the next state from an event — that's the
 *     reducer (Step 3). The store simply applies whatever the reducer returns.
 *   - Validating state transitions — semantically illegal transitions are a
 *     reducer concern. The store accepts whatever it's handed.
 *
 * Thread safety:
 *   StateFlow.update {} is atomic. Multiple threads calling update
 *   concurrently are linearized internally; no external locking needed.
 *
 * Hilt:
 *   @Singleton — exactly one instance per app process. Hilt injects it via
 *   the empty constructor.
 */
@Singleton
class WorldStateStore @Inject constructor() {

    private val _state = MutableStateFlow(WorldState.INITIAL)
    val state: StateFlow<WorldState> = _state.asStateFlow()

    // Ring buffer of recent states. Bounded to prevent memory growth.
    private val historyLock = Any()
    private val history = ArrayDeque<WorldState>(HISTORY_CAPACITY).apply {
        add(WorldState.INITIAL)
    }

    /** Current state snapshot. Read-only. */
    fun current(): WorldState = _state.value

    /**
     * Apply a reducer function atomically. The function MUST be pure — no I/O,
     * no side effects, just (oldState) -> newState. The store handles
     * persistence to history and Flow emission.
     *
     * Returns the new state.
     */
    fun reduce(transform: (WorldState) -> WorldState): WorldState {
        var newState: WorldState = current()
        _state.update { old ->
            newState = transform(old).copy(
                reductionCount = old.reductionCount + 1,
                lastReducedAtMillis = System.currentTimeMillis()
            )
            newState
        }
        recordHistory(newState)
        if (newState.foregroundPackage != _state.value.foregroundPackage) {
            Log.i(TAG, "WORLDSTATE_PKG_CHANGED ${_state.value.foregroundPackage} -> ${newState.foregroundPackage}")
        }
        return newState
    }

    /**
     * Force the state to a specific value, bypassing the reducer. Used only
     * for tests and recovery rollbacks (Step 12). Avoid in normal flow.
     */
    fun forceState(state: WorldState, reason: String) {
        _state.value = state.copy(
            lastReducedAtMillis = System.currentTimeMillis()
        )
        recordHistory(_state.value)
        Log.w(TAG, "WORLDSTATE_FORCED reason=$reason")
    }

    /**
     * Return a copy of the recent state history, oldest first.
     * For debugging / failure dumps.
     */
    fun history(): List<WorldState> = synchronized(historyLock) {
        history.toList()
    }

    /**
     * Reset to INITIAL. Test/debug only.
     */
    fun resetForTest() {
        _state.value = WorldState.INITIAL
        synchronized(historyLock) {
            history.clear()
            history.add(WorldState.INITIAL)
        }
    }

    private fun recordHistory(s: WorldState) {
        synchronized(historyLock) {
            if (history.size >= HISTORY_CAPACITY) {
                history.removeFirst()
            }
            history.addLast(s)
        }
    }

    // ====================================================================
    // Phase 5: Circuit Breaker — target-app liveness tracking
    // ====================================================================

    /**
     * Begin watching for the target package's heartbeat. Called by the
     * executor when a workflow begins. The reducer will update
     * lastTargetSeenAtMillis on every accessibility event that names this
     * package; the executor periodically checks [isTargetDead].
     */
    fun startTrackingTarget(packageName: String) {
        reduce { old ->
            old.copy(
                circuitBreakerTargetPackage = packageName,
                lastTargetSeenAtMillis = System.currentTimeMillis(),
                lastInterruptingPackage = null
            )
        }
        Log.i(TAG, "CIRCUIT_BREAKER tracking pkg=$packageName")
    }

    /**
     * Stop tracking the target. Called by the executor when a workflow ends
     * (success or failure). The breaker becomes inert and isTargetDead()
     * returns false.
     */
    fun stopTrackingTarget() {
        val prev = current().circuitBreakerTargetPackage
        reduce { old ->
            old.copy(
                circuitBreakerTargetPackage = null,
                lastTargetSeenAtMillis = 0L,
                lastInterruptingPackage = null
            )
        }
        if (prev != null) {
            Log.i(TAG, "CIRCUIT_BREAKER stopped tracking pkg=$prev")
        }
    }

    /**
     * Update target-heartbeat timestamp if the given package matches the
     * tracked target. Called by PerceptionService on every relevant
     * accessibility event. Idempotent and cheap.
     *
     * Returns the action taken so callers can log diagnostics:
     *   "heartbeat"   — eventPackage matched tracked target, timestamp updated
     *   "system"      — eventPackage is a known harmless system overlay (no update)
     *   "interrupt"   — eventPackage is unknown and not the target (recorded, no trip)
     *   "idle"        — no target being tracked
     */
    fun updateTargetHeartbeat(eventPackage: String?): String {
        if (eventPackage == null) return "idle"
        val tracked = current().circuitBreakerTargetPackage ?: return "idle"

        return when {
            eventPackage == tracked || eventPackage.startsWith("$tracked:") -> {
                reduce { old ->
                    old.copy(lastTargetSeenAtMillis = System.currentTimeMillis())
                }
                "heartbeat"
            }
            PERMITTED_SYSTEM_PACKAGES.contains(eventPackage) -> {
                // Known harmless overlay (keyboard, permission dialog, etc).
                // Don't update timestamp, don't tip the breaker.
                "system"
            }
            else -> {
                // Unexpected foreground — record but don't trip; the predicate
                // decides based on time-since-last-heartbeat, not on a single event.
                reduce { old ->
                    old.copy(lastInterruptingPackage = eventPackage)
                }
                "interrupt"
            }
        }
    }

    /**
     * Circuit-breaker predicate: returns true if the tracked target package
     * has not been observed in any accessibility event for more than the
     * absence window (default 1200ms). Returns false when no target is
     * being tracked.
     *
     * Time-based rather than event-count-based: Android event density is
     * unpredictable. A burst of 15 launcher events in 50ms shouldn't trip;
     * a single confirmed dead app for 1.2s should.
     */
    fun isTargetDead(allowedAbsenceMs: Long = DEFAULT_ABSENCE_WINDOW_MS): Boolean {
        val s = current()
        val target = s.circuitBreakerTargetPackage ?: return false
        if (s.lastTargetSeenAtMillis == 0L) return false
        val sinceSeen = System.currentTimeMillis() - s.lastTargetSeenAtMillis
        return sinceSeen > allowedAbsenceMs
    }

    companion object {
        private const val TAG = "WorldStateStore"

        /** Default absence window for the circuit breaker. */
        private const val DEFAULT_ABSENCE_WINDOW_MS = 1200L

        /**
         * Packages that may legitimately take foreground focus during a
         * workflow without indicating a target-app crash. Keyboards,
         * permission sheets, system UI overlays. When one of these is
         * the event package, the breaker holds its current timestamp
         * but doesn't trip.
         */
        private val PERMITTED_SYSTEM_PACKAGES: Set<String> = setOf(
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.google.android.inputmethod.pinyin",
            "com.touchtype.swiftkey",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "android"
        )
        private const val HISTORY_CAPACITY = 32
    }
}