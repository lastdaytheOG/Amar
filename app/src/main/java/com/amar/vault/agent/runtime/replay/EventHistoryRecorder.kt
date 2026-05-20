package com.amar.vault.agent.runtime.replay

import android.util.Log
import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.events.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records the last [CAPACITY] events that flowed through [AccessibilityEventBus].
 *
 * # Why ring buffer (not unbounded)
 * The bus emits 50-200 events/second during heavy UI interaction. Unbounded
 * recording would leak memory in minutes. A fixed-size ring buffer keeps
 * the last few seconds of activity for forensic analysis — enough to
 * reconstruct what happened around a bug.
 *
 * # What we record
 * Every [AgentEvent] subtype with full payload. The events are immutable
 * data classes, so no defensive copying needed. We just store the reference.
 *
 * # What we DON'T record
 *   - Tool call payloads, screen pixels, or anything outside the bus.
 *     If it didn't get published, we can't replay it.
 *   - Wall-clock timestamps for replay timing. Events carry their original
 *     [atMillis] field; replay uses that for relative timing.
 *
 * # Use cases
 *   - Bug repro: "WhatsApp injection failed at 18:42. What did the bus
 *     see in the 5 seconds before?" → dump(window=5_000ms, beforeMs=...)
 *   - Reducer testing: capture real events, replay them through a new
 *     reducer implementation to verify state transitions.
 *   - Adapter validation: replay focus events against a new adapter to
 *     verify classification doesn't regress.
 *
 * # Concurrency
 * Single writer (the bus collector), multiple readers (dump/replay). The
 * ring is guarded by [lock]; reads snapshot to a list under the lock.
 *
 * # Memory cost
 * CAPACITY * ~200 bytes per event = ~400KB at default 2000-event capacity.
 * Negligible vs. the 770MB APK.
 */
@Singleton
class EventHistoryRecorder @Inject constructor(
    private val bus: AccessibilityEventBus
) {

    private val ring = ArrayDeque<AgentEvent>(CAPACITY)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorJob: Job? = null

    fun start() {
        if (collectorJob?.isActive == true) {
            Log.w(TAG, "RECORDER_START_IGNORED already running")
            return
        }
        Log.i(TAG, "RECORDER_START capacity=$CAPACITY")
        collectorJob = bus.events
            .onEach { ev ->
                synchronized(lock) {
                    ring.addLast(ev)
                    while (ring.size > CAPACITY) ring.removeFirst()
                }
            }
            .launchIn(scope)
    }

    fun stop() {
        Log.i(TAG, "RECORDER_STOP")
        collectorJob?.cancel()
        collectorJob = null
    }

    /**
     * Snapshot of all currently-buffered events. Returns an immutable list;
     * subsequent writes to the ring don't affect this snapshot.
     */
    fun snapshot(): List<AgentEvent> = synchronized(lock) { ring.toList() }

    /**
     * Snapshot filtered to events between [fromMs] and [toMs] (inclusive).
     * Both bounds use the event's [AgentEvent.atMillis] field.
     */
    fun snapshotRange(fromMs: Long, toMs: Long): List<AgentEvent> =
        snapshot().filter { it.atMillis in fromMs..toMs }

    /**
     * Snapshot of the last [count] events, regardless of timing.
     */
    fun snapshotLastN(count: Int): List<AgentEvent> {
        val all = snapshot()
        return if (count >= all.size) all else all.subList(all.size - count, all.size)
    }

    /**
     * Snapshot of events from the past [windowMs] milliseconds.
     */
    fun snapshotLastWindow(windowMs: Long): List<AgentEvent> {
        val cutoff = System.currentTimeMillis() - windowMs
        return snapshot().filter { it.atMillis >= cutoff }
    }

    /**
     * Log the buffer summary at INFO level. Useful for diagnostic dumps via
     * adb broadcast or a debug menu trigger.
     */
    fun logSummary() {
        val s = snapshot()
        if (s.isEmpty()) {
            Log.i(TAG, "SUMMARY empty buffer")
            return
        }
        val first = s.first().atMillis
        val last = s.last().atMillis
        val byType = s.groupingBy { it::class.simpleName ?: "?" }.eachCount()
        Log.i(TAG, "SUMMARY size=${s.size} spanMs=${last - first} types=$byType")
    }

    /**
     * Replay [events] back through the bus.
     *
     * IMPORTANT: this re-publishes events on the LIVE bus. Subscribers
     * (reducer, semantic bridge, etc.) will react as if these events
     * just happened. Use with extreme care — replay during a live workflow
     * will corrupt WorldState.
     *
     * Intended use: dev-time testing only, ideally on a fresh process
     * with no other workflows in flight.
     *
     * Returns the number of events successfully republished.
     */
    suspend fun replay(events: List<AgentEvent>): Int {
        Log.i(TAG, "REPLAY_START count=${events.size}")
        var published = 0
        for (ev in events) {
            bus.publish(ev)
            published++
        }
        Log.i(TAG, "REPLAY_DONE published=$published")
        return published
    }

    companion object {
        private const val TAG = "EventHistory"
        private const val CAPACITY = 2000
    }
}