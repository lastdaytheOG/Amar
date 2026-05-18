package com.amar.vault.agent

import com.amar.vault.agent.perception.PackageWatchlist
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.SnapshotCache
import com.amar.vault.agent.control.AgentPhase
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide holder for dependencies that need to reach across the Hilt/Android
 * boundary.
 *
 * Why this exists:
 *   [PerceptionService] is an [android.accessibilityservice.AccessibilityService],
 *   instantiated by the Android system — NOT by our Hilt DI graph. To share the
 *   same [SnapshotCache] and [PackageWatchlist] instances between the service and
 *   the rest of the app (Control Layer, ViewModels, etc.), we need a mutable
 *   singleton accessible from both worlds.
 *
 *   This is the narrowest, least-magical way to bridge the gap. Alternatives
 *   considered and rejected:
 *     - EntryPoint injection from the service: works but requires ServiceScoped
 *       setup and careful lifecycle — overkill for two objects.
 *     - Object singleton with lateinit: breaks cleanly in tests that don't init.
 *     - ContextCompat.getSystemService-style lookup: no such pattern for our own services.
 *
 * Lifecycle:
 *   - Application.onCreate calls [init] exactly once.
 *   - Accessibility service (if enabled) calls [snapshotCache] / [watchlist]
 *     getters after onServiceConnected. If init hasn't happened yet (possible
 *     on cold start when service is enabled pre-Application), we return
 *     transient defaults and rebind at init time.
 *   - Tests call [resetForTest] between test cases.
 *
 * Thread-safety:
 *   AtomicReference reads/writes. No coordination needed — the references are
 *   set once and read many times.
 */
object AgentStateHolder {

    private val cacheRef = AtomicReference<SnapshotCache>(SnapshotCache())
    private val watchlistRef = AtomicReference<PackageWatchlist>(PackageWatchlist())

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    var phase: AgentPhase = AgentPhase.NAVIGATION

    val snapshotCache: SnapshotCache
        get() = cacheRef.get()

    val watchlist: PackageWatchlist
        get() = watchlistRef.get()

    /**
     * Initialize with canonical instances. Call from Application.onCreate.
     * If the service was already bound to transient defaults, rebind it to
     * the canonical instances.
     */
    @Synchronized
    fun init(cache: SnapshotCache, watchlist: PackageWatchlist) {
        cacheRef.set(cache)
        watchlistRef.set(watchlist)
        initialized = true
        // If the service is already running (e.g., process-restart with
        // accessibility enabled), swap its references to the new ones.
        PerceptionService.rebind(cache, watchlist)
    }

    fun isInitialized(): Boolean = initialized

    /**
     * Reset to fresh defaults. For test use only.
     * Production code must never call this — it would invalidate every
     * TaskContext currently holding references.
     */
    internal fun resetForTest() {
        cacheRef.set(SnapshotCache())
        watchlistRef.set(PackageWatchlist())
        initialized = false
    }
}