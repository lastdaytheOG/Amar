package com.amar.vault.agent.execution

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.amar.vault.agent.capability.ExecutionPlan
import com.amar.vault.agent.control.ControlLayer
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.control.TaskState
import com.amar.vault.agent.dsl.ActionEnvelope
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.dsl.TargetStrategy
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.SnapshotCache
import com.amar.vault.agent.perception.UiBounds
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
 * Upgraded changes:
 * - Fixes Jetpack Compose tree compression (elements=1 bug) by explicitly iterating
 * all interactive window layers and forcing structural NodeInfo refreshing.
 * - Auto-triggers click pipelines on voice-to-text toggles to pop open the standard IME keyboard.
 */
@Singleton
class UiSearchExecutor @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val controlLayer: ControlLayer,
    private val snapshotCache: SnapshotCache,
    private val injectionRouter: com.amar.vault.agent.runtime.injection.InjectionRouter,
    private val worldStateStore: com.amar.vault.agent.runtime.state.WorldStateStore,
    private val phaseOrchestrator: com.amar.vault.agent.runtime.orchestrator.PhaseOrchestrator,
    private val environmentVerifier: com.amar.vault.agent.runtime.environment.EnvironmentVerifier
) {

    suspend fun execute(plan: ExecutionPlan.UiSearch, ctx: TaskContext): ExecutionOutcome {
        val workflowStart = System.currentTimeMillis()
        val result = phaseOrchestrator.runWorkflow(
            goal = "search:${plan.query}",
            targetPackage = plan.packageId
        ) { _ ->
            executeInternal(plan, ctx)
        }
        return when (result) {
            is com.amar.vault.agent.runtime.orchestrator.PhaseOrchestrator.WorkflowResult.Success ->
                result.value
            is com.amar.vault.agent.runtime.orchestrator.PhaseOrchestrator.WorkflowResult.Failure -> {
                android.util.Log.w("UiSearchExecutor", "runWorkflow failed: ${result.cause.message}")
                ExecutionOutcome.Failed(
                    packageId = plan.packageId,
                    detail = "workflow_failed:${result.cause.message}",
                    durationMs = System.currentTimeMillis() - workflowStart
                )
            }
        }
    }

    private suspend fun executeInternal(plan: ExecutionPlan.UiSearch, ctx: TaskContext): ExecutionOutcome {
        val realPkg = plan.packageId.substringBefore("#")
        val started = System.currentTimeMillis()
        val TAG = "UiSearchExecutor"
        android.util.Log.i(TAG, "execute() pkg=${plan.packageId} realPkg=$realPkg query='${plan.query}'")

        val svcCheck = PerceptionService.get()
        android.util.Log.i(TAG, "DIAG PerceptionService.get() = ${svcCheck != null}")

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
                minElements = 1 // Safe default constraint allows overlapping layouts to settle cleanly
            )
        }
        android.util.Log.i(TAG, "step 2 settled: ${settled?.packageId} elements=${settled?.size}")

        val screenHeight = svc?.resources?.displayMetrics?.heightPixels ?: 2400
        val appBarCutoff = (screenHeight * 0.15f).toInt()
        android.util.Log.i(TAG, "app-bar cutoff: top ${appBarCutoff}px of ${screenHeight}px")

        // Step 2.5: navigate to main screen (skip for custom helper sentinel overlays like Gemini panel)
        if (plan.packageId != "com.google.android.googlequicksearchbox#gemini") {
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
                if (afterBack?.packageId != realPkg) {
                    android.util.Log.w(TAG, "step 2.5 BACK exited the app (now ${afterBack?.packageId}), reopening")
                    submit(AgentAction.OpenApp(app = plan.packageId, packageId = plan.packageId))
                    delay(500)
                    break
                }
            }
            android.util.Log.i(TAG, "step 2.5 navigation took ${System.currentTimeMillis() - navStart}ms")
        }

        // Step 2.7: deep poll for editable or search button across all tree layers
        android.util.Log.i(TAG, "step 2.7 polling for input or search affordance across semantic trees...")
        var foundInput: UiElement? = null
        var foundSearchButtons: List<UiElement> = emptyList()

        val pollStart = System.currentTimeMillis()
        withTimeoutOrNull(4_000L) {
            while (true) {
                val nodes = mutableListOf<UiElement>()
                // Force an explicit traversal across active interactive layouts to prevent multi-window blindness
                svc?.windows?.forEach { win ->
                    val root = try { win.root } catch (_: Throwable) { null }
                    if (root != null) {
                        unpackComposeTree(root, nodes)
                    }
                }

                foundInput = nodes.firstOrNull { el ->
                    el.editable && el.bounds != null && !el.bounds.isEmpty
                }

                foundSearchButtons = nodes.filter { el ->
                    (el.contentDesc?.contains("search", true) == true ||
                            el.contentDesc?.contains("keyboard", true) == true ||
                            el.text?.contains("Type", true) == true ||
                            el.resourceId?.contains("search", true) == true) &&
                            !isNonSearchElement(el)
                }

                if (foundInput != null || foundSearchButtons.isNotEmpty()) {
                    return@withTimeoutOrNull true
                }
                delay(250L)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        android.util.Log.i(TAG, "step 2.7 done in ${System.currentTimeMillis() - pollStart}ms")

        val alreadyEditable = foundInput != null
        android.util.Log.i(TAG, "stage 1 already-editable bypass: $alreadyEditable")

        // Phase 4a: Semantic environment verification.
        // For multi-surface packages (Gemini/Search in Quicksearchbox, etc),
        // structural "an editable exists" is not enough — we must verify the
        // ACTIVE environment matches the user's target intent.
        val targetEnvName = if (plan.packageId.endsWith("#gemini")) "GeminiConversation" else null
        if (targetEnvName != null) {
            val targetEnv = environmentVerifier.findByName(targetEnvName)
            if (targetEnv != null) {
                val verifyResult = environmentVerifier.verify(targetEnv)
                if (!verifyResult.targetReached) {
                    android.util.Log.w(TAG,
                        "ENV_MISMATCH target='${targetEnvName}' " +
                                "actualWinner='${verifyResult.winningEnvironment?.name}' " +
                                "confidence=${"%.2f".format(verifyResult.targetConfidence)} " +
                                "(threshold=${targetEnv.confidenceThreshold})")
                    // For now: abort. Phase 4c will add automatic recovery.
                    return ExecutionOutcome.Failed(
                        packageId = plan.packageId,
                        detail = "wrong_environment:expected=${targetEnvName} " +
                                "got=${verifyResult.winningEnvironment?.name ?: "unknown"} " +
                                "conf=${"%.2f".format(verifyResult.targetConfidence)}",
                        durationMs = System.currentTimeMillis() - started
                    )
                }
                android.util.Log.i(TAG,
                    "ENV_VERIFIED '${targetEnvName}' conf=${"%.2f".format(verifyResult.targetConfidence)}")
            }
        }

        if (!alreadyEditable) {
            val baseCandidates = listOf(
                AgentAction.Click(target = "Search", strategy = TargetStrategy.CONTENT_DESC),
                AgentAction.Click(target = "search_action_bar", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "menuitem_search", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "search_bar", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "my_search_bar", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "Search settings", strategy = TargetStrategy.TEXT),
                AgentAction.Click(target = "Ask Gemini", strategy = TargetStrategy.TEXT),

                // --- Gemini Keyboard Toggle Strategies ---
                AgentAction.Click(target = "Keyboard", strategy = TargetStrategy.CONTENT_DESC),
                AgentAction.Click(target = "Type", strategy = TargetStrategy.CONTENT_DESC),
                AgentAction.Click(target = "Type text", strategy = TargetStrategy.CONTENT_DESC),
                AgentAction.Click(target = "keyboard_icon", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "Type, talk, or share a photo", strategy = TargetStrategy.TEXT),

                AgentAction.Click(target = "assistant_robin_input_collapsed_text_half_sheet", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "assistant_robin_chat_input_box", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "assistant_robin_chat_input_half_sheet", strategy = TargetStrategy.RESOURCE_ID)
            )

            val dynamicCandidates = foundSearchButtons.mapNotNull { node ->
                val target = node.resourceId ?: node.contentDesc ?: node.text ?: return@mapNotNull null
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

                if (!mechOk) continue

                if (mechOk) {
                    val verified = waitForEditableOnly(svc, POST_CLICK_VERIFY_MS, realPkg)
                    if (verified) {
                        android.util.Log.i(TAG, "step 3 candidate $idx VERIFIED — editable field appeared")
                        clickedWith = "${candidate.target}/${candidate.strategy}"
                        break
                    }
                }

                android.util.Log.i(TAG, "step 3 candidate $idx click didn't produce editable; trying gesture tap fallback")
                val gestureAction = AgentAction.GestureTap(target = candidate.target, strategy = candidate.strategy)
                val gestureState = submit(gestureAction)

                if (gestureState is TaskState.Succeeded) {
                    val gestureVerified = waitForEditableOnly(svc, POST_CLICK_VERIFY_MS, realPkg)
                    if (gestureVerified) {
                        android.util.Log.i(TAG, "step 3 candidate $idx VERIFIED via gesture tap")
                        clickedWith = "${candidate.target}/${candidate.strategy}/gesture"
                        break
                    }
                }
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
        }

        // Step 8: try the InjectionEngine FIRST, before legacy step 5.
        if (USE_INJECTION_ENGINE) {
            val identityReady = kotlinx.coroutines.withTimeoutOrNull(1_000L) {
                while (true) {
                    val cur = worldStateStore.current().focusedEditableIdentity
                    if (cur != null &&
                        cur !is com.amar.vault.agent.runtime.state.SemanticIdentity.Unknown &&
                        cur.packageId == realPkg) {
                        return@withTimeoutOrNull true
                    }
                    kotlinx.coroutines.delay(50L)
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: false

            android.util.Log.i(TAG, "step 8 identity_ready=$identityReady")

            if (identityReady) {
                val engineResult = injectionRouter.injectIntoFocusedEditable(plan.query)
                when (engineResult) {
                    is com.amar.vault.agent.runtime.injection.InjectionRouter.SimpleResult.Verified -> {
                        android.util.Log.i(TAG, "step 8 InjectionEngine WIN via=${engineResult.viaStrategy}")
                        return ExecutionOutcome.Started(
                            packageId = plan.packageId,
                            durationMs = System.currentTimeMillis() - started,
                            route = "ui_search/engine/${engineResult.viaStrategy}"
                        )
                    }
                    else -> android.util.Log.w(TAG, "step 8 InjectionEngine fallback to typing paths")
                }
            }
        }

        // Step 5: typing query via focus updates
        android.util.Log.i(TAG, "step 5: typing query via findFocus(FOCUS_INPUT)")
        var typedWith = "none"

        val focusedNode = findInputFocusedNode(svc)
        if (focusedNode != null) {
            val injected = injectText(focusedNode, plan.query)
            if (injected) {
                typedWith = "findFocus(FOCUS_INPUT)/ACTION_SET_TEXT"
                android.util.Log.i(TAG, "step 5 SUCCESS via $typedWith")
            }
            safeRecycle(focusedNode)
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
                if (state is TaskState.Succeeded) {
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

        return ExecutionOutcome.Started(
            packageId = plan.packageId,
            durationMs = System.currentTimeMillis() - started,
            route = "ui_search"
        )
    }

    // =========================================================================
    // Verification & Layout Helpers
    // =========================================================================

    /**
     * Traverses the layout layer recursively, forcing node refreshing to bypass dynamic tree compression.
     */
    private fun unpackComposeTree(node: AccessibilityNodeInfo, output: MutableList<UiElement>) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(node)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            try {
                current.refresh() // Unpacks Jetpack Compose semantic merges on-the-fly
                val rect = android.graphics.Rect()
                current.getBoundsInScreen(rect)

                val uiEl = UiElement(
                    type = if (current.isEditable) UiElementType.INPUT else UiElementType.TEXT,
                    text = current.text?.toString(),
                    contentDesc = current.contentDescription?.toString(),
                    resourceId = current.viewIdResourceName,
                    bounds = UiBounds.from(rect),
                    clickable = current.isClickable,
                    editable = current.isEditable,
                    focused = current.isFocused,
                    depth = 0,
                    path = current.viewIdResourceName ?: ""
                )
                output.add(uiEl)
            } catch (_: Throwable) {}

            val count = try { current.childCount } catch (_: Throwable) { 0 }
            for (i in 0 until count) {
                try { current.getChild(i)?.let { stack.addLast(it) } } catch (_: Throwable) {}
            }
        }
    }

    private suspend fun waitForEditableOnly(
        svc: PerceptionService?,
        timeoutMs: Long,
        targetPackage: String
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val nodes = mutableListOf<UiElement>()
            svc?.windows?.forEach { win ->
                val root = try { win.root } catch (_: Throwable) { null }
                if (root != null) {
                    unpackComposeTree(root, nodes)
                }
            }

            val hasEditable = nodes.any { it.editable && it.bounds?.isEmpty == false }
            if (hasEditable) {
                return@withTimeoutOrNull true
            }

            val ws = worldStateStore.current()
            val isSearchInput = ws.focusedEditableIdentity is com.amar.vault.agent.runtime.state.SemanticIdentity.SearchInput
            val pkgMatches = ws.focusedEditableIdentity?.packageId == targetPackage
            if (isSearchInput && pkgMatches) {
                return@withTimeoutOrNull true
            }

            delay(150L)
        }
        @Suppress("UNREACHABLE_CODE") false
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

    private fun findInputFocusedNode(svc: PerceptionService?): AccessibilityNodeInfo? {
        if (svc == null) return null
        try {
            val windows = svc.windows ?: emptyList()
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val root = try { w.root } catch (_: Throwable) { null } ?: continue
                root.refresh()
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) return focused

                val walked = walkForFocusedEditable(root)
                if (walked != null) return walked
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun walkForFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            try {
                node.refresh()
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

    private fun injectText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            node.refresh()
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val status = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (status) {
                node.refresh()
                return node.text?.toString()?.contains(text) == true
            }
            false
        } catch (_: Exception) { false }
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
        private const val USE_INJECTION_ENGINE = true
    }
}