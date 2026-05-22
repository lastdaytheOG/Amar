package com.amar.vault.agent.replay

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures workflow frames into a JSON replay file. Off by default.
 *
 * Usage:
 *   recorder.startWorkflow(goal, packageId)
 *   recorder.recordFrame(frame)
 *   ...
 *   recorder.finishWorkflow(outcome)
 *
 * Frames are buffered in memory during the workflow and flushed to disk
 * at workflow end. No I/O on the hot path.
 *
 * One workflow at a time. If startWorkflow is called while another is
 * active, the previous is dropped (logged as a warning).
 *
 * Option B (step-level): caller decides which snapshots to record (step
 * boundaries only). ReplayRecorder is dumb storage — it doesn't filter.
 */
@Singleton
class ReplayRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    @Volatile
    var enabled: Boolean = true

    private val activeWorkflow = ConcurrentLinkedQueue<ReplayFrame>()
    @Volatile private var workflowId: String? = null
    @Volatile private var goal: String? = null
    @Volatile private var packageId: String? = null
    @Volatile private var workflowTags: List<String> = emptyList()
    @Volatile private var startedAtMs: Long = 0L

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun startWorkflow(
        goalStr: String,
        pkgId: String,
        tags: List<String> = emptyList()
    ): String? {
        if (!enabled) return null
        if (workflowId != null) {
            Log.w(TAG, "startWorkflow called while another workflow active; dropping previous (id=$workflowId)")
            activeWorkflow.clear()
        }
        val id = UUID.randomUUID().toString()
        workflowId = id
        goal = goalStr
        packageId = pkgId
        workflowTags = tags
        startedAtMs = System.currentTimeMillis()
        Log.i(TAG, "REC startWorkflow id=$id goal=$goalStr pkg=$pkgId tags=$tags")
        return id
    }

    fun recordFrame(frame: ReplayFrame) {
        if (!enabled) return
        if (workflowId == null) return
        activeWorkflow.offer(frame)
    }

    fun finishWorkflow(outcome: ReplayOutcome) {
        if (!enabled) return
        val id = workflowId ?: return
        val file = ReplayFile(
            workflowId = id,
            goal = goal ?: "",
            packageId = packageId ?: "",
            startedAtMs = startedAtMs,
            endedAtMs = System.currentTimeMillis(),
            outcome = outcome,
            deviceContext = captureDeviceContext(),
            workflowTags = workflowTags,
            frames = activeWorkflow.toList()
        )
        flushToFile(file)
        activeWorkflow.clear()
        workflowId = null
        goal = null
        packageId = null
        workflowTags = emptyList()
    }

    private fun captureDeviceContext(): DeviceContext {
        val dm = context.resources.displayMetrics
        return DeviceContext(
            manufacturer = android.os.Build.MANUFACTURER ?: "unknown",
            model = android.os.Build.MODEL ?: "unknown",
            androidSdk = android.os.Build.VERSION.SDK_INT,
            screenDensity = dm.density,
            windowSize = "${dm.widthPixels}x${dm.heightPixels}"
        )
    }

    private fun flushToFile(file: ReplayFile) {
        try {
            val dir = File(context.getExternalFilesDir(null), "replays")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, "${file.workflowId}.json")
            outFile.writeText(json.encodeToString(file))
            Log.i(TAG, "REC flushed workflow=${file.workflowId} path=${outFile.absolutePath} frames=${file.frames.size}")
        } catch (t: Throwable) {
            Log.e(TAG, "REC flush failed: ${t.message}")
        }
    }

    fun timeSinceStartMs(): Long =
        if (startedAtMs == 0L) 0L else System.currentTimeMillis() - startedAtMs

    companion object {
        private const val TAG = "ReplayRecorder"
    }
}