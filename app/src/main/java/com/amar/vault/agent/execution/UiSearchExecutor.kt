package com.amar.vault.agent.execution

import android.content.Context
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
import com.amar.vault.agent.perception.UiSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 3 / Execution: search via UI automation.
 *
 * v4 changes (post-click verification):
 *   Step 3 now verifies that a click actually changed UI state by polling
 *   for an editable node after each "successful" click. Mechanical click
 *   success on a non-interactive view (e.g. WhatsApp's `search_bar`
 *   layout container) returns true at the a11y framework level but has
 *   no UI effect. Without verification, the executor stops at the first
 *   mechanical win and never tries the real search affordance.
 *
 *   Candidate order also reorders: content_desc "Search" tries before
 *   resource_id "search_bar" because content_desc tends to map to the
 *   real action-bar icon, while resource_id often matches header
 *   containers.
 */
@Singleton
class UiSearchExecutor @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val controlLayer: ControlLayer,
    private val snapshotCache: SnapshotCache
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

        // Step 2.5: deep poll for EITHER an editable input OR a search button.
        // Whichever appears first decides whether we bypass the click step.
        android.util.Log.i(TAG, "step 2.5 polling for input or search affordance...")
        var latestSnap: UiSnapshot? = settled
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
                    el.text?.contains("search", true) == true ||
                            el.contentDesc?.contains("search", true) == true ||
                            el.resourceId?.contains("search", true) == true
                }

                if (foundInput != null || foundSearchButtons.isNotEmpty()) {
                    return@withTimeoutOrNull true
                }
                delay(200L)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        android.util.Log.i(TAG, "step 2.5 done in ${System.currentTimeMillis() - pollStart}ms: " +
                "input=${foundInput?.resourceId ?: foundInput?.text ?: foundInput?.contentDesc}, " +
                "searchButtons=${foundSearchButtons.size}")

        // Stage 1: editable-already-visible bypass.
        val alreadyEditable = foundInput != null
        android.util.Log.i(TAG, "stage 1 already-editable bypass: $alreadyEditable")

        if (!alreadyEditable) {
            // Step 3: click search affordance — try multiple candidates with
            // POST-CLICK VERIFICATION. A mechanical click win that doesn't
            // produce an editable within VERIFY_TIMEOUT_MS is treated as
            // failed, and we move to the next candidate.
            //
            // Order: content_desc "Search" first (action-bar icon),
            // then resource ids, then text/auto fallbacks. This is because
            // resource_id `search_bar` in apps like WhatsApp matches a
            // non-interactive header container, while content_desc=Search
            // typically maps to the real magnifying-glass icon.
            val baseCandidates = listOf(
                AgentAction.Click(target = "Search", strategy = TargetStrategy.CONTENT_DESC),
                AgentAction.Click(target = "search_action_bar", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "menuitem_search", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "search_bar", strategy = TargetStrategy.RESOURCE_ID),
                AgentAction.Click(target = "Search settings", strategy = TargetStrategy.TEXT),
                AgentAction.Click(target = "search", strategy = TargetStrategy.AUTO)
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
                    android.util.Log.i(TAG, "step 3 candidate $idx failed reason: ${state.reason}")
                }
                android.util.Log.i(TAG, "step 3 candidate $idx " +
                        "(${candidate.target}/${candidate.strategy}) mechanical=$mechOk " +
                        "(took ${System.currentTimeMillis() - step3Start}ms)")

                if (!mechOk) continue

                // POST-CLICK VERIFICATION: did the UI actually change?
                // Poll for an editable node. If none appears within the
                // verify window, this click was a mechanical no-op and we
                // try the next candidate.
                val verifyStart = System.currentTimeMillis()
                val verified = withTimeoutOrNull(POST_CLICK_VERIFY_MS) {
                    while (true) {
                        val snap = svc?.forceSnapshot()
                        val hasEditable = snap?.elements?.any {
                            (it.editable || it.type == UiElementType.INPUT) &&
                                    it.bounds != null && !it.bounds.isEmpty
                        } == true
                        if (hasEditable) return@withTimeoutOrNull true
                        delay(150L)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } ?: false

                android.util.Log.i(TAG, "step 3 candidate $idx verified=$verified " +
                        "(verify took ${System.currentTimeMillis() - verifyStart}ms)")

                if (verified) {
                    clickedWith = "${candidate.target}/${candidate.strategy}"
                    break
                } else {
                    android.util.Log.i(TAG, "step 3 candidate $idx click was mechanical no-op; trying next")
                }
            }

            if (clickedWith == "none") {
                android.util.Log.w(TAG, "FAIL step 3: all click candidates failed verification")
                return ExecutionOutcome.Failed(
                    packageId = plan.packageId,
                    detail = "could not click search affordance (no editable appeared post-click)",
                    durationMs = System.currentTimeMillis() - started
                )
            }
            android.util.Log.i(TAG, "step 3 winner (verified): $clickedWith")

            // Step 4 retained as a final settling delay; the verification
            // loop already confirmed an editable exists.
            delay(FIELD_APPEAR_DELAY_MS)
        }

        // Step 5: type query. Cascade through selector candidates, ending
        // with FOCUSED_EDITABLE which is the universal fallback.
        val typeCandidates = listOf(
            AgentAction.TypeText(
                target = "search_src_text",
                text = plan.query,
                strategy = TargetStrategy.RESOURCE_ID,
                submit = true
            ),
            AgentAction.TypeText(
                target = "Search",
                text = plan.query,
                strategy = TargetStrategy.AUTO,
                submit = true
            ),
            AgentAction.TypeText(
                target = "Search...",
                text = plan.query,
                strategy = TargetStrategy.AUTO,
                submit = true
            ),
            AgentAction.TypeText(
                target = "(focused editable)",
                text = plan.query,
                strategy = TargetStrategy.FOCUSED_EDITABLE,
                submit = true
            )
        )

        var typedWith = "none"
        val step5Start = System.currentTimeMillis()
        for ((idx, candidate) in typeCandidates.withIndex()) {
            val state = submit(candidate)
            val ok = state is TaskState.Succeeded
            if (!ok && state is TaskState.Failed) {
                android.util.Log.i(TAG, "step 5 candidate $idx failed reason: ${state.reason}")
            }
            android.util.Log.i(TAG, "step 5 candidate $idx " +
                    "(${candidate.target}/${candidate.strategy}) → $ok " +
                    "(took ${System.currentTimeMillis() - step5Start}ms)")
            if (ok) {
                typedWith = "${candidate.target}/${candidate.strategy}"
                break
            }
        }

        if (typedWith == "none") {
            android.util.Log.w(TAG, "FAIL step 5: all type candidates failed")
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

    private suspend fun submit(action: AgentAction): TaskState {
        val envelope = ActionEnvelope(action = action)
        val taskCtx = controlLayer.submitEnvelope(envelope)
        return taskCtx.state.first { it.isTerminal }
    }

    companion object {
        private const val FIELD_APPEAR_DELAY_MS = 400L
        private const val POST_CLICK_VERIFY_MS = 2_500L
    }
}