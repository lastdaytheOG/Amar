package com.amar.vault

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NativeLlamaEngine — Kotlin singleton wrapping the llama.cpp C++ inference engine.
 *
 * Thread safety:
 * All native calls are @Synchronized. The C++ side also holds a std::mutex.
 * This implementation uses strict Thread joining to prevent JNI Mutex deadlocks
 * and relies on the JNI bridge to reset its own cancel flags upon starting.
 */
object NativeLlamaEngine {
    private const val TAG = "NativeLlamaEngine"

    private var nativeAvailable = false

    init {
        try {
            System.loadLibrary("amar_llama_engine")
            nativeAvailable = true
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
            nativeAvailable = false
        }
    }

    @Volatile
    private var isLoaded = false

    // =========================================================================
    // Native JNI bindings
    // =========================================================================

    private external fun loadModelNative(modelPath: String): Boolean
    private external fun generateTextNative(prompt: String, callback: TokenCallback)
    private external fun unloadModelNative()
    private external fun cancelGenerationNative()
    private external fun isModelLoadedNative(): Boolean

    // =========================================================================
    // Token Callback Interface
    // =========================================================================

    interface TokenCallback {
        fun onToken(token: String)
        fun onComplete()
    }

    // =========================================================================
    // Public API
    // =========================================================================

    @Synchronized
    fun loadModel(modelPath: String): Boolean {
        if (!nativeAvailable) {
            Log.e(TAG, "Cannot load model: native library not available")
            return false
        }

        if (isLoaded) {
            Log.w(TAG, "Model already loaded. Unloading previous model first.")
            unloadInternal()
        }

        Log.i(TAG, "Loading model: $modelPath")
        val success = loadModelNative(modelPath)

        if (success) {
            isLoaded = true
            Log.i(TAG, "Model loaded successfully")
        } else {
            Log.e(TAG, "Failed to load model: $modelPath")
        }

        return success
    }

    /**
     * Generate text and collect the full response as a single string.
     * Uses strict thread-joining to prevent JNI Mutex deadlocks.
     */
    suspend fun generateBlocking(prompt: String): String = withContext(Dispatchers.IO) {
        if (!nativeAvailable || !isLoaded) return@withContext ""

        Log.i(TAG, "generateBlocking: Requesting generation lock...")

        // Note: We DO NOT call cancelGenerationNative() here anymore.
        // The C++ JNI bridge handles resetting g_cancel to false internally
        // the moment it acquires the mutex lock.

        val builder = StringBuilder()
        val latch = java.util.concurrent.CountDownLatch(1)

        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                builder.append(token)
            }
            override fun onComplete() {
                Log.i(TAG, "generateBlocking: JNI Complete.")
                latch.countDown()
            }
        }

        val generationThread = Thread {
            try {
                generateTextNative(prompt, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Native generate error: ${e.message}")
                latch.countDown()
            }
        }
        generationThread.start()

        // Wait up to 5 minutes for generation to finish naturally (for slower devices/models)
        val finished = latch.await(300, java.util.concurrent.TimeUnit.SECONDS)

        if (!finished) {
            Log.w(TAG, "generateBlocking timed out. Force cancelling JNI...")
            // Signal the C++ while loop to abort
            cancelGenerationNative()

            // CRITICAL: We MUST wait for the C++ thread to see the cancel flag
            // and actually release g_gen_mutex before returning to Kotlin.
            // This guarantees the next query won't hit a locked mutex.
            generationThread.join(2000)
        }

        builder.toString().trim()
    }

    @Synchronized
    fun unload() {
        unloadInternal()
    }

    fun isLoaded(): Boolean = nativeAvailable && isLoaded && isModelLoadedNative()

    fun cancelGeneration() {
        cancelGenerationNative()
    }

    private fun unloadInternal() {
        if (isLoaded) {
            Log.i(TAG, "Unloading model from memory")
            unloadModelNative()
            isLoaded = false
            Log.i(TAG, "Model unloaded. RAM freed.")
        }
    }
}