package com.amar.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ════════════════════════════════════════════════════════════════════════════════
// Public API — Result types
// ════════════════════════════════════════════════════════════════════════════════

sealed interface IndexResult {
    data class Success(val fileName: String, val chunkCount: Int, val durationMs: Long) : IndexResult
    data class Duplicate(val fileName: String, val contentHash: String) : IndexResult
    data class Failure(val fileName: String, val error: IndexError) : IndexResult
}

sealed interface IndexError {
    data class UnsupportedFormat(val mimeType: String) : IndexError
    data class ExtractionFailed(val cause: Throwable) : IndexError
    object EmptyContent : IndexError
    data class StorageFailed(val cause: Throwable) : IndexError
}

data class IndexProgress(
    val fileName: String,
    val phase: Phase,
    val current: Int = 0,
    val total: Int = 0,
) {
    enum class Phase { EXTRACTING, CHUNKING, STORING, DONE }
    val fraction: Float get() = if (total > 0) current.toFloat() / total else 0f
}

// ════════════════════════════════════════════════════════════════════════════════
// DocumentIndexer — BM25-only for documents, zero embedding at index time
// ════════════════════════════════════════════════════════════════════════════════

class DocumentIndexer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DocumentIndexer"

        @Volatile
        private var instance: DocumentIndexer? = null

        fun getInstance(context: Context): DocumentIndexer =
            instance ?: synchronized(this) {
                instance ?: DocumentIndexer(context.applicationContext).also { instance = it }
            }

        val SUPPORTED_TYPES: Map<String, DocFamily> = mapOf(
            "application/pdf" to DocFamily.PDF,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to DocFamily.WORD,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to DocFamily.EXCEL,
            "application/epub+zip" to DocFamily.EPUB,
        )
    }

    enum class DocFamily(val tag: String, val itemType: String) {
        PDF("pdf document", "pdf"),
        WORD("word document", "word"),
        EXCEL("spreadsheet excel", "excel"),
        EPUB("ebook epub", "epub"),
    }

    private val dao by lazy { VaultDatabase.get(context).vaultDao() }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val bm25Mutex = Mutex()

    init { PDFBoxResourceLoader.init(context) }

    // ════════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════════

    suspend fun indexDocument(uri: Uri, mimeType: String): IndexResult =
        indexDocumentWithProgress(uri, mimeType).first

    suspend fun indexDocumentWithProgress(
        uri: Uri, mimeType: String,
    ): Pair<IndexResult, Flow<IndexProgress>> {
        val flow = MutableSharedFlow<IndexProgress>(replay = 1, extraBufferCapacity = 64)
        val result = withContext(Dispatchers.IO) { doIndex(uri, mimeType, flow) }
        return result to flow.asSharedFlow()
    }

    fun indexBatch(
        documents: List<Pair<Uri, String>>,
        concurrency: Int = 3,
    ): Flow<IndexResult> = channelFlow {
        val sem = Semaphore(concurrency)
        documents.forEach { (uri, mime) ->
            launch(Dispatchers.IO) { sem.withPermit { send(doIndex(uri, mime)) } }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Core pipeline — NO EMBEDDING, BM25 only
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun doIndex(
        uri: Uri, mimeType: String,
        progress: MutableSharedFlow<IndexProgress>? = null,
    ): IndexResult {
        val fileName = resolveFileName(uri)
        val startMs = System.currentTimeMillis()

        val family = SUPPORTED_TYPES[mimeType]
            ?: return IndexResult.Failure(fileName, IndexError.UnsupportedFormat(mimeType))

        val uriKey = uri.toString()
        if (!inFlight.add(uriKey)) return IndexResult.Duplicate(fileName, "in-flight")

        try {
            // ── 1. Extract pages (single read — no double read) ─────────
            progress?.emit(IndexProgress(fileName, IndexProgress.Phase.EXTRACTING))

            val pagedChunks = runCatching { extractPagedChunks(uri, family) }
                .getOrElse { return IndexResult.Failure(fileName, IndexError.ExtractionFailed(it)) }

            if (pagedChunks.isEmpty()) return IndexResult.Failure(fileName, IndexError.EmptyContent)

            // ── 2. Content-hash from chunks (no second PDF read) ────────
            val fullText = pagedChunks.joinToString("\n") { it.text }
            val contentHash = fullText.sha256()
            val existing = dao.findByContentHash(contentHash)
            if (existing != null) {
                Log.d(TAG, "Skipping duplicate: $fileName")
                return IndexResult.Duplicate(fileName, contentHash)
            }

            // ── 3. Store chunks + BM25 index (NO embedding) ─────────────
            progress?.emit(IndexProgress(fileName, IndexProgress.Phase.STORING))
            val baseId = UUID.randomUUID().toString()

            pagedChunks.forEach { pagedChunk ->
                val tags = TagEngine.generate(pagedChunk.text, family)
                val tagSuffix = if (tags.isNotEmpty()) "\n[${tags.joinToString(" ")}]" else ""
                val finalText = pagedChunk.text + tagSuffix

                val item = VaultItem(
                    id          = "${baseId}_chunk${pagedChunk.chunkIndex}",
                    uri         = uriKey,
                    ocrText     = finalText,
                    lang        = LanguageDetector.detect(pagedChunk.text),
                    itemType    = family.itemType,
                    pageNum     = pagedChunk.pdfPage ?: pagedChunk.chunkIndex,
                    sourceFile  = fileName,
                    timestamp   = System.currentTimeMillis(),
                    pHash       = 0L,
                    contentHash = contentHash,
                )

                dao.insert(item)

                bm25Mutex.withLock {
                    SearchEngineHolder.engine.addDocument(item.id, finalText)
                }

                progress?.emit(IndexProgress(fileName, IndexProgress.Phase.STORING,
                    pagedChunk.chunkIndex + 1, pagedChunks.size))

                // NO embedding here — documents use BM25 + substring search
                // Embedding happens at query time only for semantic queries
            }

            progress?.emit(IndexProgress(fileName, IndexProgress.Phase.DONE,
                pagedChunks.size, pagedChunks.size))

            val elapsed = System.currentTimeMillis() - startMs
            Log.d(TAG, "Indexed $fileName: ${pagedChunks.size} chunks in ${elapsed}ms (BM25 only, no embedding)")
            return IndexResult.Success(fileName, pagedChunks.size, elapsed)

        } catch (ce: CancellationException) { throw ce }
        catch (e: Exception) {
            Log.e(TAG, "Failed to index $fileName", e)
            return IndexResult.Failure(fileName, IndexError.StorageFailed(e))
        } finally {
            inFlight.remove(uriKey)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Text extraction
    // ════════════════════════════════════════════════════════════════════════

    data class PagedChunk(val text: String, val pdfPage: Int?, val chunkIndex: Int)

    private fun extractPagedChunks(uri: Uri, family: DocFamily): List<PagedChunk> =
        when (family) {
            DocFamily.PDF -> extractPdfPaged(uri)
            else -> {
                val text = when (family) {
                    DocFamily.WORD  -> extractWord(uri)
                    DocFamily.EXCEL -> extractExcel(uri)
                    DocFamily.EPUB  -> extractEpub(uri)
                    else -> ""
                }
                SentenceAwareChunker.chunk(text).mapIndexed { idx, chunk ->
                    PagedChunk(chunk, null, idx)
                }
            }
        }

    private fun extractPdfPaged(uri: Uri): List<PagedChunk> {
        val result = mutableListOf<PagedChunk>()
        var chunkIdx = 0
        openStream(uri).use { stream ->
            PDDocument.load(stream).use { doc ->
                val stripper = PDFTextStripper()
                for (page in 1..doc.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val pageText = stripper.getText(doc).trim()
                    if (pageText.isBlank()) continue
                    SentenceAwareChunker.chunk(pageText).forEach { chunk ->
                        result.add(PagedChunk(chunk, page, chunkIdx++))
                    }
                }
            }
        }
        return result
    }

    private fun extractWord(uri: Uri): String =
        openStream(uri).use { org.apache.poi.xwpf.extractor.XWPFWordExtractor(
            org.apache.poi.xwpf.usermodel.XWPFDocument(it)).text }

    private fun extractExcel(uri: Uri): String {
        val fmt = org.apache.poi.ss.usermodel.DataFormatter()
        return openStream(uri).use { buildExcelText(org.apache.poi.xssf.usermodel.XSSFWorkbook(it), fmt) }
    }

    private fun buildExcelText(wb: org.apache.poi.ss.usermodel.Workbook, fmt: org.apache.poi.ss.usermodel.DataFormatter) = buildString {
        wb.use { w -> for (s in w) { appendLine("═══ ${s.sheetName} ═══"); for (r in s) { val c = r.mapNotNull { fmt.formatCellValue(it).takeIf { v -> v.isNotBlank() } }; if (c.isNotEmpty()) appendLine(c.joinToString(" | ")) }; appendLine() } }
    }

    private fun extractEpub(uri: Uri): String = openStream(uri).use { stream ->
        val zipBytes = stream.readBytes()
        val fileMap = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val n = entry.name.lowercase()
                if (n.endsWith(".html") || n.endsWith(".xhtml") || n.endsWith(".htm") || n.endsWith(".opf"))
                    fileMap[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
        val opf = fileMap.entries.firstOrNull { it.key.endsWith(".opf") }?.value
        val paths = parseSpineOrder(opf, fileMap.keys)
        buildString { paths.forEach { p -> fileMap[p]?.let { val c = stripHtml(it); if (c.isNotBlank()) appendLine(c) } } }
    }

    private fun parseSpineOrder(opfContent: String?, allPaths: Set<String>): List<String> {
        val htmlFilter = { p: String -> val l = p.lowercase(); l.endsWith(".html") || l.endsWith(".xhtml") || l.endsWith(".htm") }
        if (opfContent == null) return allPaths.filter(htmlFilter).sorted()
        val manifest = mutableMapOf<String, String>()
        Regex("""<item\s[^>]*id="([^"]+)"[^>]*href="([^"]+)"[^>]*/?>""").findAll(opfContent).forEach { manifest[it.groupValues[1]] = it.groupValues[2] }
        val spineIds = Regex("""<itemref\s[^>]*idref="([^"]+)"[^>]*/?>""").findAll(opfContent).map { it.groupValues[1] }.toList()
        if (spineIds.isEmpty() || manifest.isEmpty()) return allPaths.filter(htmlFilter).sorted()
        val opfDir = allPaths.firstOrNull { it.endsWith(".opf") }?.substringBeforeLast("/", "")?.let { if (it.isNotEmpty()) "$it/" else "" } ?: ""
        return spineIds.mapNotNull { id -> manifest[id]?.let { href -> allPaths.firstOrNull { it == opfDir + href || it.endsWith(href) } } }
    }

    private fun stripHtml(html: String) = html
        .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), " ")
        .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), " ")
        .replace(Regex("<[^>]+>"), " ").replace(Regex("&\\w+;"), " ").replace(Regex("\\s+"), " ").trim()

    private fun openStream(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open $uri")

    // ════════════════════════════════════════════════════════════════════════
    // Chunker, Tags, Language, Utilities — unchanged
    // ════════════════════════════════════════════════════════════════════════

    object SentenceAwareChunker {
        private const val TARGET_WORDS = 200
        private const val OVERLAP_SENTENCES = 3
        private val SENTENCE_BOUNDARY = Regex("""(?<=[.!?])\s+(?=[A-Z\u0900-\u097F])|\n{2,}""")

        fun chunk(text: String): List<String> {
            val sentences = text.split(SENTENCE_BOUNDARY).filter { it.isNotBlank() }
            if (sentences.isEmpty()) return listOf(text)
            val chunks = mutableListOf<String>(); var i = 0
            while (i < sentences.size) {
                val window = mutableListOf<String>(); var wc = 0
                while (i < sentences.size && wc < TARGET_WORDS) { window.add(sentences[i]); wc += sentences[i].split(Regex("\\s+")).size; i++ }
                chunks.add(window.joinToString(" "))
                i = (i - OVERLAP_SENTENCES).coerceAtLeast(i - window.size + 1)
                if (chunks.size > 1 && i <= 0) break
            }
            return chunks.ifEmpty { listOf(text) }
        }
    }

    object TagEngine {
        private val CONTENT_RULES = listOf(
            listOf("invoice", "bill", "receipt") to listOf("invoice", "billing", "receipt"),
            listOf("contract", "agreement", "terms and conditions") to listOf("contract", "agreement", "legal"),
            listOf("₹", "$", "€", "amount", "total", "subtotal", "payment") to listOf("financial", "payment", "monetary"),
            listOf("resume", "curriculum vitae", "cv", "work experience") to listOf("resume", "cv", "career"),
            listOf("confidential", "private", "restricted") to listOf("confidential", "sensitive"),
            listOf("meeting", "minutes", "agenda", "attendees") to listOf("meeting", "minutes", "notes"),
            listOf("report", "analysis", "findings", "summary") to listOf("report", "analysis"),
            listOf("prescription", "diagnosis", "patient", "mg", "dosage") to listOf("medical", "health"),
            listOf("marks", "grade", "semester", "exam", "cgpa", "gpa") to listOf("academic", "education"),
            listOf("tax", "gst", "pan", "itr", "tds") to listOf("tax", "government"),
        )
        fun generate(text: String, family: DocFamily): List<String> {
            val lower = text.lowercase(); val tags = mutableListOf(family.tag)
            for ((kw, et) in CONTENT_RULES) { if (kw.any { it in lower }) tags.addAll(et) }
            return tags.distinct()
        }
    }

    object LanguageDetector {
        private data class Script(val code: String, val range: IntRange)
        private val SCRIPTS = listOf(Script("hi",0x0900..0x097F),Script("bn",0x0980..0x09FF),Script("ta",0x0B80..0x0BFF),Script("te",0x0C00..0x0C7F),Script("kn",0x0C80..0x0CFF),Script("ml",0x0D00..0x0D7F),Script("gu",0x0A80..0x0AFF),Script("pa",0x0A00..0x0A7F),Script("or",0x0B00..0x0B7F),Script("ar",0x0600..0x06FF),Script("zh",0x4E00..0x9FFF),Script("ja",0x3040..0x30FF),Script("ko",0xAC00..0xD7AF),Script("th",0x0E00..0x0E7F))
        fun detect(text: String): String {
            if (text.isEmpty()) return "en"
            val counts = mutableMapOf<String, Int>()
            for (ch in text) { for (s in SCRIPTS) { if (ch.code in s.range) { counts[s.code] = (counts[s.code] ?: 0) + 1; break } } }
            val dom = counts.maxByOrNull { it.value } ?: return "en"
            return if (dom.value >= text.length * 0.15) dom.key else "en"
        }
    }

    private fun resolveFileName(uri: Uri) = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (col >= 0 && c.moveToFirst()) c.getString(col) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "document"

    private fun String.sha256(): String {
        val d = MessageDigest.getInstance("SHA-256"); val h = d.digest(toByteArray(Charsets.UTF_8))
        return h.joinToString("") { "%02x".format(it) }
    }
}