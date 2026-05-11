package com.amar.vault.agent.brain.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Test-only inference engine that emits scripted tokens.
 *
 * Lets us test the Brain orchestration layer (prompt building, correction
 * loops, stream collection) without shipping a 500MB model file with tests.
 *
 * Usage:
 *   val engine = FakeInferenceEngine(
 *       scriptedResponses = mapOf(
 *           "open whatsapp" to """{"action":"open_app","params":{"app":"WhatsApp"}}""",
 *           "go home" to """{"action":"home","params":{}}"""
 *       )
 *   )
 *   engine.load(File("/fake"), Backend.CPU)
 *   engine.generate("open whatsapp", maxTokens = 100).collect { println(it) }
 *
 * When a prompt doesn't match any scripted response, the engine emits
 * [unmatchedResponse] (default: a clearly-wrong response so tests fail loudly
 * rather than silently passing on unexpected prompts).
 *
 * Emission pacing: tokens are emitted one character at a time with [tokenDelayMs]
 * between them, simulating streaming. Set to 0 for fast tests.
 */
class FakeInferenceEngine(
    private val scriptedResponses: Map<String, String> = emptyMap(),
    private val unmatchedResponse: String = """{"action":"<UNMATCHED_PROMPT>","params":{}}""",
    private val tokenDelayMs: Long = 0L,
    private val matchStrategy: MatchStrategy = MatchStrategy.CONTAINS
) : InferenceEngine {

    enum class MatchStrategy { CONTAINS, EXACT, PREFIX }

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var loadedBackend: Backend = Backend.CPU

    override val isLoaded: Boolean get() = loaded

    override suspend fun load(modelFile: File, backend: Backend): LoadResult {
        loaded = true
        loadedBackend = backend
        return LoadResult.Success(
            EngineInfo(
                modelFileName = modelFile.name,
                backend = backend,
                maxContextTokens = 2048,
                engineIdentifier = "fake-inference-engine"
            )
        )
    }

    override fun generate(
        prompt: String,
        maxTokens: Int,
        stopStrings: List<String>,
        samplingConfig: SamplingConfig
    ): Flow<String> = flow {
        if (!loaded) throw EngineNotLoadedException()

        val response = pickResponse(prompt)
        for (char in response) {
            if (tokenDelayMs > 0) delay(tokenDelayMs)
            emit(char.toString())

            // Honor stop strings like the real engine would.
            // (Cheap check — real engines are fancier with tokenization, but
            // FakeInferenceEngine just needs to behave plausibly.)
            if (stopStrings.isNotEmpty()) {
                // We don't have the running buffer here; real engines accumulate
                // internally. For simplicity we skip mid-stream stop matching
                // and rely on the caller's accumulator to check itself.
            }
        }
    }

    override suspend fun shutdown() {
        loaded = false
    }

    override fun info(): EngineInfo? = if (loaded) EngineInfo(
        modelFileName = "fake.model",
        backend = loadedBackend,
        maxContextTokens = 2048,
        engineIdentifier = "fake-inference-engine"
    ) else null

    private fun pickResponse(prompt: String): String {
        val entry = scriptedResponses.entries.firstOrNull { (key, _) ->
            when (matchStrategy) {
                MatchStrategy.EXACT -> prompt == key
                MatchStrategy.PREFIX -> prompt.startsWith(key)
                MatchStrategy.CONTAINS -> prompt.contains(key, ignoreCase = true)
            }
        }
        return entry?.value ?: unmatchedResponse
    }
}