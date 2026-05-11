package com.amar.vault.agent.perception

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Accessibility service binding.
 *
 * Responsibilities:
 *   - Receive AccessibilityEvents from Android.
 *   - Drop events that don't pass [PackageWatchlist] OR aren't window-change events.
 *   - Debounce rapid-fire events from the same window.
 *   - Walk the accessibility tree on accepted events and publish [UiSnapshot] to cache.
 *   - Expose perform-actions API (click, scroll, set-text) for executors.
 *
 * What this is NOT:
 *   - Not an executor. Click/type/scroll live in executors/ that CALL this service.
 *   - Not a planner. It has no opinion about what the user wants, only what's on screen.
 *   - Not a permissions page. User enables the service via system Accessibility settings.
 *
 * Lifecycle:
 *   Android creates this when the user enables the service in Settings → Accessibility.
 *   The service receives a binding via onServiceConnected and runs for the life of the
 *   process (or until the user disables it). [singleton] is set in onServiceConnected
 *   so executors can grab a handle — Android doesn't let us inject it through Hilt
 *   directly since the service is instantiated by the system.
 *
 * IMPORTANT — Singleton pattern justification:
 *   AccessibilityService is instantiated by Android, not by our DI graph. The
 *   standard Android-approved pattern for wiring it into app code is a weak-ref
 *   singleton set in onServiceConnected. We use a plain @Volatile reference here
 *   for simplicity; the service's lifecycle is bounded by the process so leaks
 *   aren't a practical concern. If you want WeakReference wrapping, that's a
 *   drop-in change.
 */
class PerceptionService : AccessibilityService() {

    // Injected via bind() at onServiceConnected; set by Application class.
    @Volatile var snapshotCache: SnapshotCache = SnapshotCache()
        private set
    @Volatile var watchlist: PackageWatchlist = PackageWatchlist()
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val walkMutex = Mutex()

    private val _events = MutableSharedFlow<PerceptionEvent>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val events: SharedFlow<PerceptionEvent> = _events.asSharedFlow()

    // Debounce state — written only from the binder thread (onAccessibilityEvent).
    private var lastEventPackage: String? = null
    private var lastEventAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Tune the service info dynamically. The manifest declares baseline types;
        // we narrow here to what we actually use. Keeps the service from being
        // woken up for every text-change event on every app.
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            // notificationTimeout: coarsen rapid bursts from the system before we see them.
            notificationTimeout = 100
            // packageNames = null → watch all manifest-allowed packages. Dynamic
            // filtering happens in onAccessibilityEvent via watchlist.
            packageNames = null
        }

        singleton = this
        Log.i(TAG, "PerceptionService connected; watchlist=${watchlist.watched}")
    }

    override fun onDestroy() {
        super.onDestroy()
        singleton = null
        Log.i(TAG, "PerceptionService destroyed")
    }

    override fun onInterrupt() {
        // Required override. Called when service is forced to stop providing feedback.
        // We don't produce audio/haptic feedback, so this is a no-op.
    }

    /**
     * Bind external state (from the DI graph).
     *
     * Called from the Application class in onCreate to give the service access
     * to the app's shared SnapshotCache and PackageWatchlist instances. Must be
     * called BEFORE the service connects for the first time, or via [rebind]
     * if the service is already running.
     */
    fun bind(cache: SnapshotCache, watchlist: PackageWatchlist) {
        this.snapshotCache = cache
        this.watchlist = watchlist
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val pkg = event.packageName?.toString()
        if (!watchlist.allows(pkg)) {
            // Drop events from un-watched packages. Critical battery optimization.
            return
        }

        // Debounce: a single user action can fire dozens of CONTENT_CHANGED events
        // in quick succession. Coalesce to one walk per DEBOUNCE_MS window per package.
        val now = System.currentTimeMillis()
        val samePkg = (pkg == lastEventPackage)
        val withinWindow = (now - lastEventAt) < DEBOUNCE_MS
        if (samePkg && withinWindow && type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // STATE_CHANGED always wins (it's a new screen; force a walk).
            // CONTENT_CHANGED gets coalesced.
            return
        }
        lastEventPackage = pkg
        lastEventAt = now

        // Window change ⇒ invalidate first, then walk. Readers in between get
        // null (forces on-demand walk or stale-acceptance if their policy allows).
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            snapshotCache.invalidate()
        }

        val windowClass = event.className?.toString()
        scope.launch {
            walkAndPublish(
                packageId = pkg,
                windowClass = windowClass,
                reason = CaptureReason.WINDOW_CHANGE
            )
        }
    }

    /**
     * Force a fresh tree walk on demand. Executors call this before acting
     * when they need guaranteed fresh state (e.g., immediately after an
     * intent to open an app).
     *
     * Safe to call from any thread. Returns the resulting snapshot, or
     * [UiSnapshot.EMPTY] if the service has no window root.
     */
    suspend fun forceSnapshot(
        reason: CaptureReason = CaptureReason.ON_DEMAND
    ): UiSnapshot {
        return walkAndPublish(
            packageId = null,
            windowClass = null,
            reason = reason
        )
    }

    /**
     * Walks the tree under a mutex so concurrent events don't produce half-walked
     * snapshots. Publishes to cache on success.
     */
    private suspend fun walkAndPublish(
        packageId: String?,
        windowClass: String?,
        reason: CaptureReason
    ): UiSnapshot = walkMutex.withLock {
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            Log.w(TAG, "rootInActiveWindow threw: ${t.message}")
            null
        }

        if (root == null) {
            val empty = UiSnapshot.EMPTY.copy(
                packageId = packageId,
                capturedAt = System.currentTimeMillis(),
                captureReason = reason
            )
            snapshotCache.put(empty)
            _events.tryEmit(PerceptionEvent.SnapshotCaptured(empty))
            return@withLock empty
        }

        val snap = try {
            TreeWalker.walk(
                root = root,
                packageId = packageId ?: safePackage(root),
                windowClass = windowClass ?: safeClassName(root),
                captureReason = reason
            )
        } catch (t: Throwable) {
            Log.w(TAG, "TreeWalker threw: ${t.message}")
            UiSnapshot.EMPTY.copy(
                packageId = packageId,
                capturedAt = System.currentTimeMillis(),
                captureReason = reason
            )
        } finally {
            // Do NOT recycle the root — Android owns rootInActiveWindow's lifecycle
            // when we get it back; recycling it has caused crashes on some OEM builds.
        }

        snapshotCache.put(snap)
        _events.tryEmit(PerceptionEvent.SnapshotCaptured(snap))
        return@withLock snap
    }

    @Suppress("DEPRECATION")
    private fun safePackage(root: android.view.accessibility.AccessibilityNodeInfo): String? =
        try { root.packageName?.toString() } catch (t: Throwable) { null }

    private fun safeClassName(root: android.view.accessibility.AccessibilityNodeInfo): String? =
        try { root.className?.toString() } catch (t: Throwable) { null }

    companion object {
        private const val TAG = "PerceptionService"
        private const val DEBOUNCE_MS = 150L

        /**
         * Singleton accessor. `null` when the service isn't connected (user
         * hasn't enabled Accessibility yet, or system killed the service).
         * Executors must check for null before using.
         */
        @Volatile
        private var singleton: PerceptionService? = null

        fun get(): PerceptionService? = singleton

        /**
         * Rebind the service's shared state references. Useful if the app process
         * is re-created (config change, etc.) and the service was preserved.
         */
        fun rebind(cache: SnapshotCache, watchlist: PackageWatchlist) {
            singleton?.bind(cache, watchlist)
        }
    }
}

/**
 * Events the perception service emits to interested listeners (Brain, debug UI).
 */
sealed class PerceptionEvent {
    data class SnapshotCaptured(val snapshot: UiSnapshot) : PerceptionEvent()
}