package com.amar.vault.agent.perception

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.events.AgentEvent
import com.amar.vault.agent.runtime.ime.ImeCoordinator
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

    // Step 6: nullable so legacy path keeps working until bind() is called.
    @Volatile var bus: AccessibilityEventBus? = null
        private set

    @Volatile var imeCoordinator: ImeCoordinator? = null
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
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED

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
        // Step 6: bind runtime dependencies via Hilt EntryPoint. Idempotent —
        // if AmarApplication already called bindRuntime, this will overwrite
        // with the same singleton instances (harmless).
        try {
            val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(
                applicationContext,
                PerceptionServiceEntryPoint::class.java
            )
            bindRuntime(
                bus = entry.accessibilityEventBus(),
                imeCoordinator = entry.imeCoordinator()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to fetch runtime deps via Hilt EntryPoint: ${t.message}")
        }
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

    /**
     * Step 6 wiring: bind bus + IME coordinator. Called separately from bind()
     * to keep legacy bind() callers working without modification.
     * Safe to call before or after the service connects.
     */
    fun bindRuntime(bus: AccessibilityEventBus, imeCoordinator: ImeCoordinator) {
        this.bus = bus
        this.imeCoordinator = imeCoordinator
        Log.i(TAG, "PERCEPTION_RUNTIME_BOUND bus=true ime=true")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val type = event.eventType

        // Step 6: publish ALL relevant accessibility events to the bus.
        // This runs in PARALLEL with the legacy snapshot pipeline below —
        // bus consumers (Reducer → WorldState) get rich event data while
        // existing executors continue to use snapshotCache as before.
        publishToBus(event, type)

        // Legacy guard: only state/content changes drive snapshot walks.
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

    private suspend fun walkAndPublish(
        packageId: String?,
        windowClass: String?,
        reason: CaptureReason
    ): UiSnapshot = walkMutex.withLock {

        val generationId = RootGeneration.increment()
        Log.i(TAG, "ROOT_GENERATION_INCREMENT gen=$generationId reason=$reason")

        // Gather all windows with rich metadata.
        val windowInfos: List<android.view.accessibility.AccessibilityWindowInfo> = try {
            windows ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "windows enumeration threw: ${t.message}")
            emptyList()
        }

        // Determine target package FIRST, before filtering.
        // Priority: explicit caller-supplied → focused application window → active
        // application window → first application window → rootInActiveWindow fallback.
        val targetPkg: String? = packageId
            ?: pickForegroundAppPackage(windowInfos)
            ?: try { rootInActiveWindow?.packageName?.toString() } catch (t: Throwable) { null }

        // Select windows to walk: ALL application + IME windows whose root package
        // matches OR which are the focused window. We exclude TYPE_SYSTEM (status bar,
        // nav bar) which would pollute the snapshot. We INCLUDE the IME window so
        // executors that look for "is keyboard up" still see it.
        val rootsToWalk: List<android.view.accessibility.AccessibilityNodeInfo> =
            if (windowInfos.isEmpty()) {
                // No window list — fall back to single root.
                listOfNotNull(safeRoot { rootInActiveWindow })
            } else {
                windowInfos
                    .sortedByDescending { it.layer }
                    .filter { w ->
                        val type = w.type
                        type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                                type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                                type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ||
                                w.isFocused || w.isActive
                    }
                    .mapNotNull { w -> safeRoot { w.root } }
                    .filter { root ->
                        // Keep roots whose package matches the foreground app.
                        // Also keep "anonymous" roots (null package) — happens with
                        // some overlay popups that don't tag themselves.
                        val pkg = try { root.packageName?.toString() } catch (t: Throwable) { null }
                        pkg == null || pkg == targetPkg || targetPkg == null
                    }
            }

        if (rootsToWalk.isEmpty()) {
            val empty = UiSnapshot.EMPTY.copy(
                packageId = targetPkg,
                capturedAt = System.currentTimeMillis(),
                captureReason = reason
            )

            snapshotCache.put(empty)
            _events.tryEmit(PerceptionEvent.SnapshotCaptured(empty))
            return@withLock empty
        }

        Log.i(
            TAG,
            "walkAndPublish: ${windowInfos.size} windows total, " +
                    "${rootsToWalk.size} roots walking, pkg=$targetPkg"
        )

        // Walk every selected root, merge elements.
        val allElements = mutableListOf<UiElement>()
        var anyTruncated = false
        var firstWindowClass: String? = windowClass

        for ((idx, root) in rootsToWalk.withIndex()) {

            if (firstWindowClass == null && idx == 0) {
                firstWindowClass = safeClassName(root)
            }

            val partial = try {
                TreeWalker.walk(
                    root = root,
                    packageId = targetPkg,
                    windowClass = safeClassName(root),
                    captureReason = reason
                )
            } catch (t: Throwable) {
                Log.w(TAG, "TreeWalker threw on window $idx: ${t.message}")
                null
            }

            if (partial != null) {
                allElements.addAll(partial.elements)
                anyTruncated = anyTruncated || partial.truncated
            }
        }

        val capped = if (allElements.size > UiSnapshot.MAX_ELEMENTS) {
            anyTruncated = true
            allElements.take(UiSnapshot.MAX_ELEMENTS)
        } else {
            allElements
        }

        val snap = UiSnapshot(
            packageId = targetPkg,
            windowClass = firstWindowClass,
            capturedAt = System.currentTimeMillis(),
            elements = capped,
            truncated = anyTruncated,
            captureReason = reason
        )

        snapshotCache.put(snap)
        _events.tryEmit(PerceptionEvent.SnapshotCaptured(snap))
        return@withLock snap
    }

    /**
     * Pick the package name of the actual foreground application window,
     * ignoring system UI (status bar, nav bar) and IMEs.
     *
     * Selection order:
     *   1. The focused application window (TYPE_APPLICATION + isFocused).
     *   2. The active application window (TYPE_APPLICATION + isActive).
     *   3. Any application window, sorted by layer descending.
     */
    private fun pickForegroundAppPackage(
        windowInfos: List<android.view.accessibility.AccessibilityWindowInfo>
    ): String? {

        val appWindows = windowInfos.filter {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
        }

        if (appWindows.isEmpty()) return null

        val focused = appWindows.firstOrNull { it.isFocused }
        val active = appWindows.firstOrNull { it.isActive }
        val anyTop = appWindows.maxByOrNull { it.layer }

        val chosen = focused ?: active ?: anyTop ?: return null

        return try {
            chosen.root?.packageName?.toString()
        } catch (t: Throwable) {
            null
        }
    }

    private inline fun safeRoot(
        block: () -> android.view.accessibility.AccessibilityNodeInfo?
    ): android.view.accessibility.AccessibilityNodeInfo? =
        try {
            block()
        } catch (t: Throwable) {
            null
        }

    /**
     * Step 6: translate a raw AccessibilityEvent into a typed AgentEvent
     * and publish to the bus. Best-effort — any failure logs a warning and
     * does not affect the legacy snapshot pipeline.
     *
     * IME detection:
     *   The soft input window is identified by checking the windows() list
     *   for a TYPE_INPUT_METHOD window. We do this on every event so the
     *   reducer sees ImeVisibilityChanged immediately when the keyboard
     *   shows or hides.
     */
    private fun publishToBus(event: AccessibilityEvent, type: Int) {
        val b = bus ?: return

        val pkg = try {
            event.packageName?.toString()
        } catch (t: Throwable) {
            null
        }

        val cls = try {
            event.className?.toString()
        } catch (t: Throwable) {
            null
        }

        try {
            when (type) {

                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    b.publish(
                        AgentEvent.Accessibility.WindowStateChanged(
                            packageId = pkg,
                            windowClass = cls
                        )
                    )
                    publishImeVisibility(b)
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    b.publish(
                        AgentEvent.Accessibility.WindowContentChanged(
                            packageId = pkg
                        )
                    )
                }

                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                    val pkgs = try {
                        windows?.mapNotNull { w ->
                            try {
                                w.root?.packageName?.toString()
                            } catch (t: Throwable) {
                                null
                            }
                        } ?: emptyList()
                    } catch (t: Throwable) {
                        emptyList()
                    }

                    b.publish(
                        AgentEvent.Accessibility.WindowsChanged(
                            packageIds = pkgs
                        )
                    )

                    publishImeVisibility(b)
                }

                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    val src: AccessibilityNodeInfo? = try {
                        event.source
                    } catch (t: Throwable) {
                        null
                    }

                    val resourceId = try {
                        src?.viewIdResourceName
                    } catch (t: Throwable) {
                        null
                    }

                    val className = try {
                        src?.className?.toString()
                    } catch (t: Throwable) {
                        null
                    }

                    val isEditable = try {
                        src?.isEditable == true
                    } catch (t: Throwable) {
                        false
                    }

                    b.publish(
                        AgentEvent.Accessibility.ViewFocused(
                            packageId = pkg,
                            resourceId = resourceId,
                            className = className,
                            isEditable = isEditable
                        )
                    )

                    try {
                        src?.recycle()
                    } catch (_: Throwable) {
                    }
                }

                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val src: AccessibilityNodeInfo? = try {
                        event.source
                    } catch (t: Throwable) {
                        null
                    }

                    val resourceId = try {
                        src?.viewIdResourceName
                    } catch (t: Throwable) {
                        null
                    }

                    val before = try {
                        event.beforeText?.toString()
                    } catch (t: Throwable) {
                        null
                    }

                    val after = try {
                        event.text?.joinToString(separator = "")
                            ?.takeIf { it.isNotEmpty() }
                    } catch (t: Throwable) {
                        null
                    }

                    b.publish(
                        AgentEvent.Accessibility.TextChanged(
                            packageId = pkg,
                            resourceId = resourceId,
                            beforeText = before,
                            afterText = after
                        )
                    )

                    try {
                        src?.recycle()
                    } catch (_: Throwable) {
                    }
                }

                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                    val src: AccessibilityNodeInfo? = try {
                        event.source
                    } catch (t: Throwable) {
                        null
                    }

                    val resourceId = try {
                        src?.viewIdResourceName
                    } catch (t: Throwable) {
                        null
                    }

                    val start = try {
                        event.fromIndex
                    } catch (t: Throwable) {
                        -1
                    }

                    val end = try {
                        event.toIndex
                    } catch (t: Throwable) {
                        -1
                    }

                    b.publish(
                        AgentEvent.Accessibility.TextSelectionChanged(
                            packageId = pkg,
                            resourceId = resourceId,
                            selectionStart = start,
                            selectionEnd = end
                        )
                    )

                    imeCoordinator?.onTextSelectionChanged(
                        pkg,
                        resourceId,
                        System.currentTimeMillis()
                    )

                    try {
                        src?.recycle()
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "publishToBus failed for type=$type: ${t.message}")
        }
    }

    /**
     * Step 6: emit ImeVisibilityChanged by inspecting current windows().
     * Called on WindowStateChanged and WindowsChanged because that's when
     * the IME window appears or disappears.
     */
    private fun publishImeVisibility(b: AccessibilityEventBus) {
        val imeVisible = try {
            windows?.any { w ->
                try {
                    w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                } catch (t: Throwable) {
                    false
                }
            } ?: false
        } catch (t: Throwable) {
            false
        }

        b.publish(
            AgentEvent.Runtime.ImeVisibilityChanged(
                visible = imeVisible
            )
        )
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