package com.amar.vault.agent.runtime.adapters

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers and dispatches to the right [FrameworkAdapter] for a given
 * focused-node context.
 *
 * Resolution order:
 *   1. Exact package match (com.whatsapp, com.openai.chatgpt, etc.)
 *   2. Pattern match (Compose-class detection, Flutter-class detection)
 *   3. Null — engine uses generic behavior
 *
 * The registry is populated by Hilt multibinding: each adapter @Provides into
 * a Set<FrameworkAdapter>, the registry takes the set, and at lookup time
 * walks it in two passes (exact then pattern). Singleton.
 *
 * Why two passes:
 *   Package-specific adapters (WhatsApp) should win over framework-generic
 *   adapters (ComposeAdapter) when both could match. WhatsApp uses Compose
 *   internally for some screens, but the WhatsAppAdapter knows its semantics
 *   better than the generic ComposeAdapter, so it has priority.
 *
 * Why a registry instead of a `when()`:
 *   Adapters live in their own files (single-responsibility) and can be
 *   added without touching central code. Hilt multibinding makes adding a
 *   new adapter a one-line @IntoSet declaration.
 */
@Singleton
class FrameworkAdapterRegistry @Inject constructor(
    private val adapters: Set<@JvmSuppressWildcards FrameworkAdapter>
) {
    /**
     * Adapters registered at runtime (declarative manifests, third-party
     * packages). Separate from the Hilt-injected [adapters] set because
     * those are graph-built at app start; runtime ones are discovered
     * AFTER the graph is constructed.
     */
    private val runtimeAdapters = java.util.concurrent.CopyOnWriteArrayList<FrameworkAdapter>()

    /**
     * Register an adapter discovered at runtime. Idempotent.
     */
    fun registerRuntimeAdapter(adapter: FrameworkAdapter) {
        if (runtimeAdapters.any { it === adapter }) return
        runtimeAdapters.add(adapter)
        Log.i(TAG, "REGISTERED_RUNTIME name=${adapter.adapterName} " +
                "packages=${adapter.packageIds.joinToString(",")}")
    }

    /**
     * Find the adapter that matches the given context. Returns null if no
     * adapter applies — caller should fall back to generic engine behavior.
     */
    fun adapterFor(
        packageId: String?,
        className: String? = null,
        resourceId: String? = null
    ): FrameworkAdapter? {
        if (packageId.isNullOrEmpty()) return null

        // Search Hilt-injected adapters first, then runtime-registered.
        // Hilt-injected always wins on ties — they're the trusted built-ins.
        val allAdapters = adapters + runtimeAdapters

        // Pass 1: exact package match.
        allAdapters.firstOrNull { packageId in it.packageIds }?.let {
            Log.i(TAG, "RESOLVED_ADAPTER kind=exact pkg=$packageId adapter=${it.adapterName}")
            return it
        }

        // Pass 2: pattern match (framework-level adapters).
        allAdapters.firstOrNull { it.matches(packageId, className, resourceId) }?.let {
            Log.i(TAG, "RESOLVED_ADAPTER kind=pattern pkg=$packageId cls=$className adapter=${it.adapterName}")
            return it
        }

        return null
    }

    /**
     * Diagnostic helper: list all registered adapters.
     */
    fun all(): Set<FrameworkAdapter> = adapters + runtimeAdapters.toSet()

    companion object {
        private const val TAG = "AdapterRegistry"
    }
}