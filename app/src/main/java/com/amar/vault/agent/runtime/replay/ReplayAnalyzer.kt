package com.amar.vault.agent.replay

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Offline analyzer: loads a replay file and prints a human-readable
 * timeline analysis. Standalone — no Android runtime needed.
 *
 * Usage from CLI (after building):
 *   java -cp <classpath> com.amar.vault.agent.replay.ReplayAnalyzerKt path/to/replay.json
 *
 * Or wire into Gradle as a custom task (see app/build.gradle.kts).
 */
object ReplayAnalyzer {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    fun analyze(file: File): String {
        val content = file.readText()
        val replay: ReplayFile = json.decodeFromString(ReplayFile.serializer(), content)
        return formatAnalysis(replay)
    }

    private fun formatAnalysis(r: ReplayFile): String {
        val sb = StringBuilder()
        sb.appendLine("═".repeat(63))
        sb.appendLine("Workflow Analysis: ${r.workflowId}")
        sb.appendLine("═".repeat(63))
        sb.appendLine("Goal:           ${r.goal}")
        sb.appendLine("Package:        ${r.packageId}")
        sb.appendLine("Device:         ${r.deviceContext.manufacturer} ${r.deviceContext.model} " +
                "(SDK ${r.deviceContext.androidSdk}, ${r.deviceContext.windowSize})")
        sb.appendLine("Tags:           ${r.workflowTags.joinToString(", ")}")
        sb.appendLine("Duration:       ${r.outcome?.durationMs ?: "?"}ms")
        sb.appendLine("Outcome:        ${r.outcome?.kind ?: "?"}")
        sb.appendLine("Failure class:  ${r.outcome?.failureClass ?: "-"}")
        if (r.outcome?.detail != null) {
            sb.appendLine("Detail:         ${r.outcome.detail}")
        }
        sb.appendLine()
        sb.appendLine("Timeline:")

        val stepBoundaries = mutableListOf<Pair<String, Long>>()
        for (frame in r.frames) {
            when (frame) {
                is ReplayFrame.StepMarker -> {
                    sb.appendLine("  +${frame.tsMs.toString().padStart(5)}ms  " +
                            "[${frame.step}]   ${frame.detail ?: ""}")
                    stepBoundaries += Pair(frame.step, frame.tsMs)
                }
                is ReplayFrame.Snapshot -> {
                    sb.appendLine("                                ↓ Snapshot: ${frame.elementCount} elements, " +
                            "pkg=${frame.packageId}")
                    val interesting = frame.elements.filter { el ->
                        el.resourceId != null || !el.contentDesc.isNullOrEmpty() ||
                                !el.text.isNullOrEmpty()
                    }.take(8)
                    for (el in interesting) {
                        val parts = mutableListOf<String>()
                        if (el.resourceId != null) parts += "rid=${el.resourceId.substringAfterLast('/')}"
                        if (!el.text.isNullOrEmpty()) parts += "text=\"${el.text.take(30)}\""
                        if (!el.contentDesc.isNullOrEmpty()) parts += "cd=\"${el.contentDesc.take(30)}\""
                        sb.appendLine("                                  - ${el.type.padEnd(7)} ${parts.joinToString(" ")}")
                    }
                    if (frame.elements.size > 8) {
                        sb.appendLine("                                  ... +${frame.elements.size - 8} more")
                    }
                }
                is ReplayFrame.Action -> {
                    sb.appendLine("  +${frame.tsMs.toString().padStart(5)}ms  " +
                            "[action]            ${frame.kind} target=${frame.target} strategy=${frame.strategy}")
                }
                is ReplayFrame.ActionResult -> {
                    sb.appendLine("  +${frame.tsMs.toString().padStart(5)}ms  " +
                            "[action_result]     ${frame.state} (${frame.durationMs}ms)")
                }
                is ReplayFrame.EnvironmentVerification -> {
                    sb.appendLine("  +${frame.tsMs.toString().padStart(5)}ms  " +
                            "[env_verify]        target=${frame.targetEnv} " +
                            "conf=${"%.2f".format(frame.targetConfidence)} state=${frame.state}")
                }
                is ReplayFrame.WorldStateChange -> {
                    sb.appendLine("  +${frame.tsMs.toString().padStart(5)}ms  " +
                            "[world_state]       focusedKind=${frame.focusedEditableKind} " +
                            "pkg=${frame.focusedEditablePackage}")
                }
            }
        }

        sb.appendLine()
        sb.appendLine("Step durations:")
        for (i in 0 until stepBoundaries.size - 1) {
            val (a, ta) = stepBoundaries[i]
            val (b, tb) = stepBoundaries[i + 1]
            val dur = tb - ta
            val flag = if (dur > 2000) "  ⚠" else ""
            sb.appendLine("  $a → $b:  ${dur}ms$flag")
        }

        val slow = (0 until stepBoundaries.size - 1)
            .filter { stepBoundaries[it + 1].second - stepBoundaries[it].second > 2000 }
            .map { "${stepBoundaries[it].first} → ${stepBoundaries[it + 1].first}" }
        if (slow.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("⚠ Slow steps (>2s): ${slow.joinToString(", ")}")
        }

        return sb.toString()
    }
}

/**
 * CLI entry point: java ... ReplayAnalyzerKt <replay.json>
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: ReplayAnalyzer <replay.json>")
        return
    }
    val file = File(args[0])
    if (!file.exists()) {
        println("File not found: ${args[0]}")
        return
    }
    println(ReplayAnalyzer.analyze(file))
}