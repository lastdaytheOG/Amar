package com.amar.vault.agent.runtime.ime

import android.util.Log
import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.events.AgentEvent
import com.amar.vault.agent.runtime.state.WorldStateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level API for waiting on IME readiness. Wraps WorldStateStore +
 * InputConnectionMonitor in a clean suspend-friendly interface for
 * executors (Step 8) and tests.
 *
 * Replaces polling-based waits like UiReadinessWaiter.waitForMotionToSettle
 * with event-driven suspension. Usage:
 *
 *     val ready = imeCoordinator.awaitReady(timeoutMs = 3_000)
 *     if (!ready) {
 *         // Fall back to legacy path or recover
 *         return
 *     }
 *     // Safe to inject text here
 *     typeTextExecutor.execute(...)
 *
 * Today (Step 6), nothing calls awaitReady yet — it's wired in Step 8.
 * What IS active is the underlying InputConnectionMonitor publishing
 * InputReadiness events to the bus so we can observe the signal in logs.
 */
@Singleton
class ImeCoordinator @Inject constructor(
    private val bus: AccessibilityEventBus,
    private val store: WorldStateStore,
    private val monitor: InputConnectionMonitor,
    private val selectionTracker: SelectionTracker
) {

    /**
     * Start the underlying monitor. Idempotent.
     * AmarApplication calls this in onCreate after reducerEngine.start().
     */
    fun start() {
        monitor.start()
        Log.i(TAG, "IME_COORDINATOR_START")
    }

    fun stop() {
        monitor.stop()
        Log.i(TAG, "IME_COORDINATOR_STOP")
    }

    /**
     * Suspend until the WorldState shows inputConnectionReady == true.
     * Returns true if readiness was reached within [timeoutMs], false on timeout.
     *
     * This is the function executors should call before any text injection.
     * No polling — pure StateFlow subscription.
     */
    suspend fun awaitReady(timeoutMs: Long = 3_000L): Boolean {
        // Fast path: already ready.
        if (store.current().inputConnectionReady) return true

        val result = withTimeoutOrNull(timeoutMs) {
            store.state.first { it.inputConnectionReady }
        }
        val ok = result != null
        Log.i(TAG, "AWAIT_READY result=$ok timeout=${timeoutMs}ms")
        return ok
    }

    /**
     * Snapshot current readiness without suspending. For diagnostics.
     */
    fun isReadyNow(): Boolean = store.current().inputConnectionReady

    /**
     * Internal callback for PerceptionService to forward TextSelectionChanged
     * events. Updates SelectionTracker and nudges a readiness recompute.
     *
     * Called from PerceptionService.onAccessibilityEvent — see patch below.
     */
    fun onTextSelectionChanged(packageId: String?, resourceId: String?, atMillis: Long) {
        selectionTracker.record(packageId, resourceId, atMillis)
        monitor.onSelectionRecorded(packageId)
    }

    companion object {
        private const val TAG = "ImeCoordinator"
    }
}