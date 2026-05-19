package com.amar.vault.agent.execution

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.capability.ExecutionPlan
import com.amar.vault.agent.control.ControlLayer
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.control.TaskState
import com.amar.vault.agent.dsl.ActionEnvelope
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.dsl.TargetStrategy
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.SnapshotCache
import com.amar.vault.agent.perception.UiElement
import com.amar.vault.agent.perception.UiElementType
import com.amar.vault.agent.perception.UiReadinessWaiter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 3 / Execution: search via UI automation.
 *
 * v7 changes (Step 8 InjectionEngine + WorldState-aware verification):
 *   - Step 3 verification now consults WorldStateStore in addition to
 *     SnapshotCache. When SemanticBridge classifies a SearchInput identity
 *     in our package, we accept that as "editable appeared" — solves the
 *     multi-window blindness in legacy snapshot.
 *   - Step 8 (InjectionEngine) runs AFTER step 3 success, replacing legacy
 *     step 5 as primary text-injection path. Legacy TypeText cascade is
 *     retained as fallback.
 */
@Singleton
class UiSearchExecutor @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val controlLayer: ControlLayer,
    private val snapshotCache: SnapshotCache,
    private val injectionRouter: com.amar.vault.agent.runtime.injection.InjectionRouter,
    private val worldStateStore: com.amar.vault.agent.runtime.state.WorldStateStore
) {

    suspend fun execute(plan: ExecutionPlan.UiSearch, ctx: TaskContext): ExecutionOutcome {
        val started = System.currentTimeMillis()
        val TAG = "UiSearchExecutor"
        android.util.Log.i(TAG, "execute() pkg=${plan.packageId} query='${plan.query}'")

        val svcCheck = PerceptionService.get()
        android.util.Log.i(TAG, "DIAG PerceptionService.get() = ${svcCheck != null}")
        val cacheCheck = snapshotCache.currentAnyAge()
        android.util.Log.i(TAG, "DIAG cache pkg=${cacheCheck?.packageId} elements=${cacheCheck?.size}")

        // Step 1: open the app
        val openedState = submit(
            AgentAction.OpenApp(app = plan.packageId, packageId = plan.packageId)
        )
        val opened = openedState is TaskState.Succeeded
        android.util.Log.i(TAG, "step 1 open: $opened (state=${openedState::class.simpleName})")
        if (!opened) {
            return ExecutionOutcome.Failed(
                packageId = plan.packageId,
                detail = "failed to open app before ui-search",
                durationMs = System.currentTimeMillis() - started
            )
        }

        // Step 2: lightweight wait for the app's window to be live
        val svc = PerceptionService.get()
        val settled = svc?.let {
            UiReadinessWaiter.waitForUi(
                svc = it,
                targetPackage = plan.packageId,
                timeoutMs = 3_000L,
                minElements = 10
            )
        }
        val step2Time = System.currentTimeMillis() - started
        android.util.Log.i(TAG, "step 2 settled in ${step2Time}ms: " +
                "${settled?.packageId} elements=${settled?.size}")

        val screenHeight = svc?.resources?.displayMetrics?.heightPixels ?: 2400
        val appBarCutoff = (screenHeight * 0.15f).toInt()
        android.util.Log.i(TAG, "app-bar cutoff: top ${appBarCutoff}px of ${screenHeight}px")

        // Step 2.5: navigate to main screen
        android.util.Log.i(TAG, "step 2.5 navigating to app home screen...")
        val navStart = System.currentTimeMillis()
        for (backAttempt in 0 until 3) {
            val snap = svc?.forceSnapshot()
            val elements = snap?.elements ?: emptyList()

            val hasSearchAffordance = elements.any { el ->
                val inAppBar = el.bounds != null && el.bounds.centerY <= appBarCutoff
                inAppBar && (
                        el.contentDesc?.contains("search", true) == true ||
                                el.resourceId?.contains("search", true) == true
                        )
            }

            if (hasSearchAffordance) {
                android.util.Log.i(TAG, "step 2.5 search affordance visible after $backAttempt back presses")
                break
            }

            val hasMainScreenTabs = elements.any { el ->
                val txt = el.text?.lowercase() ?: ""
                val desc = el.contentDesc?.lowercase() ?: ""
                txt == "chats" || txt == "calls" || txt == "updates" || txt == "communities" ||
                        desc == "chats" || desc == "calls" || desc == "updates" || desc == "communities"
            }

            if (hasMainScreenTabs) {
                android.util.Log.i(TAG, "step 2.5 main screen tabs detected after $backAttempt back presses")
                break
            }

            android.util.Log.i(TAG, "step 2.5 pressing BACK (attempt $backAttempt) to reach main screen")
            val backed = svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
            android.util.Log.i(TAG, "step 2.5 BACK result: $backed")
            delay(600)

            val afterBack = svc?.forceSnapshot()
            if (afterBack?.packageId != plan.packageId) {
                android.util.Log.w(TAG, "step 2.5 BACK exited the app (now ${afterBack?.packageId}), reopening")
                submit(AgentAction.OpenApp(app = plan.packageId, packageId = plan.packageId))
                delay(500)
                break
            }
        }
        android.util.Log.i(TAG, "step 2.5 navigation took ${System.currentTimeMillis() - navStart}ms")

        // Step 2.7: deep poll for editable or search button
        android.util.Log.i(TAG, "step 2.7 polling for input or search affordance...")
        var latestSnap = svc?.forceSnapshot()
        var foundInput: UiElement? = null
        var foundSearchButtons: List<UiElement> = emptyList()

        val pollStart = System.currentTimeMillis()
        withTimeoutOrNull(4_000L) {
            while (true) {
                latestSnap = svc?.forceSnapshot()
                val elements = latestSnap?.elements ?: emptyList()

                foundInput = elements.firstOrNull { el ->
                    (el.editable || el.type == UiElementType.INPUT) &&
                            el.bounds != null && !el.bounds.isEmpty
                }

                foundSearchButtons = elements.filter { el ->
                    val inAppBar = el.bounds != null && el.bounds.centerY <= appBarCutoff
                    inAppBar &&
                            (el.contentDesc?.contains("search", true) == true ||
                                    el.resourceId?.contains("search", true) == true) &&
                            !isNonSearchElement(el)
                }

                if (foundInput != null || foundSearchButtons.isNotEmpty()) {
                    return@withTimeoutOrNull true
                }
                delay(200L)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        android.util.Log.i(TAG, "step 2.7 done in ${System.currentTimeMillis() - pollStart}ms: " +
                "input=${foundInput?.resourceId ?: foundInput?.text ?: foundInput?.contentDesc}, " +
                "searchButtons=${foundSearchButtons.size}")

        val alreadyEditable = foundInput != null
        android.util.Log.i(TAG, "stage 1 already-editable bypass: $alreadyEditable")

        if (!alreadyEditable) {
            val baseCandidates = listOf(
                AgentAction.Click(target = "Search", strategy = TargetStrategy.CONTENT_DESC),
                AgentAction.Click(target = "search_action_bar", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "menuitem_search", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "search_bar", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "my_search_bar", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "Search settings", strategy = TargetStrategy.TEXT)
            )

            val dynamicCandidates = foundSearchButtons.mapNotNull { node ->
                val target = node.resourceId ?: node.contentDesc ?: node.text
                ?: return@mapNotNull null
                val strategy = when {
                    node.resourceId != null -> TargetStrategy.RESOURCE_ID
                    node.contentDesc != null -> TargetStrategy.CONTENT_DESC
                    node.text != null -> TargetStrategy.TEXT
                    else -> TargetStrategy.AUTO
                }
                AgentAction.Click(target = target, strategy = strategy)
            }

            val clickCandidates = (baseCandidates + dynamicCandidates).distinctBy { it.target }

            var clickedWith = "none"
            val step3Start = System.currentTimeMillis()

            for ((idx, candidate) in clickCandidates.withIndex()) {

                val state = submit(candidate)
                val mechOk = state is TaskState.Succeeded

                if (!mechOk && state is TaskState.Failed) {
                    android.util.Log.i(TAG, "step 3 candidate $idx failed: ${state.reason}")
                }

                android.util.Log.i(
                    TAG,
                    "step 3 candidate $idx " +
                            "(${candidate.target}/${candidate.strategy}) mechanical=$mechOk " +
                            "(took ${System.currentTimeMillis() - step3Start}ms)"
                )

                if (!mechOk) continue

                // v7: WorldState-aware verification
                val verified = waitForEditableOnly(svc, POST_CLICK_VERIFY_MS, plan.packageId)

                if (verified) {
                    android.util.Log.i(
                        TAG,
                        "step 3 candidate $idx VERIFIED — editable field appeared"
                    )
                    clickedWith = "${candidate.target}/${candidate.strategy}"
                    break
                }

                android.util.Log.i(
                    TAG,
                    "step 3 candidate $idx click didn't produce editable; trying gesture tap"
                )

                val gestureAction = AgentAction.GestureTap(
                    target = candidate.target,
                    strategy = candidate.strategy
                )

                val gestureState = submit(gestureAction)
                val gestureOk = gestureState is TaskState.Succeeded

                android.util.Log.i(
                    TAG,
                    "step 3 candidate $idx gesture_mechanical=$gestureOk"
                )

                if (gestureOk) {
                    val gestureVerified = waitForEditableOnly(svc, POST_CLICK_VERIFY_MS, plan.packageId)
                    if (gestureVerified) {
                        android.util.Log.i(
                            TAG,
                            "step 3 candidate $idx VERIFIED via gesture tap"
                        )
                        clickedWith = "${candidate.target}/${candidate.strategy}/gesture"
                        break
                    }
                }

                android.util.Log.i(
                    TAG,
                    "step 3 candidate $idx failed verification; next"
                )
            }

            if (clickedWith == "none") {
                android.util.Log.w(TAG, "FAIL step 3: no candidate produced an editable search field")
                return ExecutionOutcome.Failed(
                    packageId = plan.packageId,
                    detail = "could not activate search affordance (no editable field appeared)",
                    durationMs = System.currentTimeMillis() - started
                )
            }

            android.util.Log.i(TAG, "step 3 winner: $clickedWith")
            delay(FIELD_APPEAR_DELAY_MS)

            // DIAG
            val diagSnap = svc?.forceSnapshot()
            android.util.Log.i(TAG, "DIAG step5 snapshot pkg=${diagSnap?.packageId} elements=${diagSnap?.size}")
            diagSnap?.elements?.forEachIndexed { i, el ->
                android.util.Log.i(TAG, "DIAG[$i] type=${el.type} editable=${el.editable} focused=${el.focused} " +
                        "rid=${el.resourceId} text='${el.text?.take(40)}' cd='${el.contentDesc?.take(40)}' " +
                        "clickable=${el.clickable} bounds=${el.bounds}")
            }
        }

        // Step 8 (God Architecture): try the InjectionEngine FIRST, before legacy step 5.
        if (USE_INJECTION_ENGINE) {
            // Wait up to 1s for SemanticBridge to upgrade focusedEditableIdentity
            // to a typed identity in the TARGET package. Avoids firing the engine
            // against stale Unknown/wrong-package identity from agent's own UI.
            val identityReady = kotlinx.coroutines.withTimeoutOrNull(1_000L) {
                while (true) {
                    val cur = worldStateStore.current().focusedEditableIdentity
                    if (cur != null &&
                        cur !is com.amar.vault.agent.runtime.state.SemanticIdentity.Unknown &&
                        cur.packageId == plan.packageId) {
                        return@withTimeoutOrNull true
                    }
                    kotlinx.coroutines.delay(50L)
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: false

            android.util.Log.i(TAG, "step 8 identity_ready=$identityReady " +
                    "current=${worldStateStore.current().focusedEditableIdentity?.let { it::class.simpleName + "/" + it.packageId }}")

            if (identityReady) {
                android.util.Log.i(TAG, "step 8 invoking InjectionEngine after step 3 success...")
                val engineResult = injectionRouter.injectIntoFocusedEditable(plan.query)
                when (engineResult) {
                    is com.amar.vault.agent.runtime.injection.InjectionRouter.SimpleResult.Verified -> {
                        android.util.Log.i(TAG, "step 8 InjectionEngine WIN via=${engineResult.viaStrategy} " +
                                "conf=${engineResult.confidence} dur=${engineResult.durationMs}ms")
                        return ExecutionOutcome.Started(
                            packageId = plan.packageId,
                            durationMs = System.currentTimeMillis() - started,
                            route = "ui_search/engine/${engineResult.viaStrategy}"
                        )
                    }
                    is com.amar.vault.agent.runtime.injection.InjectionRouter.SimpleResult.Failed -> {
                        android.util.Log.w(TAG, "step 8 InjectionEngine failed reason=${engineResult.reason} " +
                                "attempts=${engineResult.attempts}; falling through to legacy step 5")
                    }
                    is com.amar.vault.agent.runtime.injection.InjectionRouter.SimpleResult.NoIdentity -> {
                        android.util.Log.w(TAG, "step 8 InjectionEngine NoIdentity; falling through to legacy step 5")
                    }
                }
            } else {
                android.util.Log.w(TAG, "step 8 skipped: identity not ready in 1000ms; falling through to legacy step 5")
            }
        }

        // Step 5: legacy fallback typing via findFocus(FOCUS_INPUT).
        android.util.Log.i(TAG, "step 5: typing query via findFocus(FOCUS_INPUT)")
        val step5Start = System.currentTimeMillis()
        var typedWith = "none"

        val focusedNode = findInputFocusedNode(svc)
        if (focusedNode != null) {
            android.util.Log.i(TAG, "step 5 FOCUS_INPUT found: " +
                    "class=${focusedNode.className} " +
                    "editable=${focusedNode.isEditable} " +
                    "text='${focusedNode.text}' " +
                    "hint='${focusedNode.hintText}'")

            val injected = injectText(focusedNode, plan.query, svc!!)
            if (injected) {
                typedWith = "findFocus(FOCUS_INPUT)/ACTION_SET_TEXT"
                android.util.Log.i(TAG, "step 5 SUCCESS via $typedWith")
            } else {
                android.util.Log.w(TAG, "step 5 findFocus node found but injection failed")
            }
            safeRecycle(focusedNode)
        } else {
            android.util.Log.w(TAG, "step 5 findFocus(FOCUS_INPUT) returned null")
        }

        if (typedWith == "none") {
            android.util.Log.i(TAG, "step 5 falling back to TypeText candidates")
            val typeCandidates = listOf(
                AgentAction.TypeText(
                    target = "(focused editable)",
                    text = plan.query,
                    strategy = TargetStrategy.FOCUSED_EDITABLE,
                    submit = true
                ),
                AgentAction.TypeText(
                    target = "search_src_text",
                    text = plan.query,
                    strategy = TargetStrategy.RESOURCE_ID,
                    submit = true
                ),
                AgentAction.TypeText(
                    target = "search_input",
                    text = plan.query,
                    strategy = TargetStrategy.RESOURCE_ID,
                    submit = true
                )
            )

            for ((idx, candidate) in typeCandidates.withIndex()) {
                val state = submit(candidate)
                val ok = state is TaskState.Succeeded

                android.util.Log.i(
                    TAG,
                    "step 5 fallback $idx " +
                            "(${candidate.target}/${candidate.strategy}) -> $ok " +
                            "(took ${System.currentTimeMillis() - step5Start}ms)"
                )

                if (ok) {
                    typedWith = "${candidate.target}/${candidate.strategy}"
                    break
                }
            }
        }

        if (typedWith == "none") {
            android.util.Log.w(TAG, "FAIL step 5: all type methods failed")
            return ExecutionOutcome.Failed(
                packageId = plan.packageId,
                detail = "could not type query into field",
                durationMs = System.currentTimeMillis() - started
            )
        }

        android.util.Log.i(TAG, "step 5 winner: $typedWith")
        android.util.Log.i(TAG, "execute() SUCCESS in ${System.currentTimeMillis() - started}ms")

        return ExecutionOutcome.Started(
            packageId = plan.packageId,
            durationMs = System.currentTimeMillis() - started,
            route = "ui_search"
        )
    }

    // =========================================================================
    // Verification helpers
    // =========================================================================

    /**
     * Wait for an editable input field to appear. v7: accepts EITHER
     *   (a) an editable in the legacy SnapshotCache, OR
     *   (b) WorldStateStore.focusedEditableIdentity is SearchInput in [targetPackage].
     */
    private suspend fun waitForEditableOnly(
        svc: PerceptionService?,
        timeoutMs: Long,
        targetPackage: String
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val snap = svc?.forceSnapshot()
            val elements = snap?.elements ?: emptyList()
            val hasEditable = elements.any {
                (it.editable || it.type == UiElementType.INPUT) &&
                        it.bounds != null && !it.bounds.isEmpty
            }
            if (hasEditable) {
                android.util.Log.i("UiSearchExecutor", "waitForEditable: legacy snapshot hit")
                return@withTimeoutOrNull true
            }

            val ws = worldStateStore.current()
            val isSearchInput = ws.focusedEditableIdentity is
                    com.amar.vault.agent.runtime.state.SemanticIdentity.SearchInput
            val pkgMatches = ws.focusedEditableIdentity?.packageId == targetPackage
            if (isSearchInput && pkgMatches) {
                android.util.Log.i("UiSearchExecutor",
                    "waitForEditable: WorldState SearchInput hit pkg=$targetPackage")
                return@withTimeoutOrNull true
            }

            delay(150L)
        }

        @Suppress("UNREACHABLE_CODE")
        false
    } ?: false

    private fun isNonSearchElement(el: UiElement): Boolean {
        val desc = el.contentDesc?.lowercase() ?: ""
        val resId = el.resourceId?.lowercase() ?: ""
        val text = el.text?.lowercase() ?: ""
        return desc.contains("sticker") || desc.contains("emoji") ||
                desc.contains("camera") || desc.contains("gif") ||
                desc.contains("voice") || desc.contains("attach") ||
                resId.contains("sticker") || resId.contains("emoji") ||
                resId.contains("camera") || resId.contains("gif") ||
                resId.contains("attach") ||
                text.contains("sticker") || text.contains("gif")
    }

    // =========================================================================
    // Focus-based node acquisition
    // =========================================================================

    private fun findInputFocusedNode(svc: PerceptionService?): AccessibilityNodeInfo? {
        if (svc == null) return null

        try {
            val windows = svc.windows ?: emptyList()
            for (w in windows) {
                if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val root = try { w.root } catch (_: Throwable) { null } ?: continue
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    android.util.Log.i("UiSearchExecutor",
                        "findFocus(FOCUS_INPUT) in window ${w.type}: class=${focused.className} " +
                                "editable=${focused.isEditable} focused=${focused.isFocused} " +
                                "text='${focused.text}' hint='${focused.hintText}'")
                    return focused
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("UiSearchExecutor", "findFocus threw: ${t.message}")
        }

        try {
            val windows = svc.windows ?: emptyList()
            for (w in windows) {
                if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val root = try { w.root } catch (_: Throwable) { null } ?: continue
                val found = walkForFocusedEditable(root)
                if (found != null) {
                    android.util.Log.i("UiSearchExecutor", "walkForFocusedEditable found node")
                    return found
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("UiSearchExecutor", "window walk threw: ${t.message}")
        }

        try {
            val root = svc.rootInActiveWindow ?: return null
            val editable = walkForAnyEditable(root)
            if (editable != null) {
                android.util.Log.i("UiSearchExecutor", "walkForAnyEditable found node, forcing focus")
                try { editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Throwable) {}
                return editable
            }
        } catch (t: Throwable) {
            android.util.Log.w("UiSearchExecutor", "editable walk threw: ${t.message}")
        }

        return null
    }

    private fun walkForFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            try {
                val isEdit = node.isEditable || node.className?.toString()?.contains("EditText") == true
                if (isEdit && node.isFocused) return node
            } catch (_: Throwable) {}
            val count = try { node.childCount } catch (_: Throwable) { 0 }
            for (i in 0 until count) {
                try { node.getChild(i)?.let { stack.addLast(it) } } catch (_: Throwable) {}
            }
        }
        return null
    }

    private fun walkForAnyEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            try {
                val isEdit = node.isEditable || node.className?.toString()?.contains("EditText") == true
                if (isEdit) return node
            } catch (_: Throwable) {}
            val count = try { node.childCount } catch (_: Throwable) { 0 }
            for (i in 0 until count) {
                try { node.getChild(i)?.let { stack.addLast(it) } } catch (_: Throwable) {}
            }
        }
        return null
    }

    // =========================================================================
    // Text injection engine (legacy fallback)
    // =========================================================================

    private fun injectText(node: AccessibilityNodeInfo, text: String, svc: PerceptionService): Boolean {
        val TAG = "UiSearchExecutor"

        android.util.Log.i(TAG, "INJECT: waiting 400ms for InputConnection to establish")
        Thread.sleep(400)

        android.util.Log.i(TAG, "INJECT_1: Direct ACTION_SET_TEXT")
        val set1 = trySetText(node, text)
        android.util.Log.i(TAG, "INJECT_1: performAction returned $set1")
        if (set1) {
            Thread.sleep(200)
            val verified = verifyText(node, text)
            android.util.Log.i(TAG, "INJECT_1: verify=$verified")
            if (verified) return true
            val actual = try { node.refresh(); node.text?.toString() } catch (_: Throwable) { null }
            android.util.Log.i(TAG, "INJECT_1: actual text after SET_TEXT='${actual?.take(30)}'")
            if (!actual.isNullOrEmpty()) return true
        }

        android.util.Log.i(TAG, "INJECT_2: ACTION_CLICK + wait + SET_TEXT")
        try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Throwable) {}
        Thread.sleep(300)
        val set2 = trySetText(node, text)
        android.util.Log.i(TAG, "INJECT_2: performAction returned $set2")
        if (set2) {
            Thread.sleep(200)
            val actual = try { node.refresh(); node.text?.toString() } catch (_: Throwable) { null }
            android.util.Log.i(TAG, "INJECT_2: actual text='${actual?.take(30)}'")
            if (!actual.isNullOrEmpty()) return true
        }

        android.util.Log.i(TAG, "INJECT_3: Clipboard + ACTION_PASTE")
        try {
            val clipboard = svc.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("q", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(100)
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            android.util.Log.i(TAG, "INJECT_3: ACTION_PASTE returned $pasted")
            if (pasted) {
                Thread.sleep(200)
                val actual = try { node.refresh(); node.text?.toString() } catch (_: Throwable) { null }
                android.util.Log.i(TAG, "INJECT_3: actual text='${actual?.take(30)}'")
                if (!actual.isNullOrEmpty()) return true
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "INJECT_3 failed: ${e.message}")
        }

        android.util.Log.i(TAG, "INJECT_4: SELECT_ALL + SET_TEXT")
        try {
            val selectArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectArgs)
            Thread.sleep(50)
            val set4 = trySetText(node, text)
            android.util.Log.i(TAG, "INJECT_4: performAction returned $set4")
            if (set4) {
                Thread.sleep(200)
                val actual = try { node.refresh(); node.text?.toString() } catch (_: Throwable) { null }
                android.util.Log.i(TAG, "INJECT_4: actual text='${actual?.take(30)}'")
                if (!actual.isNullOrEmpty()) return true
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "INJECT_4 failed: ${e.message}")
        }

        android.util.Log.e(TAG, "ALL INJECTION STRATEGIES FAILED for text='${text.take(20)}'")
        return false
    }

    private fun trySetText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) { false }
    }

    private fun verifyText(node: AccessibilityNodeInfo, expected: String): Boolean {
        return try {
            node.refresh()
            val actual = node.text?.toString().orEmpty()
            actual == expected || actual.contains(expected)
        } catch (_: Throwable) { false }
    }

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo) {
        try { node.recycle() } catch (_: Throwable) {}
    }

    private suspend fun submit(action: AgentAction): TaskState {
        val envelope = ActionEnvelope(action = action)
        val taskCtx = controlLayer.submitEnvelope(envelope)
        return taskCtx.state.first { it.isTerminal }
    }

    companion object {
        private const val FIELD_APPEAR_DELAY_MS = 400L
        private const val POST_CLICK_VERIFY_MS = 2_500L

        /**
         * Step 8 feature flag. After step 3 succeeds the InjectionEngine
         * runs FIRST before legacy step 5 typing.
         */
        private const val USE_INJECTION_ENGINE = true
    }
}