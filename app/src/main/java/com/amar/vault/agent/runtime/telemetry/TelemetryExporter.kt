package com.amar.vault.agent.runtime.telemetry

import android.content.Context
import android.util.Log
import com.amar.vault.agent.runtime.metrics.InjectionMetrics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports [InjectionMetrics] snapshots to disk as JSON files for external
 * consumption.
 *
 * # Output location
 * Files are written to the app's external files directory:
 *   /sdcard/Android/data/com.amar.vault/files/telemetry/
 *
 * On-device the directory is accessible to the app without permission.
 * On-host (via ADB pull) the directory is reachable as:
 *   adb pull /sdcard/Android/data/com.amar.vault/files/telemetry/
 *
 * # File naming
 *   - "latest.json" — always the most recent export, overwritten each time
 *   - "hourly-YYYYMMDD-HH.json" — one file per hour, append-only
 *
 * Hourly bucketing means analytics scripts can pick up "anything new since
 * yesterday" without parsing timestamps. Latest.json gives quick eyeballing
 * without enumerating hourly files.
 *
 * # Schema (matches InjectionMetrics.Snapshot)
 * {
 *   "schemaVersion": 1,
 *   "exportedAtMillis": <long>,
 *   "median": <long>,
 *   "p95": <long>,
 *   "recentSamples": <long[]>,
 *   "perStrategy": [
 *     {
 *       "key": "SearchInput:ACTION_SET_TEXT",
 *       "attempts": <long>,
 *       "successes": <long>,
 *       "successRate": <double>,
 *       "avgDurationMs": <long>
 *     },
 *     ...
 *   ]
 * }
 *
 * # Why JSON instead of protobuf / msgpack
 * Plaintext JSON is debuggable from `adb shell cat`, parseable by every
 * scripting language, and small enough at this scale. The data is per-
 * device telemetry, not high-volume logs — speed isn't a concern.
 *
 * # Cadence
 * Three trigger paths:
 *   - export() callable manually (broadcast receiver uses this)
 *   - Periodic background loop every [PERIOD_MS]
 *   - On app start (from AmarApplication)
 *
 * Failures (disk full, permission denied) are logged but don't propagate.
 * Telemetry must never break the app.
 */
@Singleton
class TelemetryExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metrics: InjectionMetrics
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var periodicJob: Job? = null

    fun start() {
        if (periodicJob?.isActive == true) {
            Log.w(TAG, "TELEMETRY_START_IGNORED already running")
            return
        }
        Log.i(TAG, "TELEMETRY_START dir=${dir().absolutePath} period=${PERIOD_MS}ms")

        // Immediate first export so consumers see fresh data right away,
        // not after waiting for the first tick.
        scope.launch {
            try { export() } catch (t: Throwable) { Log.w(TAG, "Initial export failed: ${t.message}") }
        }

        periodicJob = scope.launch {
            while (true) {
                delay(PERIOD_MS)
                try { export() } catch (t: Throwable) { Log.w(TAG, "Periodic export failed: ${t.message}") }
            }
        }
    }

    fun stop() {
        Log.i(TAG, "TELEMETRY_STOP")
        periodicJob?.cancel()
        periodicJob = null
    }

    /**
     * Capture the current metrics snapshot and write to disk. Returns the
     * absolute path of the file written, or null on failure.
     */
    fun export(): String? {
        return try {
            val snapshot = metrics.snapshot()
            val json = renderJson(snapshot)
            val dir = dir()
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "EXPORT_FAILED cannot create dir ${dir.absolutePath}")
                return null
            }

            val latest = File(dir, "latest.json")
            latest.writeText(json)

            val now = java.text.SimpleDateFormat("yyyyMMdd-HH", java.util.Locale.US)
                .format(java.util.Date())
            val hourly = File(dir, "hourly-$now.json")
            hourly.writeText(json)

            Log.i(TAG, "EXPORTED path=${latest.absolutePath} " +
                    "strategies=${snapshot.perStrategy.size} samples=${snapshot.recentDurationsMs.size}")
            latest.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "EXPORT_FAILED ${t.message}")
            null
        }
    }

    /**
     * Where telemetry files land. Public so debug tooling can read it back.
     */
    fun dir(): File {
        val base = context.getExternalFilesDir(null)
            ?: File(context.filesDir, "telemetry-fallback")
        return File(base, "telemetry")
    }

    private fun renderJson(snapshot: InjectionMetrics.Snapshot): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("exportedAtMillis", System.currentTimeMillis())
        root.put("median", snapshot.medianDurationMs)
        root.put("p95", snapshot.p95DurationMs)

        val samples = JSONArray()
        snapshot.recentDurationsMs.forEach { samples.put(it) }
        root.put("recentSamples", samples)

        val perStrategy = JSONArray()
        snapshot.perStrategy.forEach { s ->
            perStrategy.put(JSONObject().apply {
                put("key", s.key)
                put("attempts", s.attempts)
                put("successes", s.successes)
                put("successRate", s.successRate)
                put("avgDurationMs", s.avgDurationMs)
            })
        }
        root.put("perStrategy", perStrategy)

        // Indented for human-readability; parsers don't care.
        return root.toString(2)
    }

    companion object {
        private const val TAG = "TelemetryExporter"
        private const val SCHEMA_VERSION = 1

        /** Export every 5 minutes during runtime. */
        private const val PERIOD_MS = 5L * 60L * 1000L
    }
}