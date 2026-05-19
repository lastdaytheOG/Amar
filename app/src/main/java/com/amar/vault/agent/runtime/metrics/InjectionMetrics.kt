package com.amar.vault.agent.runtime.metrics

import android.util.Log
import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.events.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks per-(package, strategy) injection outcomes for diagnostics and
 * future adaptive cascading (Step 14).
 *
 * # Why event-sourced
 * We subscribe to the bus for InjectionStarted / InjectionSucceeded /
 * InjectionFailed events. No code anywhere needs to call us directly —
 * the existing engine already publishes these events for its own logging,
 * so metrics is pure observer.
 *
 * This keeps metrics zero-cost when not running: removing the start() call
 * unsubscribes us from the bus and the engine continues unchanged.
 *
 * # What we count
 *   - Per-strategy attempts per package: how often each strategy was tried
 *   - Per-strategy successes per package: how often it verified
 *   - Per-package totals: aggregate workflow success
 *   - Recent durations: median + p95 latencies (rolling 50-sample buffer)
 *
 * # What we do NOT count
 *   - Payload contents (PII concern)
 *   - User identity / device info (no telemetry off-device)
 *   - Failure reasons granularity (the engine logs those; metrics tracks
 *     the bucket: succeeded vs failed)
 *
 * # Thread safety
 * All updates happen on the bus collector's dispatcher (Default). The
 * snapshot() method is thread-safe via ConcurrentHashMap and AtomicLong;
 * counters may be momentarily inconsistent between fields but never
 * corrupted.
 *
 * # Future use
 * Step 14 (OEM Learning) reads from this metrics store to reorder the
 * strategy cascade per-device. For example, if CLIPBOARD_PASTE outperforms
 * ACTION_SET_TEXT on Samsung devices, the adaptive cascade promotes
 * paste-first on those devices. We're not implementing the reordering
 * here — only the data collection.
 */
@Singleton
class InjectionMetrics @Inject constructor(
    private val bus: AccessibilityEventBus
) {

    /** Per-(package + strategy) counters. Keyed "$pkg:$strategy". */
    private data class Counter(
        val attempts: AtomicLong = AtomicLong(0),
        val successes: AtomicLong = AtomicLong(0),
        val totalDurationMs: AtomicLong = AtomicLong(0)
    )

    private val byKey = ConcurrentHashMap<String, Counter>()
    private val recentDurationsMs = ArrayDeque<Long>()
    private val durationsLock = Any()
    private val maxRecent = 50

    /** Per-workflow tracking: start timestamps keyed by targetIdentity only. */
    private val openWorkflows = ConcurrentHashMap<String, Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorJob: Job? = null

    /**
     * Start collecting metrics. Idempotent.
     * AmarApplication should call this after reducerEngine.start().
     */
    fun start() {
        if (collectorJob?.isActive == true) {
            Log.w(TAG, "METRICS_START_IGNORED already running")
            return
        }
        Log.i(TAG, "METRICS_START subscribing to all events (raw filter)")

        // Subscribe to the raw events flow and filter by type ourselves.
        // The reified subscribe<T>() path was not delivering Executor.*
        // events; using the underlying SharedFlow directly with explicit
        // when() dispatch makes the type check obvious to the JVM and
        // confirms whether the issue is delivery or filtering.
        collectorJob = bus.events
            .onEach { ev ->
                when (ev) {
                    is AgentEvent.Executor.InjectionStarted -> onStarted(ev)
                    is AgentEvent.Executor.InjectionSucceeded -> onSucceeded(ev)
                    is AgentEvent.Executor.InjectionFailed -> onFailed(ev)
                    else -> { /* ignore non-injection events */ }
                }
            }
            .launchIn(scope)
    }

    fun stop() {
        Log.i(TAG, "METRICS_STOP")
        collectorJob?.cancel()
        collectorJob = null
    }

    private fun onStarted(ev: AgentEvent.Executor.InjectionStarted) {
        // Key by targetIdentity only — the engine publishes Started with
        // strategy="cascade" (the whole workflow) but publishes Succeeded
        // with the specific winning strategy name. They must share a key
        // to look up the start timestamp on completion.
        val key = ev.targetIdentity
        openWorkflows[key] = System.currentTimeMillis()
    }

    private fun onSucceeded(ev: AgentEvent.Executor.InjectionSucceeded) {
        val key = counterKey(targetIdentity = ev.targetIdentity, strategy = ev.strategy)
        val c = byKey.computeIfAbsent(key) { Counter() }
        c.attempts.incrementAndGet()
        c.successes.incrementAndGet()
        recordWorkflowComplete(ev.targetIdentity, ev.strategy, success = true)
    }

    private fun onFailed(ev: AgentEvent.Executor.InjectionFailed) {
        val key = counterKey(targetIdentity = ev.targetIdentity, strategy = ev.strategy)
        val c = byKey.computeIfAbsent(key) { Counter() }
        c.attempts.incrementAndGet()
        recordWorkflowComplete(ev.targetIdentity, ev.strategy, success = false)
    }

    private fun recordWorkflowComplete(targetIdentity: String, strategy: String, success: Boolean) {
        val started = openWorkflows.remove(targetIdentity) ?: run {
            // No matching start — log anyway with dur=0 so we don't lose
            // the success/fail count.
            Log.w(TAG, "WORKFLOW_DONE_NO_START identity=$targetIdentity strategy=$strategy " +
                    "success=$success")
            return
        }
        val dur = System.currentTimeMillis() - started
        synchronized(durationsLock) {
            recentDurationsMs.addLast(dur)
            while (recentDurationsMs.size > maxRecent) recentDurationsMs.removeFirst()
        }
        val c = byKey[counterKey(targetIdentity, strategy)] ?: return
        c.totalDurationMs.addAndGet(dur)
        Log.i(TAG, "WORKFLOW_DONE identity=$targetIdentity strategy=$strategy " +
                "success=$success dur=${dur}ms")
    }

    // -------------------------------------------------------------------------
    // Snapshot API for diagnostics / future adaptive cascade.
    // -------------------------------------------------------------------------

    data class StrategyStats(
        val key: String,            // "$identity:$strategy"
        val attempts: Long,
        val successes: Long,
        val successRate: Double,    // 0.0..1.0
        val avgDurationMs: Long
    )

    data class Snapshot(
        val perStrategy: List<StrategyStats>,
        val recentDurationsMs: List<Long>,
        val medianDurationMs: Long,
        val p95DurationMs: Long
    )

    /**
     * Snapshot current counters. Thread-safe; the snapshot is a point-in-time
     * copy and won't reflect concurrent updates after the call returns.
     */
    fun snapshot(): Snapshot {
        val stats = byKey.entries.map { (key, c) ->
            val a = c.attempts.get()
            val s = c.successes.get()
            val tot = c.totalDurationMs.get()
            StrategyStats(
                key = key,
                attempts = a,
                successes = s,
                successRate = if (a > 0) s.toDouble() / a else 0.0,
                avgDurationMs = if (a > 0) tot / a else 0L
            )
        }.sortedByDescending { it.attempts }

        val durations: List<Long> = synchronized(durationsLock) { recentDurationsMs.toList() }
        val sorted = durations.sorted()
        val median = if (sorted.isEmpty()) 0L else sorted[sorted.size / 2]
        val p95 = if (sorted.isEmpty()) 0L else sorted[((sorted.size - 1) * 0.95).toInt()]

        return Snapshot(
            perStrategy = stats,
            recentDurationsMs = durations,
            medianDurationMs = median,
            p95DurationMs = p95
        )
    }

    /**
     * Log the current snapshot at INFO level. Useful for diagnostic dumps
     * via `adb shell am broadcast` or a debug-menu trigger.
     */
    fun logSnapshot() {
        val s = snapshot()
        Log.i(TAG, "SNAPSHOT median=${s.medianDurationMs}ms p95=${s.p95DurationMs}ms " +
                "samples=${s.recentDurationsMs.size}")
        s.perStrategy.forEach { st ->
            Log.i(TAG, "  ${st.key} attempts=${st.attempts} succ=${st.successes} " +
                    "rate=%.2f avg=${st.avgDurationMs}ms".format(st.successRate))
        }
    }

    /**
     * Recommendation API for future Step 14: best strategy for a given
     * package + identity, based on observed success rate.
     */
    fun bestStrategyFor(targetIdentity: String, minAttempts: Int = 3): String? {
        return byKey.entries
            .filter { it.key.startsWith("$targetIdentity:") && it.value.attempts.get() >= minAttempts }
            .maxByOrNull { it.value.successes.get().toDouble() / it.value.attempts.get() }
            ?.let { it.key.substringAfter(":") }
    }

    private fun counterKey(targetIdentity: String, strategy: String) = "$targetIdentity:$strategy"
    private fun workflowKey(targetIdentity: String, strategy: String) = "$targetIdentity:$strategy"

    companion object {
        private const val TAG = "InjectionMetrics"
    }
}