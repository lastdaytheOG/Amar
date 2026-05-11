package com.amar.vault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class IndexingForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting vault indexing...", 0, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INDEX_ALL -> {
                if (!isRunning) {
                    isRunning = true
                    indexAll()
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun indexAll() {
        scope.launch {
            try {
                updateNotification("Scanning your photos...", 0, 0)

                // ELITE FIX: Memory-safe URI loading
                val dao = VaultDatabase.get(applicationContext).vaultDao()
                val indexedUris = dao.getAllUris().toHashSet()

                val allUris = mutableListOf<Uri>()
                val cursor = contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    null, null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )

                cursor?.use {
                    val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (it.moveToNext()) {
                        val id = it.getLong(idCol)
                        val uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        )
                        if (uri.toString() !in indexedUris) allUris.add(uri)
                    }
                }

                val total = allUris.size
                var processed = 0

                if (total == 0) {
                    updateNotification("Vault is up to date!", 0, 0)
                    delay(2000)
                    stopSelf()
                    return@launch
                }

                updateNotification("Found $total photos to index...", 0, total)

                // Process with thermal awareness
                var i = 0
                val pipeline = IndexingPipeline.getInstance(applicationContext)

                while (i < allUris.size && isRunning) {
                    val batchSize = when {
                        thermalStatus() >= 5 -> 10   // SEVERE: Throttle hard
                        thermalStatus() >= 3 -> 50   // MODERATE
                        freeMemoryMb() < 200 -> 30   // LOW RAM
                        else                 -> 200  // GREEN LIGHT
                    }
                    val batch = allUris.subList(i, minOf(i + batchSize, allUris.size))

                    for (uri in batch) {
                        if (!isRunning) break

                        var bmp: Bitmap? = null
                        try {
                            bmp = loadBitmapSafely(uri)
                            if (bmp != null) {
                                pipeline.indexBitmap(bmp, uri.toString())
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            // ELITE FIX: Guaranteed memory cleanup even if OCR crashes
                            bmp?.recycle()
                            bmp = null
                        }
                        processed++
                    }

                    i += batch.size

                    // ELITE FIX: Native Android Progress Bar
                    updateNotification("Processing...", processed, total)

                    // Thermal backoff: Give the CPU a breathing window if it's hot
                    val thermal = thermalStatus()
                    if (thermal >= 3) {
                        delay(2000L * (1 shl thermal.coerceAtMost(4)))
                    }
                }

                if (isRunning) {
                    updateNotification("✓ Successfully indexed $processed photos", total, total)
                    delay(3000)
                }
                stopSelf()

            } catch (e: Exception) {
                updateNotification("Indexing failed: ${e.message}", 0, 0)
                delay(3000)
                stopSelf()
            }
        }
    }

    // ELITE FIX: Modern, safe bitmap decoding that prevents deprecation warnings and memory spikes
    private fun loadBitmapSafely(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // Prevents hardware bitmap crashes in ML Kit

                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun thermalStatus(): Int {
        return if (Build.VERSION.SDK_INT >= 29) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            pm?.currentThermalStatus ?: 0
        } else 0
    }

    private fun freeMemoryMb(): Long {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vault Indexing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while indexing your photos"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Amar Vault")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Adds the actual progress bar graphic to the notification!
        if (max > 0) {
            builder.setProgress(max, progress, false)
        } else {
            builder.setProgress(0, 0, false) // Hides progress bar
        }

        return builder.build()
    }

    private fun updateNotification(text: String, progress: Int, max: Int) {
        val notification = buildNotification(text, progress, max)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "amar_indexing"
        const val NOTIFICATION_ID = 1001
        const val ACTION_INDEX_ALL = "com.amar.vault.ACTION_INDEX_ALL"
        const val ACTION_STOP = "com.amar.vault.ACTION_STOP"

        fun startIndexing(context: Context) {
            val intent = Intent(context, IndexingForegroundService::class.java).apply {
                action = ACTION_INDEX_ALL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, IndexingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}