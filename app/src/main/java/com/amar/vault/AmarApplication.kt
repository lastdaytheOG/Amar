package com.amar.vault

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltAndroidApp
class AmarApplication : Application() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            // 1. Copy ONNX model to storage
            withContext(Dispatchers.IO) {
                EmbeddingEngine.warmUp(applicationContext)
            }

            // 2. Load ONNX model into memory
            AppEmbeddingEngine.initialize(applicationContext)

            // 3. Initialize native C++ HNSW vector engine
            VectorSearchManager.getInstance(applicationContext).initialize()
            android.util.Log.d("AmarApp", "VectorSearchManager initialized")

            // 4. Initialize native C++ BM25 search engine + hydrate from Room
            withContext(Dispatchers.IO) {
                SearchEngineHolder.engine // triggers lazy init + initEngine()
                val items = VaultDatabase.get(applicationContext)
                    .vaultDao()
                    .getAllSearchableData()
                items.forEach { item ->
                    val text = "${item.ocrText} ${item.tags} ${item.itemType}"
                    SearchEngineHolder.engine.addDocument(item.id, text)
                }
                android.util.Log.d("AmarApp", "BM25 hydrated: ${items.size} docs")
            }

            // 5. Schedule nightly job
            NightlyIndexWorker.schedule(applicationContext)
        }
    }
}