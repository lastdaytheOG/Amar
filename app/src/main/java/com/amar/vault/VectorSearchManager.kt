package com.amar.vault

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class VectorSearchManager(private val context: Context) {

    companion object {
        const val VECTOR_DIM       = 1024
        const val MAX_ELEMENTS     = 100_000
        const val DEFAULT_K        = 20
        private const val MAPPINGS_FILENAME = "vector_mappings.json"

        @Volatile
        private var INSTANCE: VectorSearchManager? = null

        fun getInstance(context: Context): VectorSearchManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VectorSearchManager(context.applicationContext)
                    .also { INSTANCE = it }
            }
        }
    }

    private val engine   = NativeVectorEngine()
    private val idMapper = VectorIdMapper()

    @Volatile
    var initialized = false
        private set

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (initialized) return@withContext
        val storagePath = context.filesDir.absolutePath
        engine.initEngine(VECTOR_DIM, MAX_ELEMENTS, storagePath)
        restoreIdMappings()
        initialized = true
        android.util.Log.d("VectorSearch", "Initialized with ${engine.getCount()} vectors")
    }

    fun indexVector(vaultItemId: String, embedding: FloatArray): Boolean {
        if (!initialized) {
            android.util.Log.e("VectorSearch", "Not initialized — skipping indexVector for $vaultItemId")
            return false
        }
        if (embedding.size != VECTOR_DIM) {
            android.util.Log.e("VectorSearch", "Wrong dim: ${embedding.size}")
            return false
        }
        if (idMapper.contains(vaultItemId)) return false

        val numericId = idMapper.getOrCreateNumericId(vaultItemId)
        engine.addVector(numericId, embedding)
        return true
    }

    fun indexBatch(items: List<Pair<String, FloatArray>>) {
        for ((id, embedding) in items) indexVector(id, embedding)
    }

    fun search(queryEmbedding: FloatArray, k: Int = DEFAULT_K): List<String> {
        if (!initialized) {
            android.util.Log.e("VectorSearch", "Not initialized — search returning empty")
            return emptyList()
        }
        if (queryEmbedding.size != VECTOR_DIM) return emptyList()
        val numericIds = engine.search(queryEmbedding, k)
        android.util.Log.d("VectorSearch", "Native search returned ${numericIds.size} results")
        return numericIds.toList().mapNotNull { idMapper.getStringId(it) }
    }

    suspend fun saveState() = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext
        engine.saveToDisk()
        persistIdMappings()
        android.util.Log.d("VectorSearch", "Saved state: ${engine.getCount()} vectors")
    }

    fun destroy() {
        if (!initialized) return
        engine.destroyEngine()
        initialized = false
    }

    fun getIndexedCount(): Int = if (initialized) engine.getCount() else 0
    fun isIndexed(id: String): Boolean = idMapper.contains(id)

    suspend fun reindex(items: List<Pair<String, FloatArray>>) = withContext(Dispatchers.IO) {
        engine.initEngine(VECTOR_DIM, MAX_ELEMENTS, context.filesDir.absolutePath)
        idMapper.clear()
        indexBatch(items)
        engine.saveToDisk()
        persistIdMappings()
    }

    private val mappingsFile: File get() = File(context.filesDir, MAPPINGS_FILENAME)

    fun persistIdMappings() {
        val mappings = idMapper.exportMappings()
        val json     = JSONObject()
        for ((numId, strId) in mappings) json.put(numId.toString(), strId)

        val tempFile = File(context.filesDir, "${MAPPINGS_FILENAME}.tmp")
        try {
            tempFile.writeText(json.toString())
            val target = mappingsFile
            if (target.exists()) target.delete()
            tempFile.renameTo(target)
        } catch (e: Exception) {
            tempFile.delete()
        }
    }

    private fun restoreIdMappings() {
        val file = mappingsFile
        if (!file.exists()) return
        try {
            val json     = JSONObject(file.readText())
            val mappings = mutableMapOf<Int, String>()
            val keys     = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                mappings[key.toInt()] = json.getString(key)
            }
            idMapper.importMappings(mappings)
        } catch (e: Exception) {
            idMapper.clear()
            file.delete()
        }
    }
}