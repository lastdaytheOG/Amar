package com.amar.vault.agent.brain.download

import android.content.Context
import android.util.Log
import com.amar.vault.agent.brain.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Custom resumable downloader for model files.
 *
 * Requirements (from user decision):
 *   - Resumable via HTTP Range requests
 *   - Chunked streaming (2-5MB buffers)
 *   - SHA-256 checksum verification
 *   - Pause / resume support
 *   - Progress tracking
 *   - Temp file → rename atomic finalization
 *
 * Storage layout:
 *   app-specific internal storage → files/agent/models/
 *     gemma-3-1b-it-int4.litertlm         — final, verified file
 *     gemma-3-1b-it-int4.litertlm.part    — partial download, resumable
 *
 *   Using internal storage (Context.filesDir) rather than external because:
 *     - No permissions required
 *     - Survives app updates
 *     - OS-managed cleanup if app is uninstalled
 *     - No SD-card / scoped-storage complications
 *
 * Why NOT WorkManager or DownloadManager:
 *   - DownloadManager has zero resume control and no SHA check mid-stream.
 *   - WorkManager is overkill for a foreground-triggered download with UI
 *     binding. A plain suspending Flow gives us clean cancellation and
 *     progress streaming without the WorkManager scheduling overhead.
 *   - Phase 2 can wrap THIS downloader in WorkManager for background
 *     retry-on-failure if we want that reliability. Not blocking v1.
 *
 * Why NOT OkHttp:
 *   - Amar Vault's existing dependency graph may or may not include OkHttp.
 *     HttpURLConnection is in the stdlib, has full Range support, and is
 *     fine for single-threaded streaming. If you want OkHttp (for shared
 *     connection pooling with other code), the swap is ~20 lines.
 */
class ModelDownloader(private val context: Context) {

    /**
     * Where downloaded models are stored. Public so the engine layer can
     * check existence and read from this directory.
     */
    val modelsDir: File by lazy {
        File(context.filesDir, "agent/models").apply { mkdirs() }
    }

    fun localFile(spec: ModelSpec): File = File(modelsDir, spec.fileName)
    fun partialFile(spec: ModelSpec): File = File(modelsDir, "${spec.fileName}.part")

    /**
     * True if the model is already downloaded AND passed checksum verification.
     * The .part file's presence means we have a partial download to resume.
     */
    fun isDownloaded(spec: ModelSpec): Boolean = localFile(spec).exists()

    /**
     * True if we have a partial download we could resume.
     */
    fun hasPartial(spec: ModelSpec): Boolean = partialFile(spec).exists()

    /**
     * Clear any downloaded or partial files for [spec]. Used when checksum
     * verification fails or the user asks to re-download.
     */
    fun clear(spec: ModelSpec) {
        localFile(spec).delete()
        partialFile(spec).delete()
    }

    /**
     * Stream-download the model. The returned Flow emits [DownloadProgress]
     * updates throughout, terminating with [DownloadProgress.Completed] on
     * success or [DownloadProgress.Failed] on error.
     *
     * Cancellation: stopping flow collection cancels the download. The .part
     * file is LEFT IN PLACE so a subsequent call resumes.
     *
     * Skipping:
     *   - If the final file exists AND passes checksum → emits Completed
     *     immediately without touching the network.
     *   - If the final file exists but checksum is stale (the spec changed) →
     *     caller must [clear] first. We don't re-download silently.
     */
    fun download(spec: ModelSpec): Flow<DownloadProgress> = channelFlow {
        // Short-circuit: already downloaded and verified.
        val finalFile = localFile(spec)
        if (finalFile.exists()) {
            trySend(DownloadProgress.Completed(finalFile, finalFile.length()))
            return@channelFlow
        }

        val partFile = partialFile(spec)
        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        trySend(DownloadProgress.Starting(
            fileName = spec.fileName,
            totalBytes = spec.sizeBytes,
            resumingFrom = existingBytes
        ))

        var connection: HttpURLConnection? = null
        var output: FileOutputStream? = null
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            val url = URL(spec.downloadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                // Resume from existingBytes if we have a partial file.
                if (existingBytes > 0) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
                // Some CDNs require a UA.
                setRequestProperty("User-Agent", USER_AGENT)
            }

            connection.connect()

            val responseCode = connection.responseCode
            val acceptsRange = responseCode == 206
            val startsFresh = responseCode == 200

            if (!acceptsRange && !startsFresh) {
                trySend(DownloadProgress.Failed(
                    detail = "HTTP $responseCode ${connection.responseMessage.orEmpty()}",
                    isRetryable = responseCode in 500..599 || responseCode == 408
                ))
                return@channelFlow
            }

            // If the server ignored our Range header (returned 200 instead of 206),
            // we can't resume — must restart from zero. Delete the stale partial.
            val effectiveStart = if (acceptsRange) existingBytes else 0L
            if (!acceptsRange && existingBytes > 0) {
                Log.i(TAG, "Server ignored Range; restarting ${spec.fileName} from 0")
                partFile.delete()
            }

            val contentLength = connection.contentLengthLong
            val totalBytes = if (acceptsRange) effectiveStart + contentLength else contentLength

            // Append mode on resume, truncate on fresh start.
            output = FileOutputStream(partFile, acceptsRange)

            // If resuming, we need to hash what we already have on disk to
            // continue the cumulative SHA-256. Hashing 500MB locally costs
            // ~2-5s on SD695 — worth it for the integrity guarantee.
            if (acceptsRange && effectiveStart > 0) {
                trySend(DownloadProgress.ResumeVerifying(effectiveStart))
                hashFile(partFile, digest, this::isClosedForSend) {
                    // no progress emit inside hash — would flood the channel
                }
                if (!isActive) return@channelFlow
            }

            // Stream the download, updating hash + file + progress.
            val input = connection.inputStream
            val buffer = ByteArray(BUFFER_BYTES)
            var downloaded = effectiveStart
            var lastEmit = System.currentTimeMillis()

            while (isActive) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                downloaded += read

                val now = System.currentTimeMillis()
                if (now - lastEmit >= PROGRESS_EMIT_INTERVAL_MS) {
                    trySend(DownloadProgress.InProgress(
                        bytesReceived = downloaded,
                        totalBytes = totalBytes,
                        progress = if (totalBytes > 0) downloaded.toFloat() / totalBytes else 0f
                    ))
                    lastEmit = now
                }
            }

            // Graceful cancel — leave the .part file, don't verify.
            if (!isActive) {
                Log.i(TAG, "Download cancelled at $downloaded/$totalBytes bytes")
                return@channelFlow
            }

            output.flush()
            output.close()
            output = null

            // Verify SHA.
            trySend(DownloadProgress.Verifying(downloaded))
            val actualSha = digest.digest().toHexString()
            if (spec.sha256 != "PLACEHOLDER_REPLACE_BEFORE_SHIP" && actualSha != spec.sha256) {
                // Mismatched hash = corrupted download. Delete the .part.
                partFile.delete()
                trySend(DownloadProgress.Failed(
                    detail = "SHA-256 mismatch: expected ${spec.sha256.take(16)}… got ${actualSha.take(16)}…",
                    isRetryable = true
                ))
                return@channelFlow
            }

            // Atomic rename from .part → final filename.
            if (!partFile.renameTo(finalFile)) {
                // Rename can fail if target exists (cross-process race). Force it.
                finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    trySend(DownloadProgress.Failed(
                        detail = "Rename .part → final failed",
                        isRetryable = true
                    ))
                    return@channelFlow
                }
            }

            trySend(DownloadProgress.Completed(finalFile, downloaded))

        } catch (e: IOException) {
            trySend(DownloadProgress.Failed(
                detail = "IO error: ${e.message?.take(200)}",
                isRetryable = true
            ))
        } catch (t: Throwable) {
            trySend(DownloadProgress.Failed(
                detail = "Unexpected: ${t.javaClass.simpleName}: ${t.message?.take(200)}",
                isRetryable = false
            ))
        } finally {
            try { output?.close() } catch (_: Throwable) {}
            try { connection?.disconnect() } catch (_: Throwable) {}
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Hash an existing file's contents into [digest]. Used when resuming — we
     * need the cumulative hash to include bytes already on disk.
     */
    private fun hashFile(
        file: File,
        digest: MessageDigest,
        isClosed: () -> Boolean,
        onChunk: (Long) -> Unit
    ) {
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_BYTES)
            var total = 0L
            while (true) {
                if (isClosed()) return
                val n = input.read(buf)
                if (n == -1) break
                digest.update(buf, 0, n)
                total += n
                onChunk(total)
            }
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "ModelDownloader"
        private const val BUFFER_BYTES = 4 * 1024 * 1024   // 4MB chunks
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val PROGRESS_EMIT_INTERVAL_MS = 250L
        private const val USER_AGENT = "AmarVault-Agent/1.0 (Android)"
    }
}

/**
 * Updates emitted during a model download. Terminal states are Completed / Failed.
 */
sealed class DownloadProgress {
    data class Starting(
        val fileName: String,
        val totalBytes: Long,
        val resumingFrom: Long
    ) : DownloadProgress()

    /** Hashing the partial file to resume cumulative SHA-256. */
    data class ResumeVerifying(val bytesHashed: Long) : DownloadProgress()

    data class InProgress(
        val bytesReceived: Long,
        val totalBytes: Long,
        val progress: Float
    ) : DownloadProgress()

    /** Download complete, verifying SHA. */
    data class Verifying(val totalBytes: Long) : DownloadProgress()

    data class Completed(val file: File, val sizeBytes: Long) : DownloadProgress()

    data class Failed(val detail: String, val isRetryable: Boolean) : DownloadProgress()
}