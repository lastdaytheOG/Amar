package com.amar.vault.agent.perception

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * PERCEPTION DIAGNOSTIC MODE.
 *
 * Dumps comprehensive perception state for debugging catastrophically
 * undersized snapshots (e.g., elements=4 for WhatsApp when 50-300 are expected).
 *
 * Potential causes for undersized snapshots:
 *   - Traversal prematurely stopping
 *   - Visibility pruning too aggressive
 *   - Compose semantics skipped
 *   - Stale root reads
 *   - Incorrect window filtering
 *
 * Compare output against: adb shell uiautomator dump
 * Goal: verify perception truthfulness before continuing execution-layer work.
 */
object PerceptionDiagnostics {

    private const val TAG = "PerceptionDiag"

    /**
     * Full diagnostic report for a perception snapshot.
     */
    data class DiagnosticReport(
        /** Total number of windows detected by the accessibility service. */
        val totalWindows: Int,

        /** Breakdown of windows by type. */
        val windowBreakdown: List<WindowInfo>,

        /** Total number of roots successfully obtained. */
        val totalRoots: Int,

        /** Total raw nodes encountered during traversal (before filtering). */
        val totalRawNodes: Int,

        /** Total nodes that passed visibility check. */
        val visibleNodes: Int,

        /** Total nodes that passed noteworthy check (emitted to snapshot). */
        val noteworthyNodes: Int,

        /** Total nodes skipped by visibility pruning. */
        val skippedByVisibility: Int,

        /** Total nodes skipped by noteworthy filter. */
        val skippedByNoteworthy: Int,

        /** Total nodes skipped due to depth limit. */
        val skippedByDepthLimit: Int,

        /** Total editable nodes found. */
        val editableNodes: Int,

        /** Total focused nodes found. */
        val focusedNodes: Int,

        /** Total clickable nodes found. */
        val clickableNodes: Int,

        /** Total scrollable nodes found. */
        val scrollableNodes: Int,

        /** Maximum depth reached during traversal. */
        val maxDepthReached: Int,

        /** Total nodes with non-empty text. */
        val nodesWithText: Int,

        /** Total nodes with contentDescription. */
        val nodesWithContentDesc: Int,

        /** Total nodes with resourceId. */
        val nodesWithResourceId: Int,

        /** Truncation status. */
        val wasTruncated: Boolean,

        /** Timestamp of the diagnostic. */
        val timestampMs: Long = System.currentTimeMillis(),

        /** Visibility pruning reasons breakdown. */
        val pruningReasons: Map<String, Int> = emptyMap(),

        /** Sample of skipped nodes for debugging. */
        val skippedNodeSamples: List<String> = emptyList()
    ) {
        fun toLogBlock(): String = buildString {
            appendLine("╔══════════════════════════════════════════════════════════╗")
            appendLine("║           PERCEPTION DIAGNOSTIC REPORT                  ║")
            appendLine("╠══════════════════════════════════════════════════════════╣")
            appendLine("║ Windows")
            appendLine("║   total_windows       = $totalWindows")
            for (w in windowBreakdown) {
                appendLine("║   window[${w.index}]: type=${w.typeName} pkg=${w.packageName} focused=${w.isFocused} active=${w.isActive} layer=${w.layer}")
            }
            appendLine("║ Roots")
            appendLine("║   total_roots         = $totalRoots")
            appendLine("║ Node Counts")
            appendLine("║   raw_nodes           = $totalRawNodes")
            appendLine("║   visible_nodes       = $visibleNodes")
            appendLine("║   noteworthy_nodes    = $noteworthyNodes")
            appendLine("║   max_depth_reached   = $maxDepthReached")
            appendLine("║ Skipped Nodes")
            appendLine("║   by_visibility       = $skippedByVisibility")
            appendLine("║   by_noteworthy       = $skippedByNoteworthy")
            appendLine("║   by_depth_limit      = $skippedByDepthLimit")
            appendLine("║ Interactive Nodes")
            appendLine("║   editable            = $editableNodes")
            appendLine("║   focused             = $focusedNodes")
            appendLine("║   clickable           = $clickableNodes")
            appendLine("║   scrollable          = $scrollableNodes")
            appendLine("║ Semantic Content")
            appendLine("║   with_text           = $nodesWithText")
            appendLine("║   with_content_desc   = $nodesWithContentDesc")
            appendLine("║   with_resource_id    = $nodesWithResourceId")
            appendLine("║ Status")
            appendLine("║   truncated           = $wasTruncated")
            if (pruningReasons.isNotEmpty()) {
                appendLine("║ Pruning Reasons")
                for ((reason, count) in pruningReasons) {
                    appendLine("║   $reason = $count")
                }
            }
            if (skippedNodeSamples.isNotEmpty()) {
                appendLine("║ Skipped Samples (first 10)")
                for (sample in skippedNodeSamples.take(10)) {
                    appendLine("║   $sample")
                }
            }
            appendLine("╚══════════════════════════════════════════════════════════╝")
        }
    }

    data class WindowInfo(
        val index: Int,
        val typeName: String,
        val packageName: String?,
        val isFocused: Boolean,
        val isActive: Boolean,
        val layer: Int
    )

    /**
     * Run full diagnostic walk on the accessibility service.
     *
     * This performs an independent tree walk with detailed counters
     * for every stage of the pipeline. Compare against adb uiautomator dump
     * to verify perception truthfulness.
     */
    fun runDiagnostic(service: PerceptionService): DiagnosticReport {
        Log.i(TAG, "=== STARTING PERCEPTION DIAGNOSTIC ===")

        // 1. Enumerate windows
        val windowInfos: List<AccessibilityWindowInfo> = try {
            service.windows ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to enumerate windows: ${t.message}")
            emptyList()
        }

        val windowBreakdown = windowInfos.mapIndexed { idx, w ->
            val typeName = when (w.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "OVERLAY"
                AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_DIVIDER"
                else -> "UNKNOWN(${w.type})"
            }
            val pkg = try { w.root?.packageName?.toString() } catch (_: Throwable) { null }
            WindowInfo(
                index = idx,
                typeName = typeName,
                packageName = pkg,
                isFocused = w.isFocused,
                isActive = w.isActive,
                layer = w.layer
            )
        }

        // 2. Gather roots
        val roots = mutableListOf<AccessibilityNodeInfo>()
        for (w in windowInfos) {
            try {
                val root = w.root
                if (root != null) roots.add(root)
            } catch (_: Throwable) {}
        }

        // Fallback to rootInActiveWindow
        if (roots.isEmpty()) {
            try {
                val r = service.rootInActiveWindow
                if (r != null) roots.add(r)
            } catch (_: Throwable) {}
        }

        // 3. Walk each root with diagnostic counters
        var totalRawNodes = 0
        var visibleNodes = 0
        var noteworthyNodes = 0
        var skippedByVisibility = 0
        var skippedByNoteworthy = 0
        var skippedByDepthLimit = 0
        var editableNodes = 0
        var focusedNodes = 0
        var clickableNodes = 0
        var scrollableNodes = 0
        var maxDepthReached = 0
        var nodesWithText = 0
        var nodesWithContentDesc = 0
        var nodesWithResourceId = 0
        var truncated = false

        val pruningReasons = mutableMapOf<String, Int>()
        val skippedSamples = mutableListOf<String>()

        val tmpRect = android.graphics.Rect()

        for (root in roots) {
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>() // node, depth
            stack.addLast(root to 0)

            while (stack.isNotEmpty()) {
                val (node, depth) = stack.removeLast()
                totalRawNodes++

                if (totalRawNodes > 2000) {
                    truncated = true
                    break
                }

                if (depth > maxDepthReached) maxDepthReached = depth

                if (depth > TreeWalker.DEPTH_LIMIT) {
                    skippedByDepthLimit++
                    pruningReasons["depth_limit"] = (pruningReasons["depth_limit"] ?: 0) + 1
                    if (node !== root) safeRecycle(node)
                    continue
                }

                // Check visibility
                val isVisible = try {
                    node.getBoundsInScreen(tmpRect)
                    !tmpRect.isEmpty
                } catch (_: Throwable) {
                    false
                }

                if (!isVisible) {
                    skippedByVisibility++
                    val reason = "invisible_or_empty_bounds"
                    pruningReasons[reason] = (pruningReasons[reason] ?: 0) + 1
                    if (skippedSamples.size < 20) {
                        val desc = describeNode(node)
                        skippedSamples.add("INVISIBLE: $desc")
                    }
                } else {
                    visibleNodes++

                    // Check noteworthy
                    val text = try { node.text?.toString()?.take(100) } catch (_: Throwable) { null }
                    val contentDesc = try { node.contentDescription?.toString()?.take(100) } catch (_: Throwable) { null }
                    val hasVisibleText = !text.isNullOrBlank() || !contentDesc.isNullOrBlank()
                    val type = ElementClassifier.classify(node)
                    val noteworthy = ElementClassifier.isNoteworthy(node, type, hasVisibleText)

                    if (noteworthy) {
                        noteworthyNodes++
                    } else {
                        skippedByNoteworthy++
                        val reason = "not_noteworthy(type=$type)"
                        pruningReasons[reason] = (pruningReasons[reason] ?: 0) + 1
                        if (skippedSamples.size < 20) {
                            val desc = describeNode(node)
                            skippedSamples.add("NOT_NOTEWORTHY[$type]: $desc")
                        }
                    }

                    // Count interactive attributes
                    try {
                        if (node.isEditable) editableNodes++
                        if (node.isFocused) focusedNodes++
                        if (node.isClickable) clickableNodes++
                        if (node.isScrollable) scrollableNodes++
                        if (!text.isNullOrBlank()) nodesWithText++
                        if (!contentDesc.isNullOrBlank()) nodesWithContentDesc++
                        if (!node.viewIdResourceName.isNullOrBlank()) nodesWithResourceId++
                    } catch (_: Throwable) {}
                }

                // Enqueue children
                val childCount = try { node.childCount } catch (_: Throwable) { 0 }
                for (i in childCount - 1 downTo 0) {
                    try {
                        val child = node.getChild(i)
                        if (child != null) stack.addLast(child to depth + 1)
                    } catch (_: Throwable) {}
                }

                if (node !== root) safeRecycle(node)
            }
        }

        val report = DiagnosticReport(
            totalWindows = windowInfos.size,
            windowBreakdown = windowBreakdown,
            totalRoots = roots.size,
            totalRawNodes = totalRawNodes,
            visibleNodes = visibleNodes,
            noteworthyNodes = noteworthyNodes,
            skippedByVisibility = skippedByVisibility,
            skippedByNoteworthy = skippedByNoteworthy,
            skippedByDepthLimit = skippedByDepthLimit,
            editableNodes = editableNodes,
            focusedNodes = focusedNodes,
            clickableNodes = clickableNodes,
            scrollableNodes = scrollableNodes,
            maxDepthReached = maxDepthReached,
            nodesWithText = nodesWithText,
            nodesWithContentDesc = nodesWithContentDesc,
            nodesWithResourceId = nodesWithResourceId,
            wasTruncated = truncated,
            pruningReasons = pruningReasons,
            skippedNodeSamples = skippedSamples
        )

        // Emit the full diagnostic log block
        Log.i(TAG, report.toLogBlock())

        // Emit snapshot size warning if undersized
        if (noteworthyNodes < 10) {
            Log.e(TAG, "⚠ CRITICALLY UNDERSIZED SNAPSHOT: only $noteworthyNodes noteworthy nodes from $totalRawNodes raw. " +
                    "Expected 50-300 for a typical WhatsApp screen. " +
                    "Investigate: visibility=$skippedByVisibility noteworthy=$skippedByNoteworthy depth=$skippedByDepthLimit")
        }

        return report
    }

    /**
     * Quick snapshot health check — call this after any forceSnapshot()
     * to verify perception is working correctly.
     */
    fun checkSnapshotHealth(snapshot: UiSnapshot): Boolean {
        val size = snapshot.elements.size
        val editableCount = snapshot.elements.count { it.editable }
        val clickableCount = snapshot.elements.count { it.clickable }

        Log.i(TAG, "SNAPSHOT_HEALTH: elements=$size editable=$editableCount clickable=$clickableCount " +
                "truncated=${snapshot.truncated} pkg=${snapshot.packageId}")

        if (size < 5) {
            Log.e(TAG, "SNAPSHOT_HEALTH_CRITICAL: elements=$size — perception may be broken. " +
                    "Expected 50-300 for a typical app screen.")
            return false
        }

        if (size < 20) {
            Log.w(TAG, "SNAPSHOT_HEALTH_LOW: elements=$size — perception may be partially degraded.")
        }

        return true
    }

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo) {
        try { node.recycle() } catch (_: Throwable) {}
    }

    private fun describeNode(node: AccessibilityNodeInfo): String {
        return try {
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
            val text = node.text?.toString()?.take(30) ?: ""
            val desc = node.contentDescription?.toString()?.take(30) ?: ""
            val resId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
            val clickable = node.isClickable
            val editable = node.isEditable
            "cls=$cls text='$text' desc='$desc' resId='$resId' click=$clickable edit=$editable"
        } catch (_: Throwable) {
            "<node_unreadable>"
        }
    }
}
