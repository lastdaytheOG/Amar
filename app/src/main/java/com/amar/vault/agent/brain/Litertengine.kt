package com.amar.vault.agent.brain.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import com.google.ai.edge.litertlm.Backend as LiteRtBackend

class LiteRtEngine(
    private val context: Context
) : InferenceEngine {

    @Volatile
    private var state: EngineState = EngineState.Idle
    private val loadMutex = Mutex()
    private val generateMutex = Mutex()

    @Volatile private var engine: Engine? = null

    override val isLoaded: Boolean
        get() = state is EngineState.Loaded

    override suspend fun load(modelFile: File, backend: Backend): LoadResult =
        loadMutex.withLock {
            (state as? EngineState.Loaded)?.let { loaded ->
                if (loaded.modelPath == modelFile.absolutePath && loaded.backend == backend) {
                    return@withLock LoadResult.Success(loaded.toEngineInfo())
                }
                shutdownInternal()
            }

            if (!modelFile.exists()) {
                return@withLock LoadResult.Failed(
                    reason = "model file does not exist: ${modelFile.absolutePath}",
                    backendsTried = emptyList()
                )
            }

            val backendsToTry = when (backend) {
                Backend.AUTO -> listOf(Backend.GPU, Backend.CPU)
                Backend.GPU -> listOf(Backend.GPU, Backend.CPU)
                Backend.CPU -> listOf(Backend.CPU)
                Backend.NPU -> listOf(Backend.NPU, Backend.GPU, Backend.CPU)
            }

            val tried = mutableListOf<Backend>()
            for (candidate in backendsToTry) {
                tried += candidate
                val result = tryLoadWithBackend(modelFile, candidate)
                if (result is LoadResult.Success) return@withLock result
                Log.w(TAG, "load failed on $candidate: ${(result as LoadResult.Failed).reason}")
            }

            LoadResult.Failed("all backends failed", tried)
        }

    private suspend fun tryLoadWithBackend(modelFile: File, backend: Backend): LoadResult =
        withContext(Dispatchers.IO) {
            try {
                val engineConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend.toLiteRt(context),
                    cacheDir = context.cacheDir.absolutePath
                )

                val newEngine = Engine(engineConfig)
                newEngine.initialize()
                engine = newEngine

                val info = EngineState.Loaded(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    maxContextTokens = 2048
                )
                state = info
                Log.i(TAG, "LiteRT-LM loaded: ${modelFile.name} on $backend")
                LoadResult.Success(info.toEngineInfo())

            } catch (oom: OutOfMemoryError) {
                cleanup()
                LoadResult.Failed("OOM loading model on $backend", listOf(backend))
            } catch (t: Throwable) {
                cleanup()
                val msg = "${t.javaClass.simpleName}: ${t.message?.take(200)}"
                LoadResult.Failed("engine load threw on $backend: $msg", listOf(backend))
            }
        }

    override fun generate(
        prompt: String,
        maxTokens: Int,
        stopStrings: List<String>,
        samplingConfig: SamplingConfig
    ): Flow<String> = callbackFlow {
        val engineRef = this@LiteRtEngine.engine
        if (engineRef == null || !isLoaded) {
            close(EngineNotLoadedException())
            return@callbackFlow
        }

        if (!generateMutex.tryLock()) {
            close(IllegalStateException("generate() called while another generation is active"))
            return@callbackFlow
        }

        var conversation: Conversation? = null
        try {
            val parsed = parsePrompt(prompt)

            Log.i(TAG, "prompt parsed: system=${parsed.system.length}ch, " +
                    "fewshot=${parsed.fewShots.size / 2} pairs, " +
                    "user='${parsed.userRequest.take(80)}'")

            val conversationConfig = ConversationConfig(
                samplerConfig = samplingConfig.toLiteRt(),
                systemInstruction = if (parsed.system.isBlank()) null
                else Contents.of(parsed.system),
                initialMessages = parsed.fewShots
            )
            conversation = engineRef.createConversation(conversationConfig)

            conversation.sendMessageAsync(parsed.userRequest)
                .catch { err -> close(err) }
                .onCompletion { cause -> if (cause == null) close() }
                .collect { message ->
                    val text = message.toString()
                    if (text.isNotEmpty()) trySend(text)
                }

            awaitClose {
                try { conversation?.close() } catch (_: Throwable) {}
            }

        } catch (t: Throwable) {
            close(t)
        } finally {
            try { conversation?.close() } catch (_: Throwable) {}
            generateMutex.unlock()
        }
    }.flowOn(Dispatchers.Default)

    // -------------------------------------------------------------------------
    // Prompt parsing
    // -------------------------------------------------------------------------

    private data class ParsedPrompt(
        val system: String,
        val fewShots: List<Message>,
        val userRequest: String
    )

    private fun parsePrompt(full: String): ParsedPrompt {
        if (!full.contains("\nUser: ") && !full.contains("User: ")) {
            return ParsedPrompt(system = "", fewShots = emptyList(), userRequest = full.trim())
        }

        val examplesMarker = "Examples:"
        val examplesIdx = full.indexOf(examplesMarker)

        val systemPart: String
        val bodyPart: String
        if (examplesIdx >= 0) {
            systemPart = full.substring(0, examplesIdx).trim()
            bodyPart = full.substring(examplesIdx + examplesMarker.length)
        } else {
            val lastUser = full.lastIndexOf("\nUser: ").let { if (it >= 0) it else full.lastIndexOf("User: ") }
            if (lastUser < 0) return ParsedPrompt("", emptyList(), full.trim())
            systemPart = full.substring(0, lastUser).trim()
            bodyPart = full.substring(lastUser)
        }

        val userRegex = Regex("(?m)^User:\\s*(.*)$")
        val jsonRegex = Regex("(?m)^JSON:\\s*(.*)$")

        val userMatches = userRegex.findAll(bodyPart).toList()
        val jsonMatches = jsonRegex.findAll(bodyPart).toList()

        val fewShots = mutableListOf<Message>()
        var userRequest = ""

        val pairCount = minOf(userMatches.size, jsonMatches.size)
        for (i in 0 until pairCount) {
            val u = userMatches[i].groupValues[1].trim()
            val j = jsonMatches[i].groupValues[1].trim()
            if (j.isEmpty()) {
                userRequest = u
                break
            }
            if (u.isNotBlank() && j.isNotBlank()) {
                fewShots += Message.user(u)
                fewShots += Message.model(j)
            }
        }

        if (userRequest.isEmpty() && userMatches.size > jsonMatches.size) {
            userRequest = userMatches.last().groupValues[1].trim()
        }

        return ParsedPrompt(
            system = systemPart,
            fewShots = fewShots,
            userRequest = userRequest.ifBlank { full.trim() }
        )
    }

    override suspend fun shutdown() {
        loadMutex.withLock { shutdownInternal() }
    }

    private fun shutdownInternal() {
        if (state is EngineState.Idle) return

        try {
            engine?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "engine.close() threw: ${t.message}")
        }
        cleanup()
        state = EngineState.Idle
        Log.i(TAG, "LiteRT-LM engine shut down")
    }

    private fun cleanup() {
        engine = null
    }

    override fun info(): EngineInfo? = (state as? EngineState.Loaded)?.toEngineInfo()

    private fun Backend.toLiteRt(context: Context): LiteRtBackend = when (this) {
        Backend.AUTO -> LiteRtBackend.GPU()
        Backend.CPU -> LiteRtBackend.CPU()
        Backend.GPU -> LiteRtBackend.GPU()
        Backend.NPU -> LiteRtBackend.NPU(
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        )
    }

    private fun SamplingConfig.toLiteRt(): SamplerConfig = SamplerConfig(
        topK = topK,
        topP = topP.toDouble(),
        temperature = temperature.toDouble()
    )

    private sealed class EngineState {
        data object Idle : EngineState()

        data class Loaded(
            val modelPath: String,
            val backend: Backend,
            val maxContextTokens: Int
        ) : EngineState() {
            fun toEngineInfo(): EngineInfo = EngineInfo(
                modelFileName = modelPath.substringAfterLast('/'),
                backend = backend,
                maxContextTokens = maxContextTokens,
                engineIdentifier = "litert-lm"
            )
        }
    }

    companion object {
        private const val TAG = "LiteRtEngine"
    }
}