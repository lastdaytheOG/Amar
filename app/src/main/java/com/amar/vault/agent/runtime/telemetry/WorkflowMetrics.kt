package com.amar.vault.agent.telemetry

import com.amar.vault.agent.replay.ReplayFile
import com.amar.vault.agent.replay.ReplayFrame

/**
 * One row of telemetry per workflow. Derived from a [ReplayFile] post-hoc
 * so we can keep Phase 1 (recording) and Phase 2 (analysis) decoupled.
 *
 * Columns chosen to surface the most common analysis questions:
 *  - which apps/workflows fail most, with what failure class?
 *  - which steps are slow per app?
 *  - which step-3 click candidates win in practice?
 *  - which send strategy works (IME_ENTER / region-scan / static cascade)?
 *  - does env verification confidence correlate with success?
 *
 * Add columns conservatively. CSV is forward-compatible only if columns
 * are appended (never reordered or removed).
 */
data class WorkflowMetrics(
    val workflowId: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val goal: String,
    val packageId: String,
    val outcome: String,                // "Succeeded" / "Failed"
    val failureClass: String?,          // null on success
    val deviceModel: String,
    val androidSdk: Int,
    val tags: String,                   // comma-separated workflow tags
    val step1Ms: Long?,
    val step2Ms: Long?,
    val step25Ms: Long?,
    val step27Ms: Long?,
    val step3Ms: Long?,
    val step5Ms: Long?,
    val step3Winner: String?,           // detail of step_3_winner marker, e.g. "Ask Gemini/TEXT"
    val step5Method: String?,           // detail of step_5_SUCCESS marker
    val alreadyEditableBypass: Boolean?,
    val envTarget: String?,
    val envConfidence: Double?,
    val envState: String?,
    val envRecovered: Boolean?,
    val elementsAtStep2: Int?,
    val elementsAtStep27: Int?,
    val elementsAtStep3: Int?
) {
    companion object {
        const val CSV_HEADER = "workflow_id,started_at_ms,duration_ms,goal,package_id," +
                "outcome,failure_class,device_model,android_sdk,tags," +
                "step_1_ms,step_2_ms,step_2_5_ms,step_2_7_ms,step_3_ms,step_5_ms," +
                "step_3_winner,step_5_method,alreadyEditable_bypass," +
                "env_target,env_confidence,env_state,env_recovered," +
                "elements_step_2,elements_step_2_7,elements_step_3"
    }

    fun toCsvRow(): String = listOf(
        workflowId, startedAtMs, durationMs, csvEscape(goal), packageId,
        outcome, failureClass ?: "", csvEscape(deviceModel), androidSdk, csvEscape(tags),
        step1Ms ?: "", step2Ms ?: "", step25Ms ?: "", step27Ms ?: "",
        step3Ms ?: "", step5Ms ?: "",
        csvEscape(step3Winner ?: ""), csvEscape(step5Method ?: ""),
        alreadyEditableBypass ?: "",
        envTarget ?: "", envConfidence ?: "", envState ?: "", envRecovered ?: "",
        elementsAtStep2 ?: "", elementsAtStep27 ?: "", elementsAtStep3 ?: ""
    ).joinToString(",")

    private fun csvEscape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"${s.replace("\"", "\"\"")}\""
        } else s
}

/**
 * Derives a [WorkflowMetrics] from a [ReplayFile] by walking the frames.
 * Robust to missing frames (returns null fields for absent data).
 */
object MetricsExtractor {

    fun from(replay: ReplayFile): WorkflowMetrics {
        val steps = mutableMapOf<String, Long>()      // step name -> ts_ms
        var step3Winner: String? = null
        var step5Method: String? = null
        var alreadyEditable: Boolean? = null
        var envTarget: String? = null
        var envConfidence: Double? = null
        var envState: String? = null
        var envRecovered: Boolean? = null
        var elementsAtStep2: Int? = null
        var elementsAtStep27: Int? = null
        var elementsAtStep3: Int? = null

        // Walk in order. Snapshots get associated with the most recent step marker.
        var lastStep: String? = null
        for (frame in replay.frames) {
            when (frame) {
                is ReplayFrame.StepMarker -> {
                    steps[frame.step] = frame.tsMs
                    lastStep = frame.step
                    when (frame.step) {
                        "step_3_winner" -> step3Winner = frame.detail
                        "step_5_SUCCESS" -> step5Method = frame.detail
                        "step_2.7_polled" -> {
                            alreadyEditable = frame.detail?.contains("alreadyEditable=true")
                        }
                    }
                }
                is ReplayFrame.Snapshot -> {
                    when (lastStep) {
                        "step_2_settled" -> elementsAtStep2 = frame.elementCount
                        "step_2.7_polled" -> elementsAtStep27 = frame.elementCount
                        "step_3_winner" -> elementsAtStep3 = frame.elementCount
                    }
                }
                is ReplayFrame.EnvironmentVerification -> {
                    envTarget = frame.targetEnv
                    envConfidence = frame.targetConfidence
                    envState = frame.state
                    envRecovered = (frame.state == "STABLE")
                }
                else -> { /* ignore */ }
            }
        }

        // Compute step durations as time-between-markers. Null if either side missing.
        val step1Ms = stepDuration(steps, "step_1_open", "step_2_settled")
        val step2Ms = stepDuration(steps, "step_2_settled", "step_2.7_polled")
        val step25Ms: Long? = null      // not currently emitted as marker
        val step27Ms = stepDuration(steps, "step_2.7_polled", "step_3_winner")
        val step3Ms = stepDuration(steps, "step_3_winner", "step_5_SUCCESS")
        val step5Ms = stepDuration(steps, "step_5_SUCCESS", null) // duration until end

        return WorkflowMetrics(
            workflowId = replay.workflowId,
            startedAtMs = replay.startedAtMs,
            durationMs = replay.outcome?.durationMs ?: 0L,
            goal = replay.goal,
            packageId = replay.packageId,
            outcome = replay.outcome?.kind ?: "Unknown",
            failureClass = replay.outcome?.failureClass,
            deviceModel = "${replay.deviceContext.manufacturer} ${replay.deviceContext.model}",
            androidSdk = replay.deviceContext.androidSdk,
            tags = replay.workflowTags.joinToString(";"),
            step1Ms = step1Ms,
            step2Ms = step2Ms,
            step25Ms = step25Ms,
            step27Ms = step27Ms,
            step3Ms = step3Ms,
            step5Ms = step5Ms,
            step3Winner = step3Winner,
            step5Method = step5Method,
            alreadyEditableBypass = alreadyEditable,
            envTarget = envTarget,
            envConfidence = envConfidence,
            envState = envState,
            envRecovered = envRecovered,
            elementsAtStep2 = elementsAtStep2,
            elementsAtStep27 = elementsAtStep27,
            elementsAtStep3 = elementsAtStep3
        )
    }

    private fun stepDuration(steps: Map<String, Long>, from: String, to: String?): Long? {
        val a = steps[from] ?: return null
        if (to == null) return null  // end-of-workflow durations need outcome.endMs context
        val b = steps[to] ?: return null
        return b - a
    }
}