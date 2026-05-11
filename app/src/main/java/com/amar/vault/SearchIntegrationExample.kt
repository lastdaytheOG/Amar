package com.amar.vault

/**
 * Example integration showing how to wire the NativeSearchEngine
 * into your existing VaultItem data flow.
 *
 * Copy the patterns below into your actual ViewModel / Application class.
 */

// --- Assume this is your existing data class ---
// data class VaultItem(
//     val id: String,
//     val ocrText: String,
//     val tags: List<String>,
//     val itemType: String
// )

object SearchIntegrationExample {

    private val searchEngine = NativeSearchEngine()

    /**
     * Call this once at app startup (Application.onCreate or ViewModel init).
     */
    fun initializeSearch() {
        searchEngine.initEngine()
    }

    /**
     * Call this whenever you receive new or updated VaultItems
     * (initial sync, incremental sync, or manual add).
     */
    fun indexVaultItem(item: VaultItem) {
        // Combine all searchable fields into one text block.
        // The C++ engine handles tokenization, n-gram generation, and scoring.
        val searchableText = buildString {
            append(item.ocrText)
            append(" ")
            append(item.tags)
            append(" ")
            append(item.itemType)
        }
        searchEngine.addDocument(item.id, searchableText)
    }

    /**
     * Bulk index — call on initial app boot after sync.
     */
    fun indexAll(items: List<VaultItem>) {
        for (item in items) {
            indexVaultItem(item)
        }
    }

    /**
     * Query the engine. Returns VaultItem IDs ranked by relevance.
     * Wire this to your search UI's text input.
     */
    fun search(query: String): List<String> {
        return searchEngine.search(query).toList()
    }

    /**
     * Full re-index (e.g., after a destructive sync).
     */
    fun reindexAll(items: List<VaultItem>) {
        searchEngine.clear()
        indexAll(items)
    }

    /**
     * CRITICAL: Call this in Application.onTerminate() or ViewModel.onCleared().
     * Failure to call this WILL leak the C++ heap allocation.
     */
    fun teardown() {
        searchEngine.destroyEngine()
    }
}

