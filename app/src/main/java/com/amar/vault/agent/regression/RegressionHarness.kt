package com.amar.vault.agent.regression

import com.amar.vault.agent.replay.ReplayFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Offline regression harness. Loads canonical workflow manifest, finds
 * the most-recent replay matching each, asserts the recorded outcome
 * matches expectations. Fails fast if any deviation.
 *
 * Designed as a guard before commits / before merging architectural
 * changes. Day-7-style regressions (ChatGPT broken by an unrelated
 * Gemini refactor) get caught here.
 */
@Serializable
data class CanonicalWorkflow(
    val id: String,
    val goal: String,
    val packageId: String,
    val expectedOutcome: String,
    val expectedFailureClass: String? = null,
    val maxDurationMs: Long,
    val notes: String = ""
)

@Serializable
data class CanonicalManifest(
    val version: Int,
    val workflows: List<CanonicalWorkflow>
)

object RegressionHarness {

    // Two parsers needed:
    //   - manifestJson: reads canonical_workflows.json which uses snake_case fields
    //   - replayJson:   reads replay files which use camelCase fields
    private val manifestJson = Json {
        ignoreUnknownKeys = true
        namingStrategy = kotlinx.serialization.json.JsonNamingStrategy.SnakeCase
    }
    private val replayJson = Json {
        ignoreUnknownKeys = true
    }

    data class Result(
        val workflowId: String,
        val status: Status,
        val message: String,
        val actualDurationMs: Long? = null,
        val actualOutcome: String? = null,
        val actualFailureClass: String? = null
    )

    enum class Status { PASS, FAIL, MISSING, UNEXPECTED_PASS }

    fun run(manifestFile: File, replaysDir: File): List<Result> {
        val manifest: CanonicalManifest = manifestJson.decodeFromString(
            CanonicalManifest.serializer(),
            manifestFile.readText()
        )

        // Load all replays once, sorted newest first.
        val replays: List<ReplayFile> = (replaysDir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull {
                try {
                    replayJson.decodeFromString(ReplayFile.serializer(), it.readText())
                } catch (t: Throwable) {
                    System.err.println("Skipping unreadable replay ${it.name}: ${t.message}")
                    null
                }
            }

        return manifest.workflows.map { canonical ->
            checkOne(canonical, replays)
        }
    }

    private fun checkOne(canonical: CanonicalWorkflow, replays: List<ReplayFile>): Result {
        // Match by packageId AND goal substring (goal in manifest is the user-facing
        // string; recorded goal field is the parsed form like "search:hi"). Match
        // by package as primary key, take the most recent.
        val match = replays.firstOrNull { r ->
            r.packageId == canonical.packageId
        }

        if (match == null) {
            return Result(
                workflowId = canonical.id,
                status = Status.MISSING,
                message = "No replay found for pkg=${canonical.packageId}. Run the workflow on device."
            )
        }

        val actualOutcome = match.outcome?.kind ?: "Unknown"
        val actualFailureClass = match.outcome?.failureClass
        val actualDuration = match.outcome?.durationMs ?: 0L

        val outcomeMatches = actualOutcome == canonical.expectedOutcome
        val failureClassMatches = canonical.expectedFailureClass == actualFailureClass

        // UNEXPECTED_PASS: canonical says fail, we actually passed.
        if (canonical.expectedOutcome == "Failed" && actualOutcome == "Succeeded") {
            return Result(
                workflowId = canonical.id,
                status = Status.UNEXPECTED_PASS,
                message = "Was expected to fail but PASSED. Update manifest if intentional.",
                actualDurationMs = actualDuration,
                actualOutcome = actualOutcome,
                actualFailureClass = actualFailureClass
            )
        }

        if (!outcomeMatches) {
            return Result(
                workflowId = canonical.id,
                status = Status.FAIL,
                message = "Outcome=$actualOutcome (expected ${canonical.expectedOutcome})",
                actualDurationMs = actualDuration,
                actualOutcome = actualOutcome,
                actualFailureClass = actualFailureClass
            )
        }

        if (canonical.expectedOutcome == "Failed" && !failureClassMatches) {
            return Result(
                workflowId = canonical.id,
                status = Status.FAIL,
                message = "Failure class=$actualFailureClass (expected ${canonical.expectedFailureClass})",
                actualDurationMs = actualDuration,
                actualOutcome = actualOutcome,
                actualFailureClass = actualFailureClass
            )
        }

        if (actualDuration > canonical.maxDurationMs) {
            return Result(
                workflowId = canonical.id,
                status = Status.FAIL,
                message = "Duration ${actualDuration}ms exceeds max ${canonical.maxDurationMs}ms",
                actualDurationMs = actualDuration,
                actualOutcome = actualOutcome,
                actualFailureClass = actualFailureClass
            )
        }

        return Result(
            workflowId = canonical.id,
            status = Status.PASS,
            message = "OK (${actualDuration}ms)",
            actualDurationMs = actualDuration,
            actualOutcome = actualOutcome,
            actualFailureClass = actualFailureClass
        )
    }
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: RegressionHarness <manifest.json> <replays_dir>")
        return
    }
    val manifestFile = File(args[0])
    val replaysDir = File(args[1])

    if (!manifestFile.exists()) {
        println("Manifest not found: ${args[0]}")
        return
    }
    if (!replaysDir.exists()) {
        println("Replays dir not found: ${args[1]}")
        return
    }

    val results = RegressionHarness.run(manifestFile, replaysDir)

    println("Regression Harness Results")
    println("==========================")
    var failed = 0
    var unexpectedPass = 0
    var missing = 0
    var passed = 0

    for (r in results) {
        val icon = when (r.status) {
            RegressionHarness.Status.PASS -> "PASS"
            RegressionHarness.Status.FAIL -> "FAIL"
            RegressionHarness.Status.MISSING -> "MISS"
            RegressionHarness.Status.UNEXPECTED_PASS -> "UPAS"
        }
        println("  [$icon] ${r.workflowId.padEnd(28)} ${r.message}")
        when (r.status) {
            RegressionHarness.Status.PASS -> passed++
            RegressionHarness.Status.FAIL -> failed++
            RegressionHarness.Status.MISSING -> missing++
            RegressionHarness.Status.UNEXPECTED_PASS -> unexpectedPass++
        }
    }

    println()
    println("Summary: $passed passed, $failed failed, $missing missing, $unexpectedPass unexpected-pass")

    // Exit nonzero on any deviation. CI / pre-commit hooks can chain on this.
    if (failed > 0 || unexpectedPass > 0) {
        System.exit(1)
    }
}