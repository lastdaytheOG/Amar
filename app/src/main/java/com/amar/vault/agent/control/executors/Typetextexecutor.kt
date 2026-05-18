package com.amar.vault.agent.control.executors

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.amar.vault.agent.AgentStateHolder
import com.amar.vault.agent.control.ActionExecutor
import com.amar.vault.agent.control.AgentPhase
import com.amar.vault.agent.control.ExecutionResult
import com.amar.vault.agent.control.ExecutorTier
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.dsl.TargetStrategy
import com.amar.vault.agent.perception.LockedTarget
import com.amar.vault.agent.perception.PerceptionDiagnostics
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.SearchContextVerifier
import com.amar.vault.agent.perception.SnapshotCache
import com.amar.vault.agent.perception.UiElementType
import com.amar.vault.agent.perception.UiReadinessWaiter
import kotlinx.coroutines.delay

class TypeTextExecutor(
    private val snapshotCache: SnapshotCache
) : ActionExecutor {

    override val handles: ActionKind = ActionKind.TYPE_TEXT
    override val tier: ExecutorTier = ExecutorTier.ACCESSIBILITY

    override fun isAvailable(): Boolean = PerceptionService.get() != null

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        val type = action as? AgentAction.TypeText ?: return ExecutionResult.FatalFailure(
            reason = FailureReason.Unexpected("TypeTextExecutor received ${action.kind}"),
            durationMs = 0
        )

        val started = System.currentTimeMillis()
        val service = PerceptionService.get() ?: return ExecutionResult.Failed(
            reason = FailureReason.AccessibilityUnavailable,
            durationMs = 0
        )

        // 1. Wait for stable UI (single pass, not per-strategy)
        val snapshot = UiReadinessWaiter.waitForStableUi(service)

        // Perception health check
        if (!PerceptionDiagnostics.checkSnapshotHealth(snapshot)) {
            Log.e(TAG, "PERCEPTION_CRITICAL: Running full diagnostic due to undersized snapshot")
            PerceptionDiagnostics.runDiagnostic(service)
        }

        // 2. Resolve target using original strategy to confirm it exists
        val resolve = TargetResolver.resolve(type.target, type.strategy, snapshotCache)
        val element = resolve.element ?: return ExecutionResult.Failed(
            reason = FailureReason.TargetNotFound(
                target = type.target,
                strategiesTried = resolve.strategiesTried
            ),
            durationMs = System.currentTimeMillis() - started
        )

        if (!element.editable && element.type != UiElementType.INPUT) {
            return ExecutionResult.Failed(
                reason = FailureReason.TargetNotFound(
                    target = type.target,
                    strategiesTried = resolve.strategiesTried + "target_not_editable"
                ),
                durationMs = System.currentTimeMillis() - started
            )
        }

        // 3. SEMANTIC VERIFICATION GATE
        val freshSnapshot = service.forceSnapshot()
        val verification = SearchContextVerifier.verifySearchContext(
            snapshot = freshSnapshot,
            candidateIndex = snapshot.elements.indexOf(element)
        )

        if (!verification.isSearchContext) {
            Log.e(TAG, "PHASE_TRANSITION_BLOCKED: Semantic verification FAILED. " +
                    "NOT transitioning to INPUT phase.")
            Log.e(TAG, "VERIFICATION_DETAIL: ${verification.toLogString()}")

            if (freshSnapshot.elements.size < 20) {
                PerceptionDiagnostics.runDiagnostic(service)
            }

            AgentStateHolder.phase = AgentPhase.RECOVERY
            return ExecutionResult.Failed(
                reason = FailureReason.SystemError(
                    "Search context verification FAILED. " +
                            "Negative signals: ${verification.negativeSignals.joinToString(", ")}. " +
                            "Refusing NAVIGATION→INPUT transition."
                ),
                durationMs = System.currentTimeMillis() - started
            )
        }

        Log.i(TAG, "SEMANTIC_VERIFICATION_PASSED: ${verification.toLogString()}")

        // 4. Wait for keyboard/IME to be ready BEFORE transitioning to INPUT
        val imeReady = waitForIme(service, timeoutMs = 3000)
        Log.i(TAG, "IME_WAIT_RESULT: ready=$imeReady")

        // 5. Transition to INPUT phase
        AgentStateHolder.phase = AgentPhase.INPUT
        val lockedTarget = LockedTarget(
            semanticRole = "SEARCH_INPUT",
            packageName = snapshot.packageId ?: "",
            bounds = element.bounds?.let {
                android.graphics.Rect(it.left, it.top, it.right, it.bottom)
            } ?: android.graphics.Rect()
        )
        Log.i(TAG, "PHASE_TRANSITION: INPUT. TARGET_LOCK_ACQUIRED: $lockedTarget")

        // 6. Acquire the FOCUSED EDITABLE node directly from the A11y tree.
        //    This is the critical fix: instead of re-resolving by the original
        //    target string (which may not match the now-active EditText), we
        //    find whatever node is actually focused and editable right now.
        //
        //    WhatsApp's search EditText has different resourceId/text/contentDesc
        //    than the search icon that was clicked to open it. The old code tried
        //    to match by the original "search" target, which found nothing or found
        //    the wrong node, causing all 5 injection strategies to silently fail.
        val focusedNode = findFocusedEditableNode(service)
        if (focusedNode != null) {
            Log.i(TAG, "FOCUSED_NODE_FOUND: class=${safeClassName(focusedNode)} " +
                    "text='${focusedNode.text}' hint='${focusedNode.hintText}' " +
                    "editable=${focusedNode.isEditable} focused=${focusedNode.isFocused}")
        } else {
            Log.w(TAG, "NO_FOCUSED_EDITABLE: Falling back to target-based resolution")
        }

        // 7. Cascading Text Injection — try focused node FIRST, then fallback
        val success = if (focusedNode != null) {
            injectIntoNode(focusedNode, type.text, service)
        } else {
            // Fallback: resolve by FOCUSED_EDITABLE strategy, then by original target
            injectByResolution(type, service, started)
        }

        val dur = System.currentTimeMillis() - started
        val resultData = mapOf(
            "target" to type.target,
            "length" to type.text.length.toString()
        )

        if (success) {
            AgentStateHolder.phase = AgentPhase.EXECUTION
            return ExecutionResult.ExecutedAndVerified(dur, resultData)
        } else {
            AgentStateHolder.phase = AgentPhase.RECOVERY
            Log.w(TAG, "RECOVERY_TRIGGERED: All text injection strategies failed.")
            return ExecutionResult.Failed(
                reason = FailureReason.SystemError(
                    "All text injection strategies failed for target '${type.target}'."
                ),
                durationMs = dur
            )
        }
    }

    // =====================================================================
    // FOCUSED NODE ACQUISITION — The core fix
    // =====================================================================

    /**
     * Directly find the focused editable node in the accessibility tree.
     *
     * This bypasses TargetResolver/LiveNodeFinder entirely. When the keyboard
     * is visible, there MUST be a focused editable node somewhere. We find it
     * by querying the A11y service's focus APIs, then walking roots if that fails.
     *
     * This is far more reliable than trying to re-match by the original target
     * string, which fails when the EditText has different properties than the
     * search icon/button that was clicked.
     */
    private fun findFocusedEditableNode(
        service: PerceptionService
    ): AccessibilityNodeInfo? {

        // Strategy 1: AccessibilityService.findFocus(FOCUS_INPUT) on all windows
        try {
            val windows = service.windows ?: emptyList()
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val root = try { w.root } catch (_: Throwable) { null } ?: continue
                val inputFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (inputFocused != null) {
                    Log.i(TAG, "FOCUS_STRATEGY_1: findFocus(FOCUS_INPUT) succeeded on window ${w.type}")
                    return inputFocused
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "findFocus(FOCUS_INPUT) threw: ${t.message}")
        }

        // Strategy 2: Walk all windows, find any node with isFocused && (isEditable || isEditText)
        try {
            val windows = service.windows ?: emptyList()
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val root = try { w.root } catch (_: Throwable) { null } ?: continue
                val found = walkForFocusedEditable(root)
                if (found != null) {
                    Log.i(TAG, "FOCUS_STRATEGY_2: tree walk found focused editable")
                    return found
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Window walk threw: ${t.message}")
        }

        // Strategy 3: Walk active root, find ANY editable node (not necessarily focused)
        try {
            val root = service.rootInActiveWindow ?: return null
            val editable = walkForAnyEditable(root)
            if (editable != null) {
                Log.i(TAG, "FOCUS_STRATEGY_3: found editable node (not focused)")
                // Force focus onto it
                editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                return editable
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Editable walk threw: ${t.message}")
        }

        return null
    }

    /**
     * DFS walk to find a focused+editable node.
     */
    private fun walkForFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()

            try {
                val isEdit = node.isEditable || node.className?.toString()?.contains("EditText") == true
                if (isEdit && node.isFocused) {
                    return node // Caller takes ownership
                }
            } catch (_: Throwable) {}

            val count = try { node.childCount } catch (_: Throwable) { 0 }
            for (i in 0 until count) {
                try {
                    val child = node.getChild(i)
                    if (child != null) stack.addLast(child)
                } catch (_: Throwable) {}
            }

            // Don't recycle — we might still need ancestors
            // Node recycling happens when we're done with the whole search
        }
        return null
    }

    /**
     * DFS walk to find ANY editable node (last resort).
     */
    private fun walkForAnyEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()

            try {
                val isEdit = node.isEditable || node.className?.toString()?.contains("EditText") == true
                if (isEdit) {
                    return node
                }
            } catch (_: Throwable) {}

            val count = try { node.childCount } catch (_: Throwable) { 0 }
            for (i in 0 until count) {
                try {
                    val child = node.getChild(i)
                    if (child != null) stack.addLast(child)
                } catch (_: Throwable) {}
            }
        }
        return null
    }

    // =====================================================================
    // TEXT INJECTION ENGINE
    // =====================================================================

    /**
     * Inject text into a known live node. Tries all strategies WITHOUT
     * re-resolving or re-waiting between attempts (no time wasted).
     */
    private fun injectIntoNode(
        node: AccessibilityNodeInfo,
        text: String,
        service: PerceptionService
    ): Boolean {
        // Strategy 1: Direct SET_TEXT (fastest, works on most apps)
        Log.i(TAG, "INJECT_1: ACTION_SET_TEXT direct")
        if (trySetText(node, text)) {
            if (verifyTextMutation(node, text)) {
                Log.i(TAG, "INJECT_1_SUCCESS: Text set and verified")
                return true
            }
            Log.w(TAG, "INJECT_1_PARTIAL: SET_TEXT returned true but text not verified")
        }

        // Strategy 2: Focus + SET_TEXT
        Log.i(TAG, "INJECT_2: ACTION_FOCUS + ACTION_SET_TEXT")
        try { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Throwable) {}
        if (trySetText(node, text) && verifyTextMutation(node, text)) {
            Log.i(TAG, "INJECT_2_SUCCESS")
            return true
        }

        // Strategy 3: Click (to ensure input connection) + SET_TEXT
        Log.i(TAG, "INJECT_3: ACTION_CLICK + ACTION_SET_TEXT")
        try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) {}
        // Brief pause to let InputConnection establish after click
        Thread.sleep(150)
        if (trySetText(node, text) && verifyTextMutation(node, text)) {
            Log.i(TAG, "INJECT_3_SUCCESS")
            return true
        }

        // Strategy 4: Accessibility Focus + SET_TEXT
        Log.i(TAG, "INJECT_4: ACTION_ACCESSIBILITY_FOCUS + SET_TEXT")
        try { node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) } catch (_: Throwable) {}
        if (trySetText(node, text) && verifyTextMutation(node, text)) {
            Log.i(TAG, "INJECT_4_SUCCESS")
            return true
        }

        // Strategy 5: Clipboard paste
        Log.i(TAG, "INJECT_5: Clipboard + ACTION_PASTE")
        try {
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(50)
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                if (verifyTextMutation(node, text)) {
                    Log.i(TAG, "INJECT_5_SUCCESS")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "INJECT_5_FAILED: ${e.message}")
        }

        // Strategy 6: Clear existing text + SET_TEXT (some fields reject SET_TEXT
        // when they already have content from a previous failed attempt)
        Log.i(TAG, "INJECT_6: Clear + ACTION_SET_TEXT")
        try {
            // Select all
            val selectArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectArgs)
            // Set fresh text
            if (trySetText(node, text) && verifyTextMutation(node, text)) {
                Log.i(TAG, "INJECT_6_SUCCESS")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "INJECT_6_FAILED: ${e.message}")
        }

        Log.e(TAG, "ALL_INJECTION_STRATEGIES_FAILED for text='${text.take(20)}...'")
        LiveNodeFinder.safeRecycle(node)
        return false
    }

    /**
     * Fallback: try resolving by FOCUSED_EDITABLE strategy, then original target.
     */
    private suspend fun injectByResolution(
        type: AgentAction.TypeText,
        service: PerceptionService,
        started: Long
    ): Boolean {
        // Try FOCUSED_EDITABLE strategy first
        Log.i(TAG, "FALLBACK_RESOLVE: Trying FOCUSED_EDITABLE strategy")
        val focusResolve = TargetResolver.resolve("", TargetStrategy.FOCUSED_EDITABLE, snapshotCache)
        val focusElement = focusResolve.element
        if (focusElement != null) {
            val liveNode = LiveNodeFinder.find(
                service = service,
                element = focusElement,
                preferEditable = true,
                allowPathFallback = true
            )
            if (liveNode != null && liveNode.isEnabled) {
                Log.i(TAG, "FALLBACK_RESOLVE: Got node via FOCUSED_EDITABLE")
                return injectIntoNode(liveNode, type.text, service)
            }
            liveNode?.let { LiveNodeFinder.safeRecycle(it) }
        }

        // Last resort: original target with AUTO strategy
        Log.i(TAG, "FALLBACK_RESOLVE: Trying original target '${type.target}'")
        val resolve = TargetResolver.resolve(type.target, TargetStrategy.AUTO, snapshotCache)
        val el = resolve.element ?: return false
        val liveNode = LiveNodeFinder.find(
            service = service,
            element = el,
            preferEditable = true,
            allowPathFallback = true
        )
        if (liveNode != null && liveNode.isEnabled) {
            return injectIntoNode(liveNode, type.text, service)
        }
        liveNode?.let { LiveNodeFinder.safeRecycle(it) }
        return false
    }

    // =====================================================================
    // IME/KEYBOARD DETECTION
    // =====================================================================

    /**
     * Wait for the IME (keyboard) window to appear.
     * When the keyboard is visible, there's an AccessibilityWindowInfo
     * with type TYPE_INPUT_METHOD. We poll for it.
     */
    private suspend fun waitForIme(
        service: PerceptionService,
        timeoutMs: Long = 3000
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isImeVisible(service)) {
                Log.i(TAG, "IME_DETECTED in ${System.currentTimeMillis() - start}ms")
                // Brief additional delay for IME animation to complete
                delay(200)
                return true
            }
            delay(100)
        }
        Log.w(TAG, "IME_NOT_DETECTED after ${timeoutMs}ms")
        return false
    }

    /**
     * Check if any window is an IME (input method) window.
     */
    private fun isImeVisible(service: PerceptionService): Boolean {
        return try {
            val windows = service.windows ?: return false
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (t: Throwable) {
            false
        }
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private fun trySetText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            false
        }
    }

    private fun verifyTextMutation(node: AccessibilityNodeInfo, expectedText: String): Boolean {
        return try {
            // Refresh the node to get latest text
            val refreshed = try { node.refresh(); true } catch (_: Throwable) { false }
            val actual = node.text?.toString().orEmpty()
            val matches = actual == expectedText || actual.contains(expectedText)
            Log.d(TAG, "TEXT_VERIFY: expected='${expectedText.take(20)}' actual='${actual.take(20)}' " +
                    "match=$matches refreshed=$refreshed")
            matches
        } catch (t: Throwable) {
            false
        }
    }

    private fun safeClassName(node: AccessibilityNodeInfo): String {
        return try { node.className?.toString() ?: "?" } catch (_: Throwable) { "?" }
    }

    companion object {
        private const val TAG = "TypeTextExecutor"
    }
}