package com.amar.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class IndexingPipeline private constructor(private val context: Context) {

    companion object {
        private const val TESS_DATA_DIR     = "tessdata"
        private const val ML_KIT_MIN_LENGTH = 10  // lowered from 15 — catch more partial text
        private const val CHUNK_SIZE        = 200  // match DocumentIndexer's BGE-M3 optimal size
        private const val CHUNK_OVERLAP     = 30

        @Volatile
        private var INSTANCE: IndexingPipeline? = null

        fun getInstance(context: Context): IndexingPipeline {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: IndexingPipeline(context.applicationContext)
                    .also { INSTANCE = it }
            }
        }
    }

    private val mlKitEn = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val mlKitHi = TextRecognition.getClient(
        DevanagariTextRecognizerOptions.Builder().build()
    )
    private val tessApi: TessBaseAPI by lazy { initTesseract() }
    private val tessMutex  = Mutex()
    private val embedder   get() = AppEmbeddingEngine.get(context)
    private val dao        = VaultDatabase.get(context).vaultDao()
    private val vectorSearch get() = VectorSearchManager.getInstance(context)

    suspend fun indexBitmap(
        bitmap: Bitmap,
        uri: String,
        itemType: String = "screenshot"
    ) = withContext(Dispatchers.Default) {

        val t0 = System.currentTimeMillis()

        val hash = computePHash(bitmap)
        if (dao.hashExists(hash)) return@withContext
        android.util.Log.d("IndexTiming", "pHash: ${System.currentTimeMillis() - t0}ms")

        val t1      = System.currentTimeMillis()

        // Run OCR and QR/barcode scanning in parallel
        val ocrDeferred = async { runAggressiveOcr(bitmap) }
        val qrDeferred  = async(Dispatchers.IO) { scanBarcode(bitmap) }

        val ocrText    = ocrDeferred.await()
        val qrPayloads = qrDeferred.await()

        android.util.Log.d("IndexTiming", "OCR: ${System.currentTimeMillis() - t1}ms | length: ${ocrText.length} | QR: ${qrPayloads.size} found")

        // If both OCR and QR found nothing, skip
        if (ocrText.isBlank() && qrPayloads.isEmpty()) return@withContext

        // Build final text with smart tags + QR payloads
        val smartTags = generateSmartTags(ocrText)
        val qrTags = qrPayloads.map { payload ->
            "qr_data:$payload"
        }

        val allTags = mutableListOf<String>()
        if (smartTags.isNotEmpty()) allTags.add(smartTags)
        allTags.addAll(qrTags)

        // Also append QR content as searchable text so "upi" or "paytm" finds it
        val qrSearchText = qrPayloads.joinToString(" ") { payload ->
            // Extract readable parts from UPI URLs for search
            if (payload.startsWith("upi://")) {
                val params = Uri.parse(payload)
                listOfNotNull(
                    params.getQueryParameter("pn"),  // payee name
                    params.getQueryParameter("pa"),  // UPI ID
                    "upi payment qr scanner"
                ).joinToString(" ")
            } else {
                "$payload qr scanner barcode"
            }
        }

        val combinedText = if (ocrText.isNotBlank() && qrSearchText.isNotBlank()) {
            "$ocrText\n$qrSearchText"
        } else if (qrSearchText.isNotBlank()) {
            qrSearchText
        } else {
            ocrText
        }

        val tagSuffix = if (allTags.isNotEmpty()) "\n[${allTags.joinToString(" ")}]" else ""
        val finalOcrText = combinedText + tagSuffix

        val baseId = UUID.randomUUID().toString()
        val item   = VaultItem(
            id        = baseId,
            uri       = uri,
            ocrText   = finalOcrText,
            lang      = detectLang(ocrText),
            itemType  = itemType,
            timestamp = System.currentTimeMillis(),
            pHash     = hash
        )
        dao.insert(item)

        val searchableText = "$finalOcrText ${item.itemType} ${item.lang}"
        SearchEngineHolder.engine.addDocument(item.id, searchableText)

        val t2     = System.currentTimeMillis()
        val chunks = slidingWindowChunks(ocrText)

        if (!vectorSearch.initialized) {
            android.util.Log.w("IndexingPipeline", "VectorSearchManager not ready — initializing now")
            vectorSearch.initialize()
        }

        chunks.forEachIndexed { index, chunkText ->
            val vector  = AppEmbeddingEngine.embedPassage(context, chunkText)
            val chunkId = "${baseId}_chunk${index}"
            val added   = vectorSearch.indexVector(chunkId, vector)
            android.util.Log.d("IndexTiming", "Chunk $index indexed: $added")
        }

        vectorSearch.persistIdMappings()
        android.util.Log.d("IndexTiming", "Total: ${System.currentTimeMillis() - t0}ms")
    }

    // ════════════════════════════════════════════════════════════════════════
    // Aggressive multi-pass OCR
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Runs OCR with 5 strategies in parallel, merges all unique text found.
     *
     * Why: a single MLKit pass on the raw image misses:
     * - Stylized product fonts ("Good Day", "Mango Masti")
     * - Colored text on colored backgrounds
     * - Small text on packaging
     * - Curved/rotated text
     *
     * Each preprocessing variant makes different text visible to the OCR engine.
     * We run them all and merge unique lines.
     */
    private suspend fun runAggressiveOcr(bitmap: Bitmap): String = coroutineScope {
        // ── Strategy 1: Raw image (works for clean screenshots) ──────────
        val rawDeferred = async(Dispatchers.IO) {
            val image = InputImage.fromBitmap(bitmap, 0)
            val en = mlKitEn.process(image).await().text.trim()
            val hi = mlKitHi.process(image).await().text.trim()
            mergeTexts(en, hi)
        }

        // ── Strategy 2: High-contrast grayscale ─────────────────────────
        //    Strips color → reveals text hidden by colorful backgrounds
        val grayDeferred = async(Dispatchers.IO) {
            val gray = toHighContrastGrayscale(bitmap)
            try {
                val image = InputImage.fromBitmap(gray, 0)
                val en = mlKitEn.process(image).await().text.trim()
                val hi = mlKitHi.process(image).await().text.trim()
                mergeTexts(en, hi)
            } finally {
                gray.recycle()
            }
        }

        // ── Strategy 3: Inverted (white text on dark backgrounds) ───────
        val invertDeferred = async(Dispatchers.IO) {
            val inv = invertBitmap(bitmap)
            try {
                val image = InputImage.fromBitmap(inv, 0)
                mlKitEn.process(image).await().text.trim()
            } finally {
                inv.recycle()
            }
        }

        // ── Strategy 4: Upscaled 2× (small text on packaging) ──────────
        val upscaleDeferred = async(Dispatchers.IO) {
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                bitmap.width * 2,
                bitmap.height * 2,
                true
            )
            try {
                val gray = toHighContrastGrayscale(scaled)
                try {
                    val image = InputImage.fromBitmap(gray, 0)
                    mlKitEn.process(image).await().text.trim()
                } finally {
                    gray.recycle()
                }
            } finally {
                scaled.recycle()
            }
        }

        // ── Strategy 5: Tesseract on preprocessed image ─────────────────
        //    Tesseract sometimes catches what MLKit misses on noisy images
        val tessDeferred = async(Dispatchers.IO) {
            val gray = toHighContrastGrayscale(bitmap)
            try {
                runTesseract(gray).trim()
            } finally {
                gray.recycle()
            }
        }

        // ── Merge all results ───────────────────────────────────────────
        val results = listOf(
            rawDeferred.await(),
            grayDeferred.await(),
            invertDeferred.await(),
            upscaleDeferred.await(),
            tessDeferred.await()
        )

        val merged = mergeAllOcrResults(results)
        android.util.Log.d("OCR", "Strategies produced: ${results.map { it.length }} chars → merged: ${merged.length}")
        merged
    }

    /**
     * Merges OCR results from multiple passes.
     * Deduplicates at the line level — keeps unique lines from all strategies.
     * Returns the combined text sorted by line length (longest first = most info).
     */
    private fun mergeAllOcrResults(results: List<String>): String {
        val seenLines = mutableSetOf<String>()
        val uniqueLines = mutableListOf<String>()

        for (text in results) {
            if (text.isBlank()) continue
            for (line in text.split("\n")) {
                val cleaned = line.trim()
                if (cleaned.isEmpty()) continue
                val normalized = cleaned.lowercase().replace(Regex("\\s+"), " ")
                // Skip if we already have a line that contains this one (substring dedup)
                if (seenLines.any { it.contains(normalized) }) continue
                // Remove lines that are substrings of this new line
                seenLines.removeAll { normalized.contains(it) }
                seenLines.add(normalized)
                uniqueLines.add(cleaned)
            }
        }

        return uniqueLines.joinToString("\n")
    }

    /** Merges English and Hindi OCR results, picking the longer one or combining if both substantial */
    private fun mergeTexts(en: String, hi: String): String {
        if (en.isBlank()) return hi
        if (hi.isBlank()) return en
        if (en.length > hi.length * 2) return en
        if (hi.length > en.length * 2) return hi
        // Both substantial — combine unique lines
        val enLines = en.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val hiLines = hi.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val combined = (enLines + hiLines).distinct()
        return combined.joinToString("\n")
    }

    // ════════════════════════════════════════════════════════════════════════
    // Image preprocessing
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Converts to grayscale with boosted contrast.
     * Uses a ColorMatrix that:
     * 1. Converts to grayscale (removes color that confuses OCR)
     * 2. Increases contrast by 1.5× (makes faint text readable)
     * 3. Shifts brightness slightly to avoid washing out
     */
    private fun toHighContrastGrayscale(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        // Step 1: Grayscale
        val grayMatrix = ColorMatrix()
        grayMatrix.setSaturation(0f)

        // Step 2: Contrast boost (1.5×)
        val contrast = 1.5f
        val offset = (-128f * contrast) + 128f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, offset,
            0f, contrast, 0f, 0f, offset,
            0f, 0f, contrast, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))

        // Combine: first grayscale, then contrast
        grayMatrix.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(grayMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)

        return result
    }

    /**
     * Inverts colors — catches white/light text on dark backgrounds
     * that MLKit completely misses on the original.
     */
    private fun invertBitmap(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        val invertMatrix = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(invertMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)

        return result
    }

    // ════════════════════════════════════════════════════════════════════════
    // QR / Barcode scanning
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Scans the bitmap for QR codes, barcodes, and other 2D codes.
     * Returns a list of decoded payloads (URLs, UPI strings, plain text).
     *
     * Uses MLKit BarcodeScanning — detects ALL barcode formats.
     * Only uses [rawValue] which is available on all MLKit barcode versions.
     */
    private suspend fun scanBarcode(bitmap: Bitmap): List<String> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
            val barcodes = scanner.process(image).await()
            scanner.close()

            barcodes.mapNotNull { barcode ->
                barcode.rawValue
            }.filter { it.isNotBlank() }.distinct()
        } catch (e: Exception) {
            android.util.Log.e("IndexingPipeline", "Barcode scan failed: " + e.message)
            emptyList()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Existing helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun slidingWindowChunks(text: String): List<String> {
        val words = text.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.size <= CHUNK_SIZE) return listOf(text)

        val chunks   = mutableListOf<String>()
        val stepSize = CHUNK_SIZE - CHUNK_OVERLAP
        var start    = 0
        while (start < words.size) {
            val end = minOf(start + CHUNK_SIZE, words.size)
            chunks.add(words.subList(start, end).joinToString(" "))
            if (end == words.size) break
            start += stepSize
        }
        return chunks
    }

    private fun generateSmartTags(ocrText: String): String {
        val tags      = mutableListOf<String>()
        val textLower = ocrText.lowercase()
        val wordCount = ocrText.trim().split("\\s+".toRegex()).size
        val hasUrl    = textLower.contains("http") || textLower.contains("www.") || textLower.contains("upi://")
        if (wordCount <= 3 && hasUrl) tags.add("qr code scanner barcode")
        val hasAmount = textLower.contains("₹") || textLower.contains("rs") || textLower.contains("total") || textLower.contains("amount")
        if (hasAmount) tags.add("receipt payment bill invoice")
        val hasOtp = textLower.contains("otp") || textLower.contains("one time")
        if (hasOtp) tags.add("otp verification code")
        val hasPhone = Regex("[6-9]\\d{9}").containsMatchIn(ocrText)
        if (hasPhone) tags.add("contact phone number call")
        return tags.joinToString(" ")
    }

    private suspend fun runTesseract(bitmap: Bitmap): String {
        return tessMutex.withLock {
            try {
                tessApi.setImage(bitmap)
                val text = tessApi.utF8Text ?: ""
                tessApi.clear()
                text
            } catch (e: Exception) {
                e.printStackTrace()
                try { tessApi.clear() } catch (_: Exception) {}
                ""
            }
        }
    }

    private fun initTesseract(): TessBaseAPI {
        val tessDir = File(context.filesDir, TESS_DATA_DIR)
        tessDir.mkdirs()
        listOf("eng.traineddata", "hin.traineddata").forEach { filename ->
            val destFile = File(tessDir, filename)
            if (!destFile.exists()) {
                try {
                    context.assets.open("$TESS_DATA_DIR/$filename").use { input ->
                        FileOutputStream(destFile).use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        val api     = TessBaseAPI()
        val success = api.init(context.filesDir.absolutePath, "eng+hin")
        if (!success) android.util.Log.e("Tesseract", "Init failed")
        return api
    }

    private fun detectLang(text: String): String {
        val count = text.count { it.code in 0x0900..0x097F }
        return if (count > text.length * 0.2) "hi" else "en"
    }

    private fun computePHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val pixels = IntArray(64)
        scaled.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        val grays = pixels.map { p ->
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b =  p         and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }
        val avg  = grays.average()
        var hash = 0L
        grays.forEachIndexed { i, gray -> if (gray >= avg) hash = hash or (1L shl i) }
        scaled.recycle()
        return hash
    }
}