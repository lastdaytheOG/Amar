package com.amar.vault.agent.execution

import android.content.Context
import com.amar.vault.agent.capability.ExecutionPlan
import com.amar.vault.agent.control.ControlLayer
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.control.TaskState
import com.amar.vault.agent.control.executors.ClickExecutor
import com.amar.vault.agent.control.executors.OpenAppExecutor
import com.amar.vault.agent.control.executors.TypeTextExecutor
import com.amar.vault.agent.dsl.ActionEnvelope
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.dsl.TargetStrategy
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.SnapshotCache
import com.amar.vault.agent.perception.UiReadinessWaiter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 3 / Execution: search via UI automation.
 *
 * Key design point — every action goes through ControlLayer.submitEnvelope,
 * not direct executor calls. This gives us:
 *   - Retry policy on transient failures
 *   - Verification step after each action
 *   - Fresh snapshot guarantees between steps
 *   - Watchlist refcount management
 *   - Checkpoint persistence (helps debugging)
 *
 * The previous version called executors directly — that bypassed the
 * entire ControlLayer machinery and clicks failed instantly when the
 * snapshot cache wasn't fresh. This version mirrors the pattern used
 * by AgentDebugViewModel's preset functions, which we already proved
 * work reliably against Settings.
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

        // Step 1: open the app via ControlLayer (gets watchlist, retry, verify)
        val opened = submit(
            AgentAction.OpenApp(app = plan.packageId, packageId = plan.packageId)
        )
        android.util.Log.i(TAG, "step 1 open: $opened")
        if (!opened) {
            return ExecutionOutcome.Failed(
                packageId = plan.packageId,
                detail = "failed to open app before ui-search",
                durationMs = System.currentTimeMillis() - started
            )
        }

        // Step 2: wait for the UI tree to actually populate
        val svc = PerceptionService.get()
        val settled = svc?.let {
            UiReadinessWaiter.waitForUi(
                svc = it,
                targetPackage = plan.packageId,
                timeoutMs = 4_000L,
                minElements = 5
            )
        }
        android.util.Log.i(TAG, "step 2 settled: ${settled?.packageId} elements=${settled?.size}")
        if (settled == null) {
            android.util.Log.w(TAG, "step 2 timed out, proceeding anyway")
        }

        // Step 3: click search affordance — try candidates in order
        val clickCandidates = listOf(
            AgentAction.Click(
                target = "search_action_bar",
                strategy = TargetStrategy.RESOURCE_ID
            ),
            AgentAction.Click(
                target = "Search settings",
                strategy = TargetStrategy.TEXT
            ),
            AgentAction.Click(
                target = "Search",
                strategy = TargetStrategy.CONTENT_DESC
            ),
            AgentAction.Click(
                target = "search",
                strategy = TargetStrategy.AUTO
            )
        )

        var clickedWith = "none"
        for ((idx, candidate) in clickCandidates.withIndex()) {
            val ok = submit(candidate)
            android.util.Log.i(TAG, "step 3 candidate $idx (${candidate.target}/${candidate.strategy}) → $ok")
            if (ok) {
                clickedWith = "${candidate.target}/${candidate.strategy}"
                break
            }
        }

        if (clickedWith == "none") {
            android.util.Log.w(TAG, "FAIL step 3: all click candidates failed")
            return ExecutionOutcome.Failed(
                packageId = plan.packageId,
                detail = "could not click search affordance",
                durationMs = System.currentTimeMillis() - started
            )
        }
        android.util.Log.i(TAG, "step 3 winner: $clickedWith")

        // Step 4: wait for input field to appear
        delay(FIELD_APPEAR_DELAY_MS)
        val typeReady = withTimeoutOrNull(FIELD_APPEAR_TIMEOUT_MS) {
            while (true) {
                val snap = svc?.forceSnapshot()
                if (snap != null && snap.elements.any { it.editable }) {
                    return@withTimeoutOrNull true
                }
                delay(150L)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
        android.util.Log.i(TAG, "step 4 input field appeared: $typeReady")

        // Step 5: type query — try multiple selectors
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
            )
        )

        var typedWith = "none"
        for ((idx, candidate) in typeCandidates.withIndex()) {
            val ok = submit(candidate)
            android.util.Log.i(TAG, "step 5 candidate $idx (${candidate.target}/${candidate.strategy}) → $ok")
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

    /**
     * Submit an action via ControlLayer and wait for terminal state.
     * Returns true on Succeeded, false otherwise.
     */
    private suspend fun submit(action: AgentAction): Boolean {
        val envelope = ActionEnvelope(action = action)
        val taskCtx = controlLayer.submitEnvelope(envelope)
        val terminal = taskCtx.state.first { it.isTerminal }
        return terminal is TaskState.Succeeded
    }

    companion object {
        private const val FIELD_APPEAR_DELAY_MS = 400L
        private const val FIELD_APPEAR_TIMEOUT_MS = 2_500L
    }
}