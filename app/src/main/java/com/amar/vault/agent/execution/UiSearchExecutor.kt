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
    private val environmentVerifier: com.amar.vault.agent.runtime.environment.EnvironmentVerifier,
    private val replayRecorder: com.amar.vault.agent.replay.ReplayRecorder
) {

    /**
     * Records a step boundary: a marker frame and (optionally) a snapshot
     * of the current UI. Per Option B (step-level recording), we capture
     * snapshots only at meaningful step transitions, not every walk.
     */
    private fun recordStep(step: String, detail: String? = null, snapshot: com.amar.vault.agent.perception.UiSnapshot? = null) {
        val ts = replayRecorder.timeSinceStartMs()
        replayRecorder.recordFrame(
            com.amar.vault.agent.replay.ReplayFrame.StepMarker(
                tsMs = ts,
                step = step,
                detail = detail
            )
        )
        if (snapshot != null) {
            replayRecorder.recordFrame(
                com.amar.vault.agent.replay.ReplayFrame.Snapshot(
                    tsMs = ts,
                    snapshotId = java.util.UUID.randomUUID().toString(),
                    packageId = snapshot.packageId,
                    elementCount = snapshot.elements.size,
                    elements = snapshot.elements.map {
                        com.amar.vault.agent.replay.SerializableUiElement.from(it)
                    }
                )
            )
        }
    }

    suspend fun execute(plan: ExecutionPlan.UiSearch, ctx: TaskContext): ExecutionOutcome {
        val workflowStart = System.currentTimeMillis()

        // Phase 5 Step 5a: begin tracking target liveness via accessibility
        // events. PerceptionService.onAccessibilityEvent updates the
        // heartbeat timestamp; the predicate isTargetDead() is wired in
        // Step 5b. Tracking is best-effort — if start fails, the workflow
        // proceeds normally.
        val trackedPkg = plan.packageId.substringBefore("#")
        try {
            worldStateStore.startTrackingTarget(trackedPkg)
        } catch (t: Throwable) {
            android.util.Log.w("UiSearchExecutor", "startTrackingTarget failed: ${t.message}")
        }

        try {
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
        } finally {
            // Always stop tracking on exit (success, fail, exception). The
            // breaker becomes inert and isTargetDead() returns false.
            try {
                worldStateStore.stopTrackingTarget()
            } catch (t: Throwable) {
                android.util.Log.w("UiSearchExecutor", "stopTrackingTarget failed: ${t.message}")
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
        recordStep("step_1_open", detail = "opened=$opened")
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
                minElements = 10 // Phase 4: raised from 1 to 10 to avoid settling
                // on splash-screen states (Rapido shows a single "Launching..."
                // image as element #1, then takes 9+ seconds to render real UI).
                // Most real app screens have well over 10 elements; this catches
                // splash/loading/intro screens and waits for the real surface.
            )
        }
        android.util.Log.i(TAG, "step 2 settled: ${settled?.packageId} elements=${settled?.size}")
        recordStep("step_2_settled", detail = "elements=${settled?.size}", snapshot = settled)

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
                    el.editable && el.bounds != null && !el.bounds.isEmpty &&
                            // Skip Gmail's open_search shim (clickable button labeled
                            // editable but typing fails until expanded).
                            el.resourceId?.contains("open_search") != true &&
                            // Skip Gemini's collapsed-text placeholder. It's editable=true
                            // but typing into it doesn't work — must be clicked first to
                            // expand into a real EditText.
                            el.resourceId?.contains("collapsed_text") != true
                }

                foundSearchButtons = nodes.filter { el ->
                    val isSearchSemantic =
                        el.contentDesc?.contains("search", true) == true ||
                                el.contentDesc?.contains("keyboard", true) == true ||
                                el.text?.contains("Type", true) == true ||
                                el.resourceId?.contains("search", true) == true
                    // Phase 6: map-first destination-entry surfaces use a
                    // non-editable Button labeled with location semantics
                    // (e.g. Rapido's "Where are you going?"). Gate on
                    // package allowlist to avoid false-positives in chat apps.
                    val isMapDestinationSemantic = realPkg in MAP_APP_PACKAGES && (
                            el.text?.let { t -> MAP_DESTINATION_PHRASES.any { t.contains(it, true) } } == true ||
                                    el.contentDesc?.let { d -> MAP_DESTINATION_PHRASES.any { d.contains(it, true) } } == true
                            )
                    (isSearchSemantic || isMapDestinationSemantic) && !isNonSearchElement(el)
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
        android.util.Log.i(TAG, "stage 1 already-editable bypass: $alreadyEditable " +
                "match=${foundInput?.let { "rid=${it.resourceId} type=${it.type} text='${it.text?.take(30)}' cd='${it.contentDesc?.take(30)}'" }}")
        recordStep("step_2.7_polled",
            detail = "alreadyEditable=$alreadyEditable foundInput=${foundInput?.resourceId}",
            snapshot = svc?.forceSnapshot())

        // Phase 5 Step 5b: circuit-breaker predicate check. The heartbeat
        // tracker (PerceptionService.onAccessibilityEvent → WorldStateStore.
        // updateTargetHeartbeat) updates lastTargetSeenAtMillis on every
        // event from the target package. If we get here and the heartbeat
        // hasn't pulsed in >1.2s, the target app is dead/unrendered and
        // continuing into step 3 will waste ~40s on doomed clicks. Abort
        // cleanly with APP_CRASH_DURING_PERCEPTION classification.
        //
        // Unlike the earlier process-health attempt (which used
        // getRunningAppProcesses, blocked by Android 11+ security), this
        // uses ONLY accessibility events that are already streaming. No
        // privileged permissions, no false positives on system overlays
        // (the passlist in WorldStateStore handles keyboards/permission
        // sheets/system UI).
        if (worldStateStore.isTargetDead()) {
            val interrupter = worldStateStore.current().lastInterruptingPackage
            android.util.Log.w(TAG,
                "circuit-breaker: target $realPkg heartbeat absent — " +
                        "lastInterruptingPackage=$interrupter")
            return ExecutionOutcome.Failed(
                packageId = plan.packageId,
                detail = "app_crash_during_perception: $realPkg heartbeat absent " +
                        "(>1.2s), lastInterrupter=$interrupter",
                durationMs = System.currentTimeMillis() - started
            )
        }

        // Phase 4a: Semantic environment verification.
        // For multi-surface packages (Gemini/Search in Quicksearchbox, etc),
        // structural "an editable exists" is not enough — we must verify the
        // ACTIVE environment matches the user's target intent.
        // Multi-environment package detection: any package containing apps
        // with distinct semantic surfaces (Gemini vs Search inside googlequicksearchbox,
        // Uber rides vs Eats, Amazon search vs checkout) gets routed through the
        // verifier. The verifier picks the correct environment by signal match
        // and rejects if forbidden signals fire.
        val targetEnvName: String? = when {
            plan.packageId == "com.google.android.apps.bard" -> "GeminiConversation"
            plan.packageId == "com.google.android.googlequicksearchbox" -> "GeminiConversation"
            plan.packageId.endsWith("#gemini") -> "GeminiConversation"
            else -> null
        }
        if (targetEnvName != null) {
            val targetEnv = environmentVerifier.findByName(targetEnvName)
            if (targetEnv != null) {
                // Step 1 of recovery roadmap: state-aware verification.
                // We classify into STABLE/TRANSITIONING/AMBIGUOUS/WRONG_ENV/UNKNOWN
                // and branch accordingly. For TRANSITIONING we wait & retry.
                // Recovery actions (Step 2) wired in next session.
                val verifyResult = retryUntilStable(targetEnv, maxTries = 4)
                when (verifyResult.state) {
                    com.amar.vault.agent.runtime.environment.EnvironmentVerifier.EnvironmentState.STABLE -> {
                        android.util.Log.i(TAG,
                            "ENV_VERIFIED '${targetEnvName}' " +
                                    "conf=${"%.2f".format(verifyResult.targetConfidence)}")
                    }
                    com.amar.vault.agent.runtime.environment.EnvironmentVerifier.EnvironmentState.WRONG_ENVIRONMENT,
                    com.amar.vault.agent.runtime.environment.EnvironmentVerifier.EnvironmentState.TRANSITIONING,
                    com.amar.vault.agent.runtime.environment.EnvironmentVerifier.EnvironmentState.UNKNOWN -> {
                        android.util.Log.w(TAG,
                            "ENV_MISMATCH state=${verifyResult.state} target='${targetEnvName}' " +
                                    "winner='${verifyResult.winningEnvironment?.name}' " +
                                    "conf=${"%.2f".format(verifyResult.targetConfidence)} — attempting recovery")

                        val recovered = attemptEnvironmentRecovery(targetEnv)
                        if (recovered.state ==
                            com.amar.vault.agent.runtime.environment.EnvironmentVerifier.EnvironmentState.STABLE) {
                            android.util.Log.i(TAG,
                                "ENV_RECOVERED via recovery — conf=${"%.2f".format(recovered.targetConfidence)}")
                        } else {
                            android.util.Log.w(TAG,
                                "ENV_RECOVERY_FAILED final_state=${recovered.state} " +
                                        "conf=${"%.2f".format(recovered.targetConfidence)}")
                            return ExecutionOutcome.Failed(
                                packageId = plan.packageId,
                                detail = "env_recovery_failed:state=${recovered.state} " +
                                        "conf=${"%.2f".format(recovered.targetConfidence)}",
                                durationMs = System.currentTimeMillis() - started
                            )
                        }
                    }
                    com.amar.vault.agent.runtime.environment.EnvironmentVerifier.EnvironmentState.AMBIGUOUS -> {
                        android.util.Log.w(TAG,
                            "ENV_AMBIGUOUS target='${targetEnvName}' " +
                                    "confidences=${verifyResult.allConfidences}")
                        return ExecutionOutcome.Failed(
                            packageId = plan.packageId,
                            detail = "ambiguous_environment:${verifyResult.allConfidences}",
                            durationMs = System.currentTimeMillis() - started
                        )
                    }
                }
            }
        }

        if (!alreadyEditable) {
            // Phase 4 stability fix: prioritize candidates likely to win for
            // this package first. The full candidate set is preserved as
            // fallback; we just reorder so each app hits its known winner
            // within the first ~2 candidates instead of position 5-7.
            // Reordering proven from replay analyzer (e.g. Gmail wasted 11s
            // hitting Gemini candidates before reaching search_bar).
            val priorityTargetsForPackage: List<Pair<String, TargetStrategy>> = when {
                realPkg.contains("bard") || realPkg.contains("googlequicksearchbox") ->
                    listOf(
                        "Ask Gemini" to TargetStrategy.TEXT,
                        "assistant_robin_input_collapsed_text_half_sheet" to TargetStrategy.RESOURCE_ID
                    )
                realPkg == "com.google.android.gm" ->
                    listOf(
                        "search_bar" to TargetStrategy.RESOURCE_ID,
                        "open_search" to TargetStrategy.RESOURCE_ID
                    )
                realPkg == "com.whatsapp" ->
                    listOf(
                        "Search" to TargetStrategy.CONTENT_DESC,
                        "my_search_bar" to TargetStrategy.RESOURCE_ID
                    )
                realPkg == "org.telegram.messenger" ->
                    listOf("Search" to TargetStrategy.CONTENT_DESC)
                realPkg == "com.openai.chatgpt" ->
                    listOf("Search" to TargetStrategy.CONTENT_DESC)
                realPkg == "com.rapido.passenger" ->
                    listOf("Where are you going?" to TargetStrategy.CONTENT_DESC)
                realPkg == "com.pinterest" ->
                    listOf(
                        "menu_search" to TargetStrategy.RESOURCE_ID,
                        "Search" to TargetStrategy.CONTENT_DESC
                    )
                else -> emptyList()
            }

            val baseCandidates = listOf(
                // Gemini-specific candidates first — when target env is Gemini,
                // these resolve in ~150ms vs 18s of generic candidate timeouts.
                AgentAction.Click(target = "Ask Gemini", strategy = TargetStrategy.TEXT),
                AgentAction.Click(target = "Search", strategy = TargetStrategy.CONTENT_DESC),
                AgentAction.Click(target = "search_action_bar", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "menuitem_search", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "search_bar", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "my_search_bar", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "Search settings", strategy = TargetStrategy.TEXT),

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

            // Prepend priority candidates so they're tried before the generic
            // cascade; distinctBy preserves only the first occurrence.
            val priorityCandidates = priorityTargetsForPackage.map { (target, strategy) ->
                AgentAction.Click(target = target, strategy = strategy)
            }
            val clickCandidates = (priorityCandidates + baseCandidates + dynamicCandidates)
                .distinctBy { it.target }
            var clickedWith = "none"
            val step3Start = System.currentTimeMillis()

            for ((idx, candidate) in clickCandidates.withIndex()) {
                val state = submit(candidate)
                val mechOk = state is TaskState.Succeeded

                if (!mechOk && state is TaskState.Failed) {
                    android.util.Log.i(TAG, "step 3 candidate $idx failed: ${state.reason}")
                }

                // Phase 6: when accessibility resolved the target but refused
                // ACTION_CLICK because the node has no clickable ancestor
                // (Rapido's "Where are you going?" button — Compose pointer-
                // input handler isn't reflected in the a11y clickable flag),
                // escalate directly to gesture-tap instead of skipping. The
                // existing gesture-tap fallback below would never run because
                // it's gated on mechOk=true.
                val isUnclickableTarget = !mechOk &&
                        state is TaskState.Failed &&
                        (state.reason as? com.amar.vault.agent.control.FailureReason.TargetNotFound)
                            ?.strategiesTried?.contains("no_clickable_ancestor") == true
                if (isUnclickableTarget) {
                    android.util.Log.i(TAG, "step 3 candidate $idx unclickable; escalating to gesture tap")
                    val gestureAction = AgentAction.GestureTap(target = candidate.target, strategy = candidate.strategy)
                    val gestureState = submit(gestureAction)
                    if (gestureState is TaskState.Succeeded) {
                        val gestureVerified = waitForEditableOnly(svc, POST_CLICK_VERIFY_MS, realPkg)
                        if (gestureVerified) {
                            android.util.Log.i(TAG, "step 3 candidate $idx VERIFIED via escalated gesture tap")
                            clickedWith = "${candidate.target}/${candidate.strategy}/gesture-escalated"
                            break
                        }
                    }
                    continue
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
            recordStep("step_3_winner", detail = clickedWith, snapshot = svc?.forceSnapshot())
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
                recordStep("step_5_SUCCESS", detail = typedWith)

                // Press ENTER to submit the query. ACTION_IME_ENTER is the
                // canonical accessibility action for "submit current input"
                // and works on most modern apps. Available since Android 30.
                try {
                    delay(150L)  // let text settle into the field
                    val submitted = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        focusedNode.performAction(
                            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                        )
                    } else false
                    android.util.Log.i(TAG, "step 5 submit ACTION_IME_ENTER=$submitted")

                    // ACTION_IME_ENTER works for many apps but Compose-based
                    // surfaces (Gemini, ChatGPT) ignore it and require an
                    // explicit Send button click. Try a few common Send-button
                    // affordances after a small settle delay.
                    //
                    // Phase 4 stability fix: only run the send cascade for
                    // chat/messaging apps that actually HAVE a send button.
                    // For search-bar apps (Gmail, Twitter, Instagram via UI,
                    // generic search surfaces) IME_ENTER already submitted;
                    // the cascade just wastes ~3s per candidate timing out
                    // looking for buttons that don't exist.
                    val needsSendButton =
                        realPkg.contains("whatsapp") ||
                                realPkg.contains("telegram") ||
                                realPkg.contains("messenger") ||
                                realPkg.contains("chatgpt") ||
                                realPkg.contains("openai") ||
                                realPkg.contains("bard") ||
                                realPkg.contains("googlequicksearchbox") ||
                                realPkg.contains("signal") ||
                                realPkg.contains("slack") ||
                                realPkg.contains("discord")
                    if (needsSendButton) {
                        delay(200L)
                        val sendCandidates = listOf(
                            AgentAction.Click(target = "Send", strategy = TargetStrategy.CONTENT_DESC),
                            AgentAction.Click(target = "Send message", strategy = TargetStrategy.CONTENT_DESC),
                            AgentAction.Click(target = "Submit", strategy = TargetStrategy.CONTENT_DESC),
                            AgentAction.Click(target = "send_btn", strategy = TargetStrategy.RESOURCE_ID),
                            AgentAction.Click(target = "send_button", strategy = TargetStrategy.RESOURCE_ID)
                        )
                        var sentBy: String? = null
                        for (cand in sendCandidates) {
                            val st = submit(cand)
                            if (st is TaskState.Succeeded) {
                                sentBy = "${cand.target}/${cand.strategy}"
                                break
                            }
                        }
                        android.util.Log.i(TAG, "step 5 send_button=$sentBy")
                    } else {
                        android.util.Log.i(TAG, "step 5 send_button=skipped (search-bar app, IME_ENTER sufficient)")
                    }
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "step 5 submit threw: ${t.message}")
                }
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
    /**
     * Invokes the target environment's recovery strategy and re-verifies.
     *
     * For LaunchComponent: launches a specific Activity component (used
     * when the generic intent landed in the wrong surface — e.g. ACTION_ASSIST
     * opening Google Search instead of Gemini's MainActivity).
     *
     * For SendIntent: fires an Android Intent (alternative deep-link path).
     *
     * For TapAffordance: clicks an in-UI button (e.g. mode-switch tab).
     *
     * After recovery action: waits 600ms for UI to settle, then runs
     * verifier again. Returns the post-recovery verification result.
     */
    private suspend fun attemptEnvironmentRecovery(
        targetEnv: com.amar.vault.agent.runtime.environment.SemanticEnvironment
    ): com.amar.vault.agent.runtime.environment.EnvironmentVerifier.VerificationResult {
        val recovery = targetEnv.recoveryStrategy()
        if (recovery == null) {
            android.util.Log.w("UiSearchExecutor", "RECOVERY no_strategy for env=${targetEnv.name}")
            return environmentVerifier.verify(targetEnv)
        }

        android.util.Log.i("UiSearchExecutor", "RECOVERY invoking strategy=$recovery for env=${targetEnv.name}")

        try {
            when (recovery) {
                is com.amar.vault.agent.runtime.environment.EnvironmentRecovery.LaunchComponent -> {
                    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                        component = android.content.ComponentName(recovery.packageName, recovery.className)
                        addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                is com.amar.vault.agent.runtime.environment.EnvironmentRecovery.SendIntent -> {
                    val intent = android.content.Intent(recovery.action).apply {
                        setPackage(recovery.packageName)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                is com.amar.vault.agent.runtime.environment.EnvironmentRecovery.TapAffordance -> {
                    val strategy = if (recovery.byText) TargetStrategy.TEXT else TargetStrategy.RESOURCE_ID
                    submit(AgentAction.Click(target = recovery.target, strategy = strategy))
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("UiSearchExecutor", "RECOVERY threw: ${t.message}")
        }

        // Let the new surface render before re-verifying.
        delay(800L)
        val result = retryUntilStable(targetEnv, maxTries = 4)
        android.util.Log.i("UiSearchExecutor", "RECOVERY_RESULT state=${result.state} " +
                "conf=${"%.2f".format(result.targetConfidence)}")
        return result
    }

    private suspend fun retryUntilStable(
        targetEnv: com.amar.vault.agent.runtime.environment.SemanticEnvironment,
        maxTries: Int
    ): com.amar.vault.agent.runtime.environment.EnvironmentVerifier.VerificationResult {
        var last = environmentVerifier.verify(targetEnv)
        var tries = 1
        while (tries < maxTries &&
            last.state == com.amar.vault.agent.runtime.environment.EnvironmentVerifier.EnvironmentState.TRANSITIONING) {
            delay(250L)
            last = environmentVerifier.verify(targetEnv)
            tries++
        }
        android.util.Log.i("UiSearchExecutor", "retryUntilStable tries=$tries final_state=${last.state}")
        return last
    }

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

        // Phase 6: map-first ride/navigation apps where the home screen shows
        // a non-editable Button styled as a search bar ("Where are you going?").
        // The button triggers an activity transition into a real editable
        // surface. priorityTargetsForPackage already clicks these; this set
        // gates the step 2.7 filter extension that lets the predicate
        // recognize the trigger as a search affordance.
        private val MAP_APP_PACKAGES = setOf(
            "com.rapido.passenger",
            "com.olacabs.customer",
            "com.ubercab",
            "com.google.android.apps.maps"
        )
        private val MAP_DESTINATION_PHRASES = listOf(
            "where are you going", "where to", "destination",
            "drop", "pickup", "pick up", "drop off"
        )
    }
}