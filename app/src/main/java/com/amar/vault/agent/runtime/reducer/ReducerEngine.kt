package com.amar.vault.agent.runtime.reducer

import android.util.Log
import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.events.AgentEvent
import com.amar.vault.agent.runtime.state.WorldStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscribes to [AccessibilityEventBus], applies [AccessibilityReducer], and
 * writes the result to [WorldStateStore].
 *
 * This is the "Reducer Engine" from the architecture doc — the runtime piece
 * that wires the pure reducer function into a live event stream.
 *
 * Lifecycle:
 *   - Constructed by Hilt at app process start (eagerly via initializer in
 *     the Application class — see wiring instructions below).
 *   - [start] launches a single collector on a dedicated CoroutineScope.
 *   - The collector runs for the life of the app process. Cancellation is
 *     a debug-only convenience via [stop].
 *
 * Threading:
 *   Runs on Dispatchers.Default. Reducer is pure and fast (no I/O); we don't
 *   need Main. StateFlow emissions from WorldStateStore propagate to
 *   subscribers on whatever dispatchers they collected on.
 *
 * Observability:
 *   Logs every state-changing reduction at INFO level with the event type
 *   and the salient changed fields. Heartbeats and stale events go to DEBUG
 *   to keep logcat readable.
 */
@Singleton
class ReducerEngine @Inject constructor(
    private val bus: AccessibilityEventBus,
    private val store: WorldStateStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorJob: Job? = null

    /**
     * Start the event collector. Idempotent — calling twice is a no-op.
     *
     * Called from the Application class onCreate after Hilt is initialized.
     * If not called, no events ever update WorldState — the bus and store
     * exist but are not connected.
     */
    fun start() {
        if (collectorJob?.isActive == true) {
            Log.w(TAG, "REDUCER_START_IGNORED engine already running")
            return
        }
        Log.i(TAG, "REDUCER_START")
        collectorJob = bus.events
            .onEach { event -> handleEvent(event) }
            .launchIn(scope)
    }

    /**
     * Stop the collector. Used for tests and to allow clean shutdown.
     * Production app does not call this.
     */
    fun stop() {
        Log.i(TAG, "REDUCER_STOP")
        collectorJob?.cancel()
        collectorJob = null
    }

    /**
     * True if the engine is currently consuming events.
     */
    fun isRunning(): Boolean = collectorJob?.isActive == true

    private fun handleEvent(event: AgentEvent) {
        // Capture old state for change detection logging.
        val before = store.current()
        val after = store.reduce { current -> AccessibilityReducer.reduce(current, event) }

        // Skip logging if nothing relevant changed beyond sequence + counters.
        val materialChange =
            before.foregroundPackage != after.foregroundPackage ||
                    before.foregroundWindowClass != after.foregroundWindowClass ||
                    before.imeVisible != after.imeVisible ||
                    before.inputConnectionReady != after.inputConnectionReady ||
                    before.focusedEditableIdentity != after.focusedEditableIdentity ||
                    before.inRecovery != after.inRecovery ||
                    before.activeWorkflowId != after.activeWorkflowId

        if (materialChange) {
            Log.i(TAG, "REDUCED seq=${event.sequence} type=${event::class.simpleName} " +
                    "pkg=${after.foregroundPackage} ime=${after.imeVisible} " +
                    "ready=${after.inputConnectionReady} " +
                    "focused=${after.focusedEditableIdentity?.let { it::class.simpleName }} " +
                    "recovery=${after.inRecovery}")
        }
    }

    companion object {
        private const val TAG = "ReducerEngine"
    }
}