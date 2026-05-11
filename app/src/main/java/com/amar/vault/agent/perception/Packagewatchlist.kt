package com.amar.vault.agent.perception

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Dynamic whitelist of packages the Accessibility service currently watches.
 *
 * Why it exists:
 *   - Manifest-declared event filtering is static — once the service is installed,
 *     Android constrains which packages fire events, but we can't narrow this
 *     further at runtime through manifest alone.
 *   - This class adds a second runtime-controlled filter on top. When the
 *     whitelist is non-empty, events from packages outside it are DROPPED before
 *     the tree is walked. Enormous battery win when the service is enabled but
 *     idle (user is scrolling Instagram, we don't care).
 *   - When a task is dispatched that targets app X, we add X's package here.
 *     When the task completes, we remove it (unless ref-count > 0 from another
 *     concurrent task).
 *
 * Ref-counting:
 *   Two tasks might both target WhatsApp. We ref-count so removing one doesn't
 *   silence events the other needs.
 *
 * Empty-whitelist semantics:
 *   When the whitelist has no entries, we fall back to "watch everything the
 *   manifest allows". Otherwise a service with no active tasks would go blind.
 *   This is debatable — alternative is "watch nothing until a task arrives".
 *   Going with fall-back-to-all for v1; revisit if battery telemetry shows
 *   it's a problem.
 *
 * Threading:
 *   ConcurrentHashMap + StateFlow. Binder thread (AccessibilityService) reads,
 *   coroutine threads (ControlLayer pre-task hooks) write.
 */
class PackageWatchlist {

    private val refcounts = ConcurrentHashMap<String, Int>()
    private val _flow = MutableStateFlow<Set<String>>(emptySet())
    val flow: StateFlow<Set<String>> = _flow.asStateFlow()

    /** Current set of watched packages. Empty ⇒ watch all manifest-allowed. */
    val watched: Set<String>
        get() = _flow.value

    /**
     * Should the service process an event from [packageId]?
     * Returns true if the whitelist is empty (watch all) or contains the package.
     */
    fun allows(packageId: String?): Boolean {
        if (packageId.isNullOrBlank()) return true    // system / unknown — don't filter
        val w = _flow.value
        return w.isEmpty() || packageId in w
    }

    /**
     * Add [packageId] to the watchlist. Idempotent — increments refcount if
     * already present. Returns the new refcount.
     */
    fun acquire(packageId: String): Int {
        val newCount = refcounts.compute(packageId) { _, old -> (old ?: 0) + 1 }!!
        republish()
        return newCount
    }

    /**
     * Decrement refcount for [packageId]. Removes entirely when refcount hits 0.
     * Returns remaining refcount, or 0 if the package was not present.
     */
    fun release(packageId: String): Int {
        val newCount = refcounts.compute(packageId) { _, old ->
            val next = (old ?: 0) - 1
            if (next <= 0) null else next
        } ?: 0
        republish()
        return newCount
    }

    /** Force-clear the watchlist. Intended for tests and emergency UI reset. */
    fun clear() {
        refcounts.clear()
        _flow.value = emptySet()
    }

    private fun republish() {
        _flow.value = refcounts.keys().toList().toSet()
    }
}