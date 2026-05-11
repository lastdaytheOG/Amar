package com.amar.vault

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bidirectional mapper between hnswlib's integer IDs and VaultItem string UUIDs.
 *
 * hnswlib only accepts size_t (integer) IDs. This class maintains a thread-safe
 * mapping so the rest of the app can work with string UUIDs as usual.
 *
 * Thread safety: ConcurrentHashMap + AtomicInteger — lock-free for reads,
 * safe for concurrent writes.
 *
 * Persistence: This mapper should be backed by Room if you need it to survive
 * process death independently of the HNSW index. For most cases, rebuilding
 * the mapper from the same source data as the HNSW index is sufficient.
 *
 * Example flow:
 *   val numId = idMapper.getOrCreateNumericId("screenshot-uuid-8f7a")
 *   nativeEngine.addVector(numId, embeddingFloats)
 *   // ... later, on search ...
 *   val resultIds: IntArray = nativeEngine.search(queryVec, 10)
 *   val uuids: List<String> = resultIds.mapNotNull { idMapper.getStringId(it) }
 */
class VectorIdMapper {

    private val counter = AtomicInteger(0)

    // Int -> String (C++ ID -> VaultItem UUID)
    private val numericToString = ConcurrentHashMap<Int, String>()

    // String -> Int (VaultItem UUID -> C++ ID)
    private val stringToNumeric = ConcurrentHashMap<String, Int>()

    /**
     * Get or create a numeric ID for a string UUID.
     * If the UUID has been seen before, returns the existing numeric ID.
     * Otherwise, assigns the next available integer.
     */
    fun getOrCreateNumericId(stringId: String): Int {
        return stringToNumeric.computeIfAbsent(stringId) { _ ->
            val newId = counter.getAndIncrement()
            numericToString[newId] = stringId
            newId
        }
    }

    /**
     * Look up the string UUID for a C++ numeric ID.
     * Returns null if the ID is unknown (should not happen in normal operation).
     */
    fun getStringId(numericId: Int): String? {
        return numericToString[numericId]
    }

    /**
     * Look up the numeric ID for a string UUID.
     * Returns null if the UUID has never been mapped.
     */
    fun getNumericId(stringId: String): Int? {
        return stringToNumeric[stringId]
    }

    /**
     * Check if a string UUID has already been indexed.
     */
    fun contains(stringId: String): Boolean {
        return stringToNumeric.containsKey(stringId)
    }

    /**
     * Total number of mapped IDs.
     */
    val size: Int get() = numericToString.size

    /**
     * Clear all mappings (call alongside VectorEngine.init on full re-index).
     */
    fun clear() {
        numericToString.clear()
        stringToNumeric.clear()
        counter.set(0)
    }

    /**
     * Export all mappings (for persistence to Room or SharedPreferences).
     */
    fun exportMappings(): Map<Int, String> {
        return HashMap(numericToString)
    }

    /**
     * Import mappings (restore from Room or SharedPreferences on boot).
     * Sets the counter to max(existingIds) + 1 to avoid collisions.
     */
    fun importMappings(mappings: Map<Int, String>) {
        clear()
        var maxId = -1
        for ((numId, strId) in mappings) {
            numericToString[numId] = strId
            stringToNumeric[strId] = numId
            if (numId > maxId) maxId = numId
        }
        counter.set(maxId + 1)
    }
}
