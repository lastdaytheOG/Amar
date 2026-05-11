package com.amar.vault.agent.brain

import android.util.Log
import com.amar.vault.agent.brain.download.DownloadProgress
import com.amar.vault.agent.brain.download.ModelDownloader
import com.amar.vault.agent.brain.engine.Backend
import com.amar.vault.agent.brain.engine.InferenceEngine
import com.amar.vault.agent.brain.engine.LoadResult
import com.amar.vault.agent.dsl.ActionEnvelope
import com.amar.vault.agent.dsl.ActionValidator
import com.amar.vault.agent.dsl.ValidationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Primary Brain — production implementation orchestrating:
 *
 *   download → load → warm → plan
 *
 * Plan orchestration:
 *   1. Build prompt via PromptTemplates.buildPlanPrompt
 *   2. Stream tokens from inference engine
 *   3. Accumulate until EOS / max tokens / stop string
 *   4. Extract JSON (strip any prose the model leaked)
 *   5. Validate via ActionValidator
 *   6. On validation failure: correction round (up to MAX_CORRECTION_ROUNDS)
 *   7. Emit Completed or Failed
 *
 * TTFT tracking:
 *   - ttftMs = time from generate() start to first Delta emission.
 *   - totalDurationMs = time from plan() start to Completed/Failed.
 *   - Tracked in-Brain so we can surface metrics without cross-layer plumbing.
 *
 * Thread safety:
 *   - warmup / shutdown serialized via [lifecycleMutex].
 *   - plan() concurrency is handled by the underlying engine's mutex.
 *     Callers should not issue overlapping plan() calls in v1.
 */
class LiteRtBrain(
    private val modelSpec: ModelSpec,
    private val engine: InferenceEngine,
    private val downloader: ModelDownloader,
    private val preferredBackend: Backend = Backend.AUTO,
    /** Override for tests — real scope comes from Hilt in production. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : PrimaryBrain {

    private val _state = MutableStateFlow<BrainState>(BrainState.Uninitialized)
    override val state: StateFlow<BrainState> = _state.asStateFlow()

    private val lifecycleMutex = Mutex()
    private var warmupJob: Job? = null

    override val isReady: Boolean
        get() = _state.value is BrainState.Ready

    // -------------------------------------------------------------------------
    // Warmup: download → load → prime KV cache
    // -------------------------------------------------------------------------

    override suspend fun warmup() {
        lifecycleMutex.withLock {
            when (_state.value) {
                is BrainState.Ready -> return   // already warm
                is BrainState.Downloading,
                is BrainState.Loading,
                is BrainState.Warming -> return  // in progress
                else -> { /* proceed */ }
            }

            // Step 1: ensure model file is on disk and verified.
            if (!downloader.isDownloaded(modelSpec)) {
                val ok = runDownload()
                if (!ok) return   // state already set to Failed by runDownload
            }

            // Step 2: load into engine.
            _state.value = BrainState.Loading
            val modelFile = downloader.localFile(modelSpec)
            when (val result = engine.load(modelFile, preferredBackend)) {
                is LoadResult.Success -> Log.i(TAG, "Engine loaded: ${result.info}")
                is LoadResult.Failed  -> {
                    _state.value = BrainState.Failed(
                        BrainFailure.ModelLoadFailed(result.reason)
                    )
                    return
                }
            }

            // Step 3: prime the KV cache with a throwaway generation.
            _state.value = BrainState.Warming
            runPrimingGeneration()

            _state.value = BrainState.Ready
        }
    }

    /**
     * Stream the model download. Maps DownloadProgress events to BrainState.
     * Returns true on success, false on terminal failure (state already set).
     */
    private suspend fun runDownload(): Boolean {
        var success = false
        downloader.download(modelSpec).collect { progress ->
            when (progress) {
                is DownloadProgress.Starting -> {
                    _state.value = BrainState.Downloading(
                        progress = if (progress.totalBytes > 0)
                            progress.resumingFrom.toFloat() / progress.totalBytes
                        else 0f,
                        bytesReceived = progress.resumingFrom,
                        bytesTotal = progress.totalBytes
                    )
                }
                is DownloadProgress.InProgress -> {
                    _state.value = BrainState.Downloading(
                        progress = progress.progress,
                        bytesReceived = progress.bytesReceived,
                        bytesTotal = progress.totalBytes
                    )
                }
                is DownloadProgress.ResumeVerifying,
                is DownloadProgress.Verifying -> {
                    // Minor states; keep UI on "downloading" to avoid flashing.
                }
                is DownloadProgress.Completed -> {
                    success = true
                    Log.i(TAG, "Model downloaded: ${progress.file.name} (${progress.sizeBytes} bytes)")
                }
                is DownloadProgress.Failed -> {
                    _state.value = BrainState.Failed(
                        BrainFailure.ModelDownloadFailed(progress.detail)
                    )
                }
            }
        }
        return success
    }

    /**
     * Run a short throwaway generation to prime the KV cache.
     * Most inference engines lazy-allocate context tensors — the FIRST generate()
     * pays a one-time 200-800ms penalty we'd rather not inflict on the user's
     * first real prompt.
     */
    private suspend fun runPrimingGeneration() {
        try {
            engine.generate(
                prompt = "hi",
                maxTokens = 4
            ).collect { /* discard */ }
        } catch (t: Throwable) {
            // Priming failure is non-fatal — log and continue. First real
            // plan() call will pay the warmup cost instead.
            Log.w(TAG, "priming generation threw (non-fatal): ${t.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Plan: the actual user-facing inference
    // -------------------------------------------------------------------------

    override fun plan(request: String, context: PlanContext): Flow<PlanUpdate> = channelFlow {
        if (!isReady) {
            trySend(PlanUpdate.Failed(
                rawOutput = "",
                reason = PlanFailure.NotReady,
                totalDurationMs = 0
            ))
            return@channelFlow
        }

        val planStartTime = System.currentTimeMillis()

        // Round 1: initial prompt.
        val initialPrompt = PromptTemplates.buildPlanPrompt(request, context)
        val round1 = runOneRound(
            prompt = initialPrompt,
            planStartTime = planStartTime,
            emitDeltas = true
        )

        when (round1) {
            is RoundResult.Valid -> {
                trySend(PlanUpdate.Completed(
                    envelope = round1.envelope,
                    rawOutput = round1.rawOutput,
                    tokensGenerated = round1.tokensGenerated,
                    totalDurationMs = System.currentTimeMillis() - planStartTime,
                    ttftMs = round1.ttftMs
                ))
                return@channelFlow
            }
            is RoundResult.Failed -> {
                trySend(PlanUpdate.Failed(
                    rawOutput = round1.rawOutput,
                    reason = round1.reason,
                    totalDurationMs = System.currentTimeMillis() - planStartTime
                ))
                return@channelFlow
            }
            is RoundResult.Invalid -> {
                // Fall through to correction rounds.
            }
        }

        // Correction rounds. Don't emit deltas for these — user sees
        // only the final answer. The in-flight feedback was from round 1.
        var lastRound: RoundResult = round1
        for (attempt in 1..PromptTemplates.MAX_CORRECTION_ROUNDS) {
            val invalid = lastRound as RoundResult.Invalid

            val correctionPrompt = PromptTemplates.buildCorrectionPrompt(
                originalRequest = request,
                originalContext = context,
                rejectedOutput = invalid.rawOutput,
                validationReasons = invalid.reasons.map { "${it.code}: ${it.message}" }
            )

            lastRound = runOneRound(
                prompt = correctionPrompt,
                planStartTime = planStartTime,
                emitDeltas = false
            )

            when (lastRound) {
                is RoundResult.Valid -> {
                    val valid = lastRound as RoundResult.Valid
                    trySend(PlanUpdate.Completed(
                        envelope = valid.envelope,
                        rawOutput = valid.rawOutput,
                        tokensGenerated = valid.tokensGenerated,
                        totalDurationMs = System.currentTimeMillis() - planStartTime,
                        ttftMs = valid.ttftMs
                    ))
                    return@channelFlow
                }
                is RoundResult.Failed -> {
                    val failed = lastRound as RoundResult.Failed
                    trySend(PlanUpdate.Failed(
                        rawOutput = failed.rawOutput,
                        reason = failed.reason,
                        totalDurationMs = System.currentTimeMillis() - planStartTime
                    ))
                    return@channelFlow
                }
                is RoundResult.Invalid -> {
                    // Loop to next correction round.
                }
            }
        }

        // Exhausted correction budget — emit the last rejection.
        val finalInvalid = lastRound as RoundResult.Invalid
        trySend(PlanUpdate.Failed(
            rawOutput = finalInvalid.rawOutput,
            reason = PlanFailure.InvalidEnvelope(
                validationReasons = finalInvalid.reasons.map { "${it.code}: ${it.message}" }
            ),
            totalDurationMs = System.currentTimeMillis() - planStartTime
        ))
    }

    /**
     * Run one round of generation + validation. Doesn't emit PlanUpdates
     * directly — returns a structured RoundResult the caller converts into
     * the final stream emission (Completed / Failed).
     *
     * When emitDeltas is true, streams token deltas out via channelFlow's
     * ProducerScope. This is how the initial round gets to show typing-like
     * feedback while correction rounds run silent.
     */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<PlanUpdate>.runOneRound(
        prompt: String,
        planStartTime: Long,
        emitDeltas: Boolean
    ): RoundResult {
        val accumulator = StringBuilder()
        var ttftMs = -1L
        var tokenCount = 0
        val roundStart = System.currentTimeMillis()

        try {
            withTimeout(PLAN_TIMEOUT_MS) {
                engine.generate(
                    prompt = prompt,
                    maxTokens = MAX_PLAN_TOKENS,
                    stopStrings = listOf("\n\n", "</s>", "<end_of_turn>")
                ).collect { token ->
                    if (ttftMs < 0) ttftMs = System.currentTimeMillis() - roundStart
                    accumulator.append(token)
                    tokenCount++
                    if (emitDeltas) trySend(PlanUpdate.Delta(token))

                    // Cheap early termination — once we have balanced JSON, stop.
                    if (hasBalancedJson(accumulator)) {
                        // Can't break a collector cleanly from a lambda;
                        // we rely on max_tokens / stop strings for cleaner termination.
                        // (Optimization: plug this up later if we measure it hurts latency.)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            return RoundResult.Failed(
                rawOutput = accumulator.toString(),
                reason = PlanFailure.Timeout(tokensGenerated = tokenCount)
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return RoundResult.Failed(
                rawOutput = accumulator.toString(),
                reason = PlanFailure.EngineError("${t.javaClass.simpleName}: ${t.message}")
            )
        }

        val rawOutput = accumulator.toString()
        val extractedJson = PromptTemplates.extractJson(rawOutput)

        return when (val validation = ActionValidator.validate(extractedJson)) {
            is ValidationResult.Valid -> RoundResult.Valid(
                envelope = validation.envelope,
                rawOutput = rawOutput,
                tokensGenerated = tokenCount,
                ttftMs = ttftMs.coerceAtLeast(0)
            )
            is ValidationResult.Degraded -> RoundResult.Valid(
                envelope = validation.envelope,
                rawOutput = rawOutput,
                tokensGenerated = tokenCount,
                ttftMs = ttftMs.coerceAtLeast(0)
            )
            is ValidationResult.Invalid -> RoundResult.Invalid(
                rawOutput = rawOutput,
                reasons = validation.reasons.map { RoundReason(it.code, it.message) }
            )
        }
    }

    private fun hasBalancedJson(sb: StringBuilder): Boolean {
        var depth = 0
        var inString = false
        var escape = false
        var sawObject = false
        for (c in sb) {
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> { depth++; sawObject = true }
                !inString && c == '}' -> depth--
            }
        }
        return sawObject && depth == 0
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    override suspend fun shutdown() {
        lifecycleMutex.withLock {
            warmupJob?.cancelAndJoin()
            warmupJob = null
            engine.shutdown()
            _state.value = BrainState.Shutdown
        }
    }

    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    private sealed class RoundResult {
        data class Valid(
            val envelope: ActionEnvelope,
            val rawOutput: String,
            val tokensGenerated: Int,
            val ttftMs: Long
        ) : RoundResult()

        data class Invalid(
            val rawOutput: String,
            val reasons: List<RoundReason>
        ) : RoundResult()

        data class Failed(
            val rawOutput: String,
            val reason: PlanFailure
        ) : RoundResult()
    }

    private data class RoundReason(val code: String, val message: String)

    companion object {
        private const val TAG = "LiteRtBrain"

        /** Hard timeout on any single plan round. TTFT target is 2s; this is a ceiling. */
        private const val PLAN_TIMEOUT_MS = 30_000L

        /** Max tokens per plan. ActionEnvelope JSON is typically <150 tokens; 256 is generous. */
        private const val MAX_PLAN_TOKENS = 256
    }
}