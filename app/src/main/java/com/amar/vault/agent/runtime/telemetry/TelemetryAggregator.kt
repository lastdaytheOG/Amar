package com.amar.vault.agent.telemetry

import com.amar.vault.agent.replay.ReplayFile
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads all .json replay files from a directory, extracts metrics, writes
 * a CSV. Standalone (no Android runtime). Designed for the Gradle task in
 * Phase 2 Step 2.3.
 */
object TelemetryAggregator {

    private val json = Json { ignoreUnknownKeys = true }

    fun aggregate(replayDir: File, outputCsv: File): AggregationResult {
        if (!replayDir.exists() || !replayDir.isDirectory) {
            return AggregationResult(0, 0, "Replay dir not found: ${replayDir.absolutePath}")
        }

        val replayFiles = replayDir.listFiles { f -> f.extension == "json" } ?: emptyArray()
        var processed = 0
        var failed = 0

        outputCsv.bufferedWriter().use { writer ->
            writer.write(WorkflowMetrics.CSV_HEADER)
            writer.newLine()

            for (file in replayFiles.sortedBy { it.lastModified() }) {
                try {
                    val replay: ReplayFile = json.decodeFromString(
                        ReplayFile.serializer(),
                        file.readText()
                    )
                    val metrics = MetricsExtractor.from(replay)
                    writer.write(metrics.toCsvRow())
                    writer.newLine()
                    processed++
                } catch (t: Throwable) {
                    failed++
                    System.err.println("Skipping ${file.name}: ${t.message}")
                }
            }
        }

        return AggregationResult(processed, failed, null)
    }

    data class AggregationResult(
        val processedCount: Int,
        val skippedCount: Int,
        val error: String?
    )
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: TelemetryAggregator <replay_dir> <output_csv>")
        return
    }
    val replayDir = File(args[0])
    val outputCsv = File(args[1])
    val result = TelemetryAggregator.aggregate(replayDir, outputCsv)
    if (result.error != null) {
        println("Error: ${result.error}")
    } else {
        println("Aggregated ${result.processedCount} workflows " +
                "(${result.skippedCount} skipped) -> ${outputCsv.absolutePath}")
    }
}