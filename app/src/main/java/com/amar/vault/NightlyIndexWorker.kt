package com.amar.vault

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class NightlyIndexWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                val processor = ThermalAwareBatchProcessor(applicationContext)
                val unindexed = findUnindexedImagesSince(applicationContext)
                if (unindexed.isEmpty()) return@withContext Result.success()
                processor.processQueue(unindexed)
                // Save last indexed timestamp
                saveLastIndexedTime(applicationContext)
                Result.success()
            } catch (e: Exception) {
                e.printStackTrace()
                Result.retry()
            }
        }
    }

    private suspend fun findUnindexedImagesSince(
        context: Context
    ): List<android.net.Uri> = withContext(Dispatchers.IO) {

        val allUris     = mutableListOf<android.net.Uri>()
        val lastIndexed = getLastIndexedTime(context)

        // Get already-indexed URIs from Room
        val dao         = VaultDatabase.get(context).vaultDao()
        val indexedUris = dao.getAll().map { it.uri }.toHashSet()

        // Query ONLY images added since last index run
        // This is the key upgrade — not loading all 50K images every night
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH
        )

        val selection     = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf((lastIndexed / 1000).toString()) // MediaStore uses seconds

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )

        cursor?.use {
            val idCol   = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (it.moveToNext()) {
                val id  = it.getLong(idCol)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                if (uri.toString() !in indexedUris) {
                    allUris.add(uri)
                }
            }
        }

        allUris
    }

    private fun getLastIndexedTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Default: 30 days ago on first run — index recent history only
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        return prefs.getLong(KEY_LAST_INDEXED, thirtyDaysAgo)
    }

    private fun saveLastIndexedTime(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_INDEXED, System.currentTimeMillis())
            .apply()
    }

    companion object {
        const val WORK_TAG        = "nightly_index"
        private const val PREFS_NAME       = "amar_prefs"
        private const val KEY_LAST_INDEXED = "last_indexed_timestamp"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NightlyIndexWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(
                    calculateDelayTo2AM(),
                    TimeUnit.MILLISECONDS
                )
                .addTag(WORK_TAG)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_TAG,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }

        private fun calculateDelayTo2AM(): Long {
            val now  = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 2)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}