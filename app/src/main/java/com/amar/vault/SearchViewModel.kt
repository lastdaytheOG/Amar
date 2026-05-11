package com.amar.vault

import android.content.Context
import androidx.collection.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val db: VaultDatabase,
    private val vectorSearchManager: VectorSearchManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val query           = MutableStateFlow("")
    val isSearchLoading = MutableStateFlow(false)

    private val embeddingCache = object : LruCache<String, FloatArray>(30) {}
    private val engine by lazy { AppEmbeddingEngine.get(context) }

    val allItems: StateFlow<List<VaultItem>> = db.vaultDao()
        .getAllItems()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var cachedAllItems: List<VaultItem>? = null

    private suspend fun getAllItemsSnapshot(): List<VaultItem> {
        cachedAllItems?.let { if (it.isNotEmpty()) return it }
        val items = db.vaultDao().getAllItems().first()
        cachedAllItems = items
        return items
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (!vectorSearchManager.initialized) vectorSearchManager.initialize()
            getAllItemsSnapshot()
            android.util.Log.d("SearchVM", "VectorSearch ready: ${vectorSearchManager.getIndexedCount()} vectors")
        }
        viewModelScope.launch { db.vaultDao().getAllItems().collect { cachedAllItems = it } }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<VaultItem>> = query
        .debounce(250)
        .map { it.trim() }
        .distinctUntilChanged()
        .transformLatest { q ->
            if (q.isBlank()) { isSearchLoading.value = false; emit(emptyList()); return@transformLatest }
            isSearchLoading.value = true
            try { yield(); emit(hybridSearch(q)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("SearchVM", "Search failed", e); emit(emptyList()) }
            finally { isSearchLoading.value = false }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ════════════════════════════════════════════════════════════════════════
    // Hybrid search — 4 lanes + late embedding for semantic doc queries
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun hybridSearch(q: String): List<VaultItem> = coroutineScope {
        val queryType = classifyQuery(q)
        val qLower = q.lowercase().trim()

        // ── 1. BM25 ─────────────────────────────────────────────────────
        val bm25Deferred = async(Dispatchers.IO) {
            try {
                val ids = SearchEngineHolder.engine.search(q).toList()
                if (ids.isNotEmpty()) db.vaultDao().getByIds(ids) else emptyList()
            } catch (e: Exception) {
                android.util.Log.e("SearchVM", "BM25 error: " + e.message)
                emptyList()
            }
        }

        // ── 2. HNSW vector search (images only — docs have no vectors) ──
        val vectorDeferred = async(Dispatchers.IO) {
            if (queryType == QueryType.GIBBERISH) return@async emptyList<VaultItem>()
            if (qLower.length < 4) return@async emptyList<VaultItem>()
            if (!vectorSearchManager.initialized) return@async emptyList<VaultItem>()

            val queryVec = synchronized(embeddingCache) { embeddingCache.get(q) }
                ?: engine.embed(q).also { synchronized(embeddingCache) { embeddingCache.put(q, it) } }

            ensureActive()
            val ids = vectorSearchManager.search(queryVec, 50)
            if (ids.isEmpty()) return@async emptyList<VaultItem>()
            db.vaultDao().getByIds(ids)
        }

        // ── 3. Substring scan ───────────────────────────────────────────
        val substringDeferred = async(Dispatchers.IO) {
            if (qLower.length < 2) return@async emptyList<VaultItem>()
            try {
                val items = getAllItemsSnapshot()
                val words = qLower.split(Regex("\\s+")).filter { it.isNotEmpty() }
                items.filter { item ->
                    val text = item.ocrText.lowercase()
                    words.any { w -> text.contains(w) }
                }.sortedByDescending { item ->
                    val text = item.ocrText.lowercase()
                    words.count { w -> text.contains(w) }
                }.take(50)
            } catch (e: Exception) {
                android.util.Log.e("SearchVM", "Substring error: " + e.message)
                emptyList()
            }
        }

        // ── 4. Fuzzy scan ───────────────────────────────────────────────
        val fuzzyDeferred = async(Dispatchers.IO) {
            if (qLower.length < 4) return@async emptyList<VaultItem>()
            try {
                val items = getAllItemsSnapshot()
                val words = qLower.split(Regex("\\s+")).filter { it.length >= 3 }
                if (words.isEmpty()) return@async emptyList<VaultItem>()
                items.filter { item ->
                    val docWords = item.ocrText.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }
                    words.any { qw ->
                        val md = if (qw.length <= 4) 1 else 2
                        docWords.any { dw -> kotlin.math.abs(dw.length - qw.length) <= md && levenshtein(qw, dw) <= md }
                    }
                }.take(30)
            } catch (e: Exception) {
                android.util.Log.e("SearchVM", "Fuzzy error: " + e.message)
                emptyList()
            }
        }

        // ── Await all lanes ─────────────────────────────────────────────
        val bm25Results = bm25Deferred.await()
        val vectorResults = vectorDeferred.await()
        val substringResults = substringDeferred.await()
        val fuzzyResults = fuzzyDeferred.await()

        // Debug logging
        android.util.Log.d("SearchVM", "=== Search: '$q' ===")
        android.util.Log.d("SearchVM", "BM25: ${bm25Results.size} | Vector: ${vectorResults.size} | Substring: ${substringResults.size} | Fuzzy: ${fuzzyResults.size}")

        if (bm25Results.isEmpty() && vectorResults.isEmpty() &&
            substringResults.isEmpty() && fuzzyResults.isEmpty()) {
            return@coroutineScope emptyList()
        }

        // ── 5. Late embedding for semantic document queries ─────────────
        //
        // Documents have NO pre-computed vectors (embedding skipped at index time).
        // For semantic queries (multi-word phrases), we embed the query + top BM25
        // doc candidates NOW and re-rank by cosine similarity.
        // This is fast because we only embed ~5-10 candidates, not 40+ chunks.
        //
        val semanticDocBoosts = mutableMapOf<String, Double>()

        if (queryType == QueryType.SEMANTIC_PHRASE && qLower.length >= 6) {
            val docTypes = setOf("pdf", "word", "excel", "epub")

            // Get top document results from BM25 + substring (already fetched)
            val docCandidates = (bm25Results + substringResults)
                .filter { it.itemType in docTypes }
                .distinctBy { it.id }
                .take(10) // only embed top 10 — not all chunks

            if (docCandidates.isNotEmpty()) {
                try {
                    val queryVec = synchronized(embeddingCache) { embeddingCache.get(q) }
                        ?: engine.embed(q).also { synchronized(embeddingCache) { embeddingCache.put(q, it) } }

                    // Embed each candidate's text and compute cosine similarity
                    docCandidates.forEach { item ->
                        ensureActive()
                        val chunkText = item.ocrText.substringBefore("\n[").trim()
                        if (chunkText.length >= 20) {
                            val docVec = engine.embed(chunkText)
                            val sim = cosineSimilarity(queryVec, docVec)
                            if (sim > 0.3) { // only boost if genuinely similar
                                semanticDocBoosts[item.id] = sim.toDouble()
                            }
                        }
                    }
                    android.util.Log.d("SearchVM", "Late embedding: ${docCandidates.size} docs, ${semanticDocBoosts.size} boosted")
                } catch (e: Exception) {
                    android.util.Log.e("SearchVM", "Late embedding error: " + e.message)
                }
            }
        }

        // ── 6. Reciprocal Rank Fusion ───────────────────────────────────
        val rrfK = 60.0
        val weights = mapOf("bm25" to 1.2, "vector" to 1.0, "substring" to 1.1, "fuzzy" to 0.6)
        val rrfScores = mutableMapOf<String, Double>()
        val itemMap = mutableMapOf<String, VaultItem>()

        fun scoreLane(results: List<VaultItem>, lane: String) {
            val w = weights[lane] ?: 1.0
            results.forEachIndexed { rank, item ->
                itemMap[item.id] = item
                rrfScores[item.id] = (rrfScores[item.id] ?: 0.0) + w * (1.0 / (rrfK + rank))
            }
        }

        scoreLane(bm25Results, "bm25")
        scoreLane(vectorResults, "vector")
        scoreLane(substringResults, "substring")
        scoreLane(fuzzyResults, "fuzzy")

        // Apply late semantic boosts for documents
        for ((id, sim) in semanticDocBoosts) {
            rrfScores[id] = (rrfScores[id] ?: 0.0) + sim * 0.5 // weight semantic signal
        }

        // ── 7. Text-presence gating ─────────────────────────────────────
        val bm25Ids = bm25Results.map { it.id }.toSet()
        val substringIds = substringResults.map { it.id }.toSet()
        val fuzzyIds = fuzzyResults.map { it.id }.toSet()
        val queryWords = qLower.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val toRemove = mutableListOf<String>()

        for ((id, item) in itemMap) {
            val text = item.ocrText.lowercase()
            val matchedWords = queryWords.count { text.contains(it) }

            if (matchedWords > 0) {
                if (text.contains(qLower)) rrfScores[id] = (rrfScores[id] ?: 0.0) + 0.10
                val ratio = matchedWords.toDouble() / queryWords.size
                rrfScores[id] = (rrfScores[id] ?: 0.0) + 0.06 * ratio
            } else {
                val inTextLane = id in bm25Ids || id in substringIds || id in fuzzyIds
                if (!inTextLane) toRemove.add(id)
            }
        }

        toRemove.forEach { itemMap.remove(it); rrfScores.remove(it) }

        // ── 8. Sort and return ──────────────────────────────────────────
        itemMap.entries.sortedByDescending { rrfScores[it.key] ?: 0.0 }.map { it.value }.take(50)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Cosine similarity
    // ════════════════════════════════════════════════════════════════════════

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }

    // ════════════════════════════════════════════════════════════════════════
    // Levenshtein
    // ════════════════════════════════════════════════════════════════════════

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0; if (a.isEmpty()) return b.length; if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }; var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) { val c = if (a[i-1] == b[j-1]) 0 else 1; curr[j] = minOf(prev[j]+1, curr[j-1]+1, prev[j-1]+c) }
            val t = prev; prev = curr; curr = t
        }
        return prev[b.length]
    }

    // ════════════════════════════════════════════════════════════════════════
    // Query classification
    // ════════════════════════════════════════════════════════════════════════

    private fun classifyQuery(q: String): QueryType {
        if (q.matches(Regex("^[^a-zA-Z\\u0900-\\u097F]+$"))) return QueryType.GIBBERISH
        if (q.length == 1) return QueryType.EXACT_KEYWORD
        if (q.length < 6 && q.matches(Regex("^[a-zA-Z]{1,3}\\d+.*$"))) return QueryType.GIBBERISH
        return if (q.contains(" ")) QueryType.SEMANTIC_PHRASE else QueryType.EXACT_KEYWORD
    }

    private enum class QueryType { EXACT_KEYWORD, SEMANTIC_PHRASE, GIBBERISH }

    fun updateQuery(newQuery: String) { query.value = newQuery }
}