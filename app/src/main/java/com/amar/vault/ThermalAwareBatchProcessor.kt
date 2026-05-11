package com.amar.vault

import android.content.Context
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import com.amar.vault.NativeVectorEngine
import com.amar.vault.VectorSearchManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class ThermalAwareBatchProcessor(private val context: Context) {

    private val thermalManager = context.getSystemService(PowerManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val pipeline = IndexingPipeline.getInstance(context)

    // Batch sizes based on thermal headroom
    private fun batchSize(): Int = when {
        thermalStatus() >= THERMAL_STATUS_SEVERE   -> 10   // hot — go slow
        thermalStatus() >= THERMAL_STATUS_MODERATE -> 50   // warm
        freeMemoryMb() < 200                       -> 30   // low RAM
        else                                       -> 200  // cool + charging = full speed
    }

    private fun thermalStatus(): Int {
        return if (Build.VERSION.SDK_INT >= 29) {
            thermalManager?.currentThermalStatus ?: 0
        } else 0
    }

    private fun freeMemoryMb(): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(info)
        return (info.availMem / (1024 * 1024))
    }

    suspend fun processQueue(uris: List<Uri>) {
        var i = 0

        // Instantiate the vector managers to trigger disk flushes
        val vectorEngine = NativeVectorEngine()
        val vectorSearchManager = VectorSearchManager(context)

        while (i < uris.size && coroutineContext.isActive) { // ELITE FIX: Respect WorkManager cancellations
            val currentBatchSize = batchSize()
            val batch = uris.subList(i, minOf(i + currentBatchSize, uris.size))

            for (uri in batch) {
                if (!coroutineContext.isActive) break // Stop immediately if OS kills the worker

                var bmp: Bitmap? = null
                try {
                    bmp = loadBitmapSafely(uri)
                    if (bmp != null) {
                        pipeline.indexBitmap(bmp, uri.toString())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    // ELITE FIX: Guaranteed native memory cleanup, even if OCR pipeline crashes
                    bmp?.recycle()
                    bmp = null
                }
            }

            i += batch.size

            // 🚨 CRITICAL FIX: Flush the C++ HNSW graph to disk after every batch!
            // If the OS kills this background worker right now, the data in RAM will be saved.
            try {
                vectorEngine.saveToDisk()
                vectorSearchManager.persistIdMappings() // Note: make sure this is public in VectorSearchManager!
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Exponential backoff if thermal pressure rises
            val thermal = thermalStatus()
            if (thermal >= THERMAL_STATUS_MODERATE) {
                // E.g., Thermal level 3 = 16 seconds of delay to let CPU cool down
                val backoffMs = 2000L * (1 shl thermal.coerceAtMost(4))
                delay(backoffMs)
            }
        }
    }

    // ELITE FIX: Modern, safe bitmap decoding that prevents ML Kit / Tesseract native crashes
    private fun loadBitmapSafely(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // Forces CPU RAM, required for ML Kit
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val THERMAL_STATUS_MODERATE = 3
        private const val THERMAL_STATUS_SEVERE   = 5
    }
}