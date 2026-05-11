package com.amar.vault

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Singleton engine for on-device LLM inference via llama.cpp.
 *
 * Usage:
 *   val engine = QwenEngine.getInstance(context)
 *   engine.ensureLoaded()  // loads model if not already loaded
 *   val answer = engine.generate("What was my last OTP?", retrievedChunks)
 *   engine.release()       // free memory when done
 *
 * The engine auto-selects the best model variant for current device
 * conditions (RAM, thermal, battery, chipset) via DeviceCapability.
 *
 * QWEN 2.5 ChatML format:
 *   <|im_start|>system\n{system_prompt}<|im_end|>
 *   <|im_start|>user\n{user_message}<|im_end|>
 *   <|im_start|>assistant\n
 */
class QwenEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "QwenEngine"

        @Volatile
        private var instance: QwenEngine? = null

        fun getInstance(context: Context): QwenEngine =
            instance ?: synchronized(this) {
                instance ?: QwenEngine(context.applicationContext).also { instance = it }
            }

        /** Release the singleton and free native memory. */
        fun release() {
            instance?.unload()
            instance = null
        }
    }

    init {
        System.loadLibrary("llm_bridge")
    }

    // ── Native JNI methods ──────────────────────────────────────
    private external fun nativeLoadModel(
        modelPath: String, contextSize: Int, nGpuLayers: Int, nThreads: Int
    ): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeAbort()
    private external fun nativeUnload()
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeGetInfo(): String

    // ── State ───────────────────────────────────────────────────
    private var currentConfig: DeviceCapability.ModelConfig? = null
    val isLoaded: Boolean get() = nativeIsLoaded()

    // ── System prompt for RAG ───────────────────────────────────
    private val SYSTEM_PROMPT = "Answer using only the provided context. Be brief and direct."

    // ═══════════════════════════════════════════════════════════════
    // Model lifecycle
    // ═══════════════════════════════════════════════════════════════

    /**
     * Ensures a model is loaded. Auto-selects the best variant
     * for current device conditions. Returns true if ready.
     */
    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext true

        val state = DeviceCapability.getDeviceState(context)
        val config = DeviceCapability.selectModel(state)

        Log.d(TAG, "Selected model: ${config.displayName} (${config.fileName})")
        Log.d(TAG, "Device: chipTier=${state.chipTier}, freeRAM=${state.freeRamGB}GB, " +
                "thermal=${state.thermalStatus}, battery=${state.batteryPercent}%")

        val modelFile = resolveModelFile(config.fileName)
        if (modelFile == null) {
            Log.e(TAG, "Model file not found: ${config.fileName}")
            return@withContext false
        }

        val success = nativeLoadModel(
            modelPath = modelFile.absolutePath,
            contextSize = config.contextSize,
            nGpuLayers = config.nGpuLayers,
            nThreads = config.nThreads,
        )

        if (success) {
            currentConfig = config
            Log.d(TAG, "Model loaded: ${config.displayName} | ${nativeGetInfo()}")
        } else {
            Log.e(TAG, "Failed to load model: ${config.fileName}")
        }

        success
    }

    /**
     * Unload model and free all native memory.
     * Call when app goes to background or on memory pressure.
     */
    fun unload() {
        if (isLoaded) {
            nativeUnload()
            currentConfig = null
            Log.d(TAG, "Model unloaded")
        }
    }

    /** Abort an in-progress generation (thread-safe). */
    fun abort() { nativeAbort() }

    // ═══════════════════════════════════════════════════════════════
    // Inference
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate an answer to a user query using retrieved vault chunks as context.
     *
     * @param query The user's question
     * @param chunks Retrieved VaultItems from search (top results)
     * @return Generated answer text
     */
    suspend fun generate(
        query: String,
        chunks: List<VaultItem>,
        maxTokens: Int? = null,
    ): String = withContext(Dispatchers.IO) {
        if (!ensureLoaded()) {
            return@withContext "Model could not be loaded. Please check storage."
        }

        val config = currentConfig
            ?: return@withContext "Model configuration error."

        // Limit to 1 chunk with 100 chars — keep prompt under 150 tokens total
        val limitedChunks = chunks.take(1)
        val prompt = buildChatMLPrompt(query, limitedChunks)
        val tokens = maxTokens ?: config.maxGenTokens.coerceAtMost(50)

        Log.d(TAG, "Generating: query='$query', chunks=${chunks.size}, maxTokens=$tokens")
        val t0 = System.currentTimeMillis()

        val result = nativeGenerate(prompt, tokens)

        val elapsed = System.currentTimeMillis() - t0
        Log.d(TAG, "Generated in ${elapsed}ms: ${result.length} chars")

        result.trim()
    }

    /**
     * Quick generation without RAG context — for simple formatting tasks.
     */
    suspend fun generateRaw(prompt: String, maxTokens: Int = 150): String =
        withContext(Dispatchers.IO) {
            if (!ensureLoaded()) return@withContext ""
            nativeGenerate(prompt, maxTokens).trim()
        }

    // ═══════════════════════════════════════════════════════════════
    // ChatML prompt assembly
    // ═══════════════════════════════════════════════════════════════

    /**
     * Builds a ChatML prompt with system instructions, retrieved context,
     * and the user's query. This is the RAG prompt template.
     *
     * Format:
     *   <|im_start|>system
     *   {system_prompt}
     *
     *   Context from vault:
     *   <source id="1" type="screenshot">OCR text here</source>
     *   <source id="2" type="pdf" file="report.pdf" page="3">chunk text</source>
     *   <|im_end|>
     *   <|im_start|>user
     *   {query}<|im_end|>
     *   <|im_start|>assistant
     */
    private fun buildChatMLPrompt(query: String, chunks: List<VaultItem>): String {
        val sb = StringBuilder()

        // System message with context
        sb.append("<|im_start|>system\n")
        sb.append(SYSTEM_PROMPT)

        if (chunks.isNotEmpty()) {
            sb.append("\n\nContext from user's vault:\n")
            chunks.forEachIndexed { i, chunk ->
                val cleanText = chunk.ocrText
                    .substringBefore("\n[")  // remove tags
                    .trim()
                    .take(100)  // minimal text for fast prefill

                val sourceFile = chunk.sourceFile?.takeIf { it.isNotBlank() }
                val pageInfo = if (chunk.pageNum > 0) " page=\"${chunk.pageNum}\"" else ""
                val fileInfo = if (sourceFile != null) " file=\"$sourceFile\"" else ""

                sb.append("<source id=\"${i + 1}\" type=\"${chunk.itemType}\"$fileInfo$pageInfo>")
                sb.append(cleanText)
                sb.append("</source>\n")
            }
        }

        sb.append("<|im_end|>\n")

        // User message
        sb.append("<|im_start|>user\n")
        sb.append(query)
        sb.append("<|im_end|>\n")

        // Assistant start (model completes from here)
        sb.append("<|im_start|>assistant\n")

        return sb.toString()
    }

    // ═══════════════════════════════════════════════════════════════
    // Model file resolution
    // ═══════════════════════════════════════════════════════════════

    /**
     * Finds the GGUF model file. Checks in order:
     * 1. App's internal files directory (downloaded models)
     * 2. External files directory
     * 3. Assets (for bundled models — not recommended for large files)
     */
    private fun resolveModelFile(fileName: String): File? {
        val candidates = mutableListOf<File>()

        // 1. Internal storage
        candidates.add(File(context.filesDir, "models/$fileName"))

        // 2. External files dir (context.getExternalFilesDir)
        context.getExternalFilesDir(null)?.let {
            candidates.add(File(it, "models/$fileName"))
            candidates.add(File(it, fileName)) // without models/ subfolder
        }

        // 3. /sdcard/Android/data/package/ paths (adb push often lands here)
        candidates.add(File("/sdcard/Android/data/${context.packageName}/files/models/$fileName"))
        candidates.add(File("/storage/emulated/0/Android/data/${context.packageName}/files/models/$fileName"))

        // 4. /sdcard/ root (easiest adb push target)
        candidates.add(File("/sdcard/$fileName"))
        candidates.add(File("/sdcard/models/$fileName"))

        // 5. Direct path
        candidates.add(File(fileName))

        for (f in candidates) {
            if (f.exists()) {
                Log.d(TAG, "Found model at: ${f.absolutePath} (${f.length() / 1024 / 1024}MB)")
                return f
            }
        }

        Log.e(TAG, "Model not found: $fileName")
        Log.e(TAG, "Searched ${candidates.size} paths:")
        candidates.forEach { Log.e(TAG, "  ✗ ${it.absolutePath}") }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // Diagnostics
    // ═══════════════════════════════════════════════════════════════

    fun getModelInfo(): String = if (isLoaded) nativeGetInfo() else "Not loaded"

    fun getCurrentConfig(): DeviceCapability.ModelConfig? = currentConfig
}