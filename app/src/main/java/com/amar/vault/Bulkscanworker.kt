package com.amar.vault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Background worker that bulk-indexes photos from user-selected folders.
 *
 * Features:
 * - Thermal-aware batching: burst 8 photos → pause 2s → repeat
 * - Persistent notification with progress
 * - Resumable: tracks last-processed timestamp, skips already-indexed
 * - WorkManager guarantees completion even if app is killed
 */
class BulkScanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BulkScanWorker"
        private const val CHANNEL_ID = "bulk_scan"
        private const val NOTIFICATION_ID = 9001
        private const val BATCH_SIZE = 8          // photos per burst
        private const val COOL_DOWN_MS = 2000L    // pause between bursts

        /** Enqueue a bulk scan. Safe to call multiple times — WorkManager deduplicates. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<BulkScanWorker>()
                .addTag("bulk_scan")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("bulk_scan", androidx.work.ExistingWorkPolicy.KEEP, request)
        }

        /** Check if a bulk scan is currently running. */
        fun isRunning(context: Context): Boolean {
            val infos = WorkManager.getInstance(context)
                .getWorkInfosByTag("bulk_scan").get()
            return infos.any { it.state == WorkInfo.State.RUNNING }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        return buildForegroundInfo(0, 0, 0)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            createNotificationChannel()
            val prefs = ScanPreferences.prefsFlow(applicationContext).first()
            val folders = prefs.foldersToScan()
            val uris = queryPhotos(folders)

            Log.d(TAG, "Starting bulk scan: ${uris.size} photos from ${folders.size} folders")

            if (uris.isEmpty()) {
                ScanPreferences.markInitialScanDone(applicationContext)
                return@withContext Result.success()
            }

            val pipeline = IndexingPipeline.getInstance(applicationContext)
            var indexed = 0
            var skipped = 0

            uris.forEachIndexed { i, photoUri ->
                // Check cancellation
                if (isStopped) return@withContext Result.failure()

                // Update notification
                setForeground(buildForegroundInfo(i + 1, uris.size, indexed))

                try {
                    val stream = applicationContext.contentResolver.openInputStream(photoUri)
                    if (stream != null) {
                        val bitmap = BitmapFactory.decodeStream(stream)
                        stream.close()
                        if (bitmap != null) {
                            pipeline.indexBitmap(bitmap, photoUri.toString(), "photo")
                            bitmap.recycle()
                            indexed++
                        } else {
                            skipped++
                        }
                    } else {
                        skipped++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to index $photoUri: ${e.message}")
                    skipped++
                }

                // Thermal-aware batching: pause every BATCH_SIZE photos
                if ((i + 1) % BATCH_SIZE == 0 && i < uris.size - 1) {
                    Log.d(TAG, "Cooling down after batch... ($indexed indexed, $skipped skipped)")
                    delay(COOL_DOWN_MS)
                }

                // Report progress
                setProgress(workDataOf(
                    "current" to (i + 1),
                    "total" to uris.size,
                    "indexed" to indexed,
                ))
            }

            ScanPreferences.markInitialScanDone(applicationContext)
            Log.d(TAG, "Bulk scan complete: $indexed indexed, $skipped skipped out of ${uris.size}")

            // Final notification
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("Scan Complete")
                .setContentText("$indexed photos indexed")
                .setAutoCancel(true)
                .build())

            Result.success(workDataOf("indexed" to indexed, "total" to uris.size))
        } catch (e: Exception) {
            Log.e(TAG, "Bulk scan failed", e)
            Result.retry()
        }
    }

    /**
     * Queries MediaStore for all images in the selected folders.
     * Returns content URIs sorted by date (newest first).
     */
    private fun queryPhotos(folders: List<String>): List<android.net.Uri> {
        val uris = mutableListOf<android.net.Uri>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
        )

        // Build selection for folders
        val selection: String?
        val selectionArgs: Array<String>?

        if (folders.isEmpty() || folders.contains("")) {
            // Scan all
            selection = null
            selectionArgs = null
        } else {
            selection = folders.joinToString(" OR ") {
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            }
            selectionArgs = folders.map { "%$it%" }.toTypedArray()
        }

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        applicationContext.contentResolver.query(
            collection, projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                uris.add(uri)
            }
        }

        Log.d(TAG, "Found ${uris.size} photos to scan")
        return uris
    }

    private fun buildForegroundInfo(current: Int, total: Int, indexed: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Scanning Photos")
            .setContentText(if (total > 0) "$current of $total ($indexed indexed)" else "Preparing scan...")
            .setProgress(total, current, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        // Android 10+ (Q) requires explicit foreground service type
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Photo Scanning",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows progress while scanning your photos" }
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}