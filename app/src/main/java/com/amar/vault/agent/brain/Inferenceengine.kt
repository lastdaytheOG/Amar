package com.amar.vault.agent.brain.engine

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Inference engine abstraction.
 *
 * Decouples the Brain orchestration logic (PromptTemplates, correction rounds,
 * context building) from the specific inference runtime (LiteRT-LM, llama.cpp,
 * MLC, etc.).
 *
 * Why not bind directly to LiteRT-LM:
 *   1. The LiteRT-LM Kotlin API is preview as of April 2026. Shape will evolve.
 *      Keeping it behind this interface means the Brain doesn't need updates
 *      when LiteRT-LM bumps a major version.
 *   2. Unit tests use a FakeInferenceEngine that emits scripted tokens.
 *      Testing the Brain without a 500MB model file is only possible with
 *      this abstraction.
 *   3. Phase 3 Shadow Brain may use llama.cpp. Same interface, different impl,
 *      no Brain changes required.
 *
 * Contract:
 *   - generate() returns a Flow of String tokens. Each emission is ONE token
 *     or a partial word (depends on the tokenizer).
 *   - The flow completes when the model hits a stop condition (EOS token,
 *     max_tokens reached, or stop-string matched).
 *   - Cancelling flow collection must cancel generation inside the engine.
 *   - load() must be called before generate(). Calling generate() on an
 *     unloaded engine returns EngineNotLoaded error (thrown, not emitted).
 *
 * Thread safety:
 *   Inference is single-sessioned in v1 — one generation at a time. Calling
 *   generate() while another generate() is active should either queue or
 *   error (implementation's choice; LiteRtEngine errors).
 */
interface InferenceEngine {

    /** True once [load] has completed and the engine can accept generate() calls. */
    val isLoaded: Boolean

    /**
     * Load a model into the engine. Long-running (seconds). Safe to call
     * multiple times — no-op if already loaded with the same file.
     *
     * @param modelFile The verified .litertlm model file.
     * @param backend Preferred backend. Engine may downgrade on unavailability.
     */
    suspend fun load(modelFile: File, backend: Backend): LoadResult

    /**
     * Generate text given a prompt. Streams tokens as they're produced.
     *
     * @param prompt Full formatted prompt (chat template already applied).
     * @param maxTokens Hard cap on generation length. Prevents runaways.
     * @param stopStrings Early-stop patterns. If any match in the running
     *   output, generation stops at that point.
     */
    fun generate(
        prompt: String,
        maxTokens: Int,
        stopStrings: List<String> = emptyList(),
        samplingConfig: SamplingConfig = SamplingConfig.Greedy
    ): Flow<String>

    /**
     * Release engine resources. After shutdown() the engine must be re-loaded
     * before any generate() call. Safe to call on unloaded engines.
     */
    suspend fun shutdown()

    /**
     * Diagnostic info about the loaded model + backend. Null if not loaded.
     */
    fun info(): EngineInfo?
}

enum class Backend {
    /** Let the engine pick based on device probe. */
    AUTO,
    /** CPU-only. Safest, slowest. */
    CPU,
    /** GPU via OpenCL (LiteRT-LM's preferred GPU path on Android). */
    GPU,
    /** NPU — flagship-only, if supported by the SoC. */
    NPU
}

/**
 * Sampling parameters. Greedy is the default for agent planning: we want
 * deterministic, structured output, not creative variation.
 */
data class SamplingConfig(
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val repeatPenalty: Float
) {
    companion object {
        /** Deterministic — for structured DSL generation. What v1 uses. */
        val Greedy = SamplingConfig(temperature = 0.0f, topK = 1, topP = 1.0f, repeatPenalty = 1.0f)

        /** Slightly stochastic — for when you want less model "stickiness." */
        val Balanced = SamplingConfig(temperature = 0.4f, topK = 40, topP = 0.95f, repeatPenalty = 1.1f)
    }
}

sealed class LoadResult {
    data class Success(val info: EngineInfo) : LoadResult()
    data class Failed(val reason: String, val backendsTried: List<Backend>) : LoadResult()
}

data class EngineInfo(
    val modelFileName: String,
    val backend: Backend,
    val maxContextTokens: Int,
    val engineIdentifier: String  // "litert-lm-0.10", "llama-cpp-...", etc.
)

/**
 * Thrown when generate() is called before a successful load.
 * (Flow errors are hard to signal clearly, so this is a thrown exception.)
 */
class EngineNotLoadedException : IllegalStateException("Engine not loaded. Call load() first.")