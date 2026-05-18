package com.amar.vault.agent.runtime.ime

import android.util.Log
import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.events.AgentEvent
import com.amar.vault.agent.runtime.state.WorldStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscribes to bus signals and computes [AgentEvent.Runtime.InputReadiness].
 *
 * Readiness logic (per the architecture doc):
 *   ready = imeVisible
 *         AND focusedEditable exists
 *         AND a TextSelectionChanged event for the focused package was
 *             observed within RECENT_SELECTION_MS (default 1500ms)
 *
 * Why all three:
 *   - imeVisible alone is insufficient — modern apps (Flutter, Compose, RN)
 *     show the keyboard BEFORE binding the InputConnection.
 *   - focusedEditable alone is insufficient — a focused editable with no
 *     IME bound rejects ACTION_SET_TEXT silently (the WhatsApp pattern).
 *   - selection-changed alone is insufficient — events from older windows
 *     should not satisfy the current task's readiness.
 *
 * Publishes:
 *   AgentEvent.Runtime.InputReadiness on every transition. The reducer
 *   then updates WorldState.inputConnectionReady which the Injection Engine
 *   (Step 8) waits on instead of polling.
 */
@Singleton
class InputConnectionMonitor @Inject constructor(
    private val bus: AccessibilityEventBus,
    private val store: WorldStateStore,
    private val selectionTracker: SelectionTracker
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    @Volatile private var lastReadyEmitted: Boolean? = null

    /**
     * Start monitoring. Idempotent. Called from Step 6 wiring (AmarApplication).
     */
    fun start() {
        if (monitorJob?.isActive == true) {
            Log.w(TAG, "INPUT_MONITOR_START_IGNORED already running")
            return
        }
        Log.i(TAG, "INPUT_MONITOR_START")

        // Recompute readiness whenever imeVisible, focusedEditable, or foregroundPackage
        // changes. distinctUntilChanged avoids spamming the bus.
        monitorJob = store.state
            .map { Triple(it.imeVisible, it.focusedEditableIdentity != null, it.foregroundPackage) }
            .distinctUntilChanged()
            .onEach { (imeVisible, hasFocusedEditable, pkg) -> recompute(imeVisible, hasFocusedEditable, pkg) }
            .launchIn(scope)
    }

    fun stop() {
        Log.i(TAG, "INPUT_MONITOR_STOP")
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun recompute(imeVisible: Boolean, hasFocusedEditable: Boolean, pkg: String?) {
        val recentSelection = selectionTracker.isRecent(pkg, RECENT_SELECTION_MS)
        val ready = imeVisible && hasFocusedEditable && recentSelection

        val previous = lastReadyEmitted
        if (previous == ready) return  // no change

        lastReadyEmitted = ready
        val reason = if (ready) "ime+editable+selection_recent" else
            "missing(ime=$imeVisible editable=$hasFocusedEditable selection=$recentSelection)"

        bus.publish(AgentEvent.Runtime.InputReadiness(ready = ready, reason = reason))
        Log.i(TAG, "INPUT_READINESS ready=$ready reason=$reason pkg=$pkg")
    }

    /**
     * Direct hook for SelectionTracker to nudge a recompute even when no
     * state field changed. SelectionTracker updates aren't visible through
     * WorldState because we deliberately don't store selection data there.
     */
    fun onSelectionRecorded(packageId: String?) {
        val s = store.current()
        if (s.foregroundPackage == packageId) {
            recompute(s.imeVisible, s.focusedEditableIdentity != null, s.foregroundPackage)
        }
    }

    companion object {
        private const val TAG = "InputConnectionMonitor"
        private const val RECENT_SELECTION_MS = 1_500L
    }
}