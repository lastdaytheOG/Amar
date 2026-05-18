package com.amar.vault.agent.runtime.events

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central event bus for the agent runtime.
 *
 * Responsibilities:
 *   - Accept events from any producer (PerceptionService, Executors, Reducer).
 *   - Stamp each event with a monotonic sequence number on publish.
 *   - Deduplicate near-identical events within a short window.
 *   - Expose typed subscription via Kotlin Flow.
 *   - Optionally replay recent events for late subscribers (e.g. for telemetry).
 *
 * Threading:
 *   The bus is thread-safe. publish() can be called from any thread including
 *   AccessibilityService's binder thread. Subscribers receive events on the
 *   collector's coroutine context.
 *
 * Backpressure:
 *   Uses SharedFlow with extraBufferCapacity=128 and BufferOverflow.DROP_OLDEST.
 *   We tolerate losing diagnostic events; CRITICAL/HIGH-priority events should
 *   never be dropped in practice because subscribers drain faster than producers
 *   emit. If profiling shows drops on high-priority events, lift capacity.
 *
 * Sequence numbering:
 *   Every published event gets a monotonic Long via [seqCounter]. The sequence
 *   is set via reflection-free copy() on each known event type. Order of
 *   delivery to subscribers respects sequence numbers — Flow collection is
 *   serialized per-subscriber.
 *
 * Deduplication:
 *   Successive identical Accessibility.WindowContentChanged events within
 *   DEDUP_WINDOW_MS are coalesced — only the latest is published. This mirrors
 *   PerceptionService's existing debounce but moves the concern up the stack.
 *
 *   Other event types are NOT deduplicated — even rapid duplicate
 *   InjectionStarted events carry distinct semantic meaning (multi-strategy
 *   cascade) and must reach the reducer.
 */
@Singleton
class AccessibilityEventBus @Inject constructor() {

    private val seqCounter = AtomicLong(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    /** All events. Subscribers filter by type. */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    // Last-seen WindowContentChanged for dedup. Reset on any non-WCC event.
    @Volatile
    private var lastWcc: AgentEvent.Accessibility.WindowContentChanged? = null

    /**
     * Publish an event. Returns the assigned sequence number.
     *
     * @param priority If null, uses [EventPriority.defaultFor]. Subscribers
     *                 today don't enforce priority drains (we trust capacity);
     *                 priority becomes meaningful in Step 4 (Scheduler Isolation).
     */
    fun publish(event: AgentEvent, priority: EventPriority? = null): Long {
        // Dedup WindowContentChanged within DEDUP_WINDOW_MS.
        if (event is AgentEvent.Accessibility.WindowContentChanged) {
            val prev = lastWcc
            if (prev != null &&
                prev.packageId == event.packageId &&
                event.atMillis - prev.atMillis < DEDUP_WINDOW_MS
            ) {
                // Drop. Next non-dedup event will reset.
                return 0L
            }
            lastWcc = event
        } else {
            lastWcc = null
        }

        val seq = seqCounter.incrementAndGet()
        val stamped = withSequence(event, seq)
        val ok = _events.tryEmit(stamped)
        if (!ok) {
            Log.w(TAG, "BUS_OVERFLOW seq=$seq type=${event::class.simpleName} priority=${priority ?: "default"}")
        } else {
            // Lightweight verbose logging for high-priority events only.
            val p = priority ?: EventPriority.defaultFor(event)
            if (p == EventPriority.CRITICAL || p == EventPriority.HIGH) {
                Log.i(TAG, "BUS_EMIT seq=$seq priority=$p type=${event::class.simpleName}")
            }
        }
        return seq
    }

    /**
     * Subscribe to events of a specific subtype. Returns a Flow that the caller
     * collects in their own scope.
     *
     * Example:
     *   bus.subscribe<AgentEvent.Accessibility.WindowStateChanged>()
     *      .onEach { handle(it) }
     *      .launchIn(scope)
     */
    inline fun <reified T : AgentEvent> subscribe(): kotlinx.coroutines.flow.Flow<T> =
        events.filter { it is T }.let { @Suppress("UNCHECKED_CAST") (it as kotlinx.coroutines.flow.Flow<T>) }

    /**
     * Current sequence counter. Useful for tests and diagnostics that need to
     * assert "no new events since X".
     */
    fun currentSequence(): Long = seqCounter.get()

    /**
     * Reset internal state. Test/debug only — do not call in production.
     */
    fun resetForTest() {
        seqCounter.set(0L)
        lastWcc = null
    }

    /**
     * Copy the event with a stamped sequence number. Sealed-class aware:
     * each event type has its own copy() so we dispatch on actual type.
     *
     * This is the only "boilerplate" in the bus — adding a new AgentEvent
     * subtype requires adding a case here. The compiler enforces this via
     * the sealed hierarchy (when() warns on non-exhaustive).
     */
    private fun withSequence(event: AgentEvent, seq: Long): AgentEvent = when (event) {
        is AgentEvent.Accessibility.WindowStateChanged    -> event.copy(sequence = seq)
        is AgentEvent.Accessibility.WindowContentChanged  -> event.copy(sequence = seq)
        is AgentEvent.Accessibility.WindowsChanged        -> event.copy(sequence = seq)
        is AgentEvent.Accessibility.ViewFocused           -> event.copy(sequence = seq)
        is AgentEvent.Accessibility.TextChanged           -> event.copy(sequence = seq)
        is AgentEvent.Accessibility.TextSelectionChanged  -> event.copy(sequence = seq)

        is AgentEvent.Executor.InjectionStarted    -> event.copy(sequence = seq)
        is AgentEvent.Executor.InjectionSucceeded  -> event.copy(sequence = seq)
        is AgentEvent.Executor.InjectionFailed     -> event.copy(sequence = seq)

        is AgentEvent.Runtime.SearchActivated       -> event.copy(sequence = seq)
        is AgentEvent.Runtime.ImeVisibilityChanged  -> event.copy(sequence = seq)
        is AgentEvent.Runtime.InputReadiness        -> event.copy(sequence = seq)
        is AgentEvent.Runtime.RecoveryStarted       -> event.copy(sequence = seq)
        is AgentEvent.Runtime.RecoveryCompleted     -> event.copy(sequence = seq)
        is AgentEvent.Runtime.OverlayDetected       -> event.copy(sequence = seq)
        is AgentEvent.Runtime.WorkflowCancelled     -> event.copy(sequence = seq)

        is AgentEvent.Diagnostic.Heartbeat          -> event.copy(sequence = seq)
        is AgentEvent.Diagnostic.CriticalViolation  -> event.copy(sequence = seq)
    }

    companion object {
        private const val TAG = "AccessibilityEventBus"
        private const val DEDUP_WINDOW_MS = 50L
    }
}