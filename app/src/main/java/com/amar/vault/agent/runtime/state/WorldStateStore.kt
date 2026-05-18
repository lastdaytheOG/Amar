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

    companion object {
        private const val TAG = "WorldStateStore"
        private const val HISTORY_CAPACITY = 32
    }
}