package com.amar.vault

import android.app.Application
import com.amar.vault.agent.runtime.reducer.ReducerEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltAndroidApp
class AmarApplication : Application() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Inject lateinit var reducerEngine: ReducerEngine
    @Inject lateinit var accessibilityEventBus: com.amar.vault.agent.runtime.events.AccessibilityEventBus
    @Inject lateinit var imeCoordinator: com.amar.vault.agent.runtime.ime.ImeCoordinator
    @Inject lateinit var semanticBridge: com.amar.vault.agent.runtime.semantic.SemanticBridge
    @Inject lateinit var injectionMetrics: com.amar.vault.agent.runtime.metrics.InjectionMetrics
    @Inject lateinit var overlayDetector: com.amar.vault.agent.runtime.recovery.OverlayDetector
    @Inject lateinit var recoveryEngine: com.amar.vault.agent.runtime.recovery.RecoveryEngine
    @Inject lateinit var phaseOrchestrator: com.amar.vault.agent.runtime.orchestrator.PhaseOrchestrator

    override fun onCreate() {
        super.onCreate()
        android.util.Log.e("AmarApp", "ONCREATE_ENTERED reducerEngine=${if (::reducerEngine.isInitialized) "injected" else "NOT_INJECTED"}")

        // Step 3: start the WorldState reducer FIRST. It must be live before
        // any other initialization could publish events to the bus. The engine
        // launches its own collector scope; this call returns immediately.
        reducerEngine.start()

        // Step 6: start the IME coordinator and wire it into PerceptionService.
        imeCoordinator.start()
        com.amar.vault.agent.perception.PerceptionService.get()?.bindRuntime(
            bus = accessibilityEventBus,
            imeCoordinator = imeCoordinator
        )

        // Step 7: start semantic identity resolver bridge.
        semanticBridge.start()
        injectionMetrics.start()
        overlayDetector.start()
        recoveryEngine.start()
        phaseOrchestrator.start()

        // If the service isn't connected yet (user enables a11y later), bind
        // again when it connects. For now this no-ops gracefully.

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