package com.amar.vault.agent.execution

import android.content.Context
import com.amar.vault.agent.capability.ExecutionPlan
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.control.executors.ClickExecutor
import com.amar.vault.agent.control.executors.OpenAppExecutor
import com.amar.vault.agent.control.executors.TypeTextExecutor
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.dsl.TargetStrategy
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.SnapshotCache
import com.amar.vault.agent.perception.UiReadinessWaiter
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 3 / Execution: search via UI automation.
 *
 * Used when NativeSearchExecutor reports NotSupported. Flow:
 *   1. Launch the app (OpenAppExecutor)
 *   2. Wait for the window via ActivityManager / snapshot
 *   3. Wait for the actual UI tree to populate via UiReadinessWaiter
 *      (dynamic polling — adapts to device speed, no fixed delay)
 *   4. Try a list of search-affordance selectors; first hit wins
 *   5. Type the query into the resulting input field, trying multiple
 *      selectors in order; first hit wins
 *
 * The cascading selectors are a stopgap until we build the semantic
 * grounding layer. They cover Settings-class apps (resource_id), apps
 * with explicit "Search" content descriptions, and the generic AUTO
 * fallback. We log which selector won so we can track patterns over
 * time and prioritize Tier 1 (native intent) coverage for repeat offenders.
 */
@Singleton
class UiSearchExecutor @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val openAppExecutor: OpenAppExecutor,
    private val clickExecutor: ClickExecutor,
    private val typeTextExecutor: TypeTextExecutor,
    private val snapshotCache: SnapshotCache
) {

    suspend fun execute(plan: ExecutionPlan.UiSearch, ctx: TaskContext): ExecutionOutcome {
        val started = System.currentTimeMillis()
        val TAG = "UiSearchExecutor"
        android.util.Log.i(TAG, "execute() pkg=${plan.packageId} query='${plan.query}'")

        // Step 1: open the app
        val openAction = AgentAction.OpenApp(
            app = plan.packageId,
            packageId = plan.packageId
        )

        val openResult = runCatching {
            openAppExecutor.execute(openAction, ctx)
        }.getOrNull()

        android.util.Log.i(TAG, "step 1 open result: ${openResult?.javaClass?.simpleName}")

        if (openResult == null ||
            (openResult !is com.amar.vault.agent.control.ExecutionResult.Executed &&
                    openResult !is com.amar.vault.agent.control.ExecutionResult.ExecutedAndVerified)) {
            android.util.Log.w(TAG, "FAIL step 1: $openResult")
            return ExecutionOutcome.Failed(
                packageId = plan.packageId,
                detail = "failed to open app before ui-search",
                durationMs = System.currentTimeMillis() - started
            )
        }

        // Step 2: wait for the app window (process foreground)
        val ready = awaitAppVisible(plan.packageId)
        android.util.Log.i(TAG, "step 2 app visible: $ready")
        if (!ready) {
            android.util.Log.w(TAG, "FAIL step 2: app not visible")
            return ExecutionOutcome.Failed(
                packageId = plan.packageId,
                detail = "app did not become visible within timeout",
                durationMs = System.currentTimeMillis() - started
            )
        }

        // Step 3: dynamic-wait for the UI tree to populate.
        // Replaces the previous hardcoded delay — adapts to device speed.
        val svc = PerceptionService.get()
        val settledSnap = svc?.let {
            UiReadinessWaiter.waitForUi(
                svc = it,
                targetPackage = plan.packageId,
                timeoutMs = 4_000L,
                minElements = 5
            )
        }
        if (settledSnap == null) {
            android.util.Log.w(TAG, "step 3 UI never settled for ${plan.packageId}, proceeding anyway")
        } else {
            android.util.Log.i(TAG, "step 3 UI settled: ${settledSnap.size} elements")
        }

        // Step 4: click a search affordance. Try multiple selector strategies
        // in order. Resource-id wins for system apps (Settings), text wins
        // for apps that label the bar, AUTO is the generic fallback.
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

        var clickResult: com.amar.vault.agent.control.ExecutionResult? = null
        var clickWhich = "none"

        for ((idx, candidate) in clickCandidates.withIndex()) {
            val r = runCatching {
                clickExecutor.execute(candidate, ctx)
            }.getOrNull()

            android.util.Log.i(
                TAG,
                "step 4 candidate $idx (${candidate.target}/${candidate.strategy}) → ${r?.javaClass?.simpleName}"
            )

            if (r is com.amar.vault.agent.control.ExecutionResult.Executed ||
                r is com.amar.vault.agent.control.ExecutionResult.ExecutedAndVerified) {
                clickResult = r
                clickWhich = "${candidate.target}/${candidate.strategy}"
                break
            }
        }

        android.util.Log.i(TAG, "step 4 winner: $clickWhich")

        if (clickResult == null) {
            android.util.Log.w(TAG, "FAIL step 4: all click candidates failed")
            return ExecutionOutcome.Failed(
                packageId = plan.packageId,
                detail = "could not click search affordance",
                durationMs = System.currentTimeMillis() - started
            )
        }

        // Step 5: settle briefly for the input field to appear, then poll
        // until at least one editable node is present.
        delay(FIELD_APPEAR_DELAY_MS)

        val typeReady = withTimeoutOrNull(FIELD_APPEAR_TIMEOUT_MS) {
            while (true) {
                val snap = svc?.forceSnapshot()
                if (snap != null && snap.elements.any { it.editable }) return@withTimeoutOrNull true
                delay(150L)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
        android.util.Log.i(TAG, "step 5 input field appeared: $typeReady")

        // Step 6: type query, trying multiple selector strategies. resource_id
        // for system apps (search_src_text is a near-universal id), then
        // text/hint fallbacks for app-specific labels.
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

        var typeResult: com.amar.vault.agent.control.ExecutionResult? = null
        var typeWhich = "none"

        for ((idx, candidate) in typeCandidates.withIndex()) {
            val r = runCatching {
                typeTextExecutor.execute(candidate, ctx)
            }.getOrNull()

            android.util.Log.i(
                TAG,
                "step 6 candidate $idx (${candidate.target}/${candidate.strategy}) → ${r?.javaClass?.simpleName}"
            )

            if (r is com.amar.vault.agent.control.ExecutionResult.Executed ||
                r is com.amar.vault.agent.control.ExecutionResult.ExecutedAndVerified) {
                typeResult = r
                typeWhich = "${candidate.target}/${candidate.strategy}"
                break
            }
        }

        android.util.Log.i(TAG, "step 6 winner: $typeWhich")

        if (typeResult == null) {
            android.util.Log.w(TAG, "FAIL step 6: all type candidates failed")
            return ExecutionOutcome.Failed(
                packageId = plan.packageId,
                detail = "could not type query into field",
                durationMs = System.currentTimeMillis() - started
            )
        }

        android.util.Log.i(TAG, "execute() SUCCESS in ${System.currentTimeMillis() - started}ms")
        return ExecutionOutcome.Started(
            packageId = plan.packageId,
            durationMs = System.currentTimeMillis() - started,
            route = "ui_search"
        )
    }

    /**
     * Wait for the target package to be foreground. Tries ActivityManager
     * first (works on modern Android for the recently-launched app),
     * snapshot cache second. Accepts on timeout because the OpenApp call
     * already returned success — we just couldn't confirm via signals.
     */
    private suspend fun awaitAppVisible(packageId: String): Boolean {
        val result = withTimeoutOrNull(APP_VISIBLE_TIMEOUT_MS) {
            while (true) {
                if (isPackageForeground(packageId)) return@withTimeoutOrNull true

                val snap = snapshotCache.currentAnyAge()
                if (snap != null && snap.packageId == packageId) {
                    return@withTimeoutOrNull true
                }

                delay(POLL_INTERVAL_MS)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        return result ?: true
    }

    private fun isPackageForeground(packageName: String): Boolean {
        return try {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                    as? android.app.ActivityManager
                ?: return false
            val processes = am.runningAppProcesses ?: return false
            processes.any { proc ->
                proc.importance ==
                        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        proc.processName.startsWith(packageName)
            }
        } catch (t: Throwable) {
            false
        }
    }

    companion object {
        private const val APP_VISIBLE_TIMEOUT_MS = 4_000L
        private const val POLL_INTERVAL_MS = 150L
        private const val FIELD_APPEAR_DELAY_MS = 400L
        private const val FIELD_APPEAR_TIMEOUT_MS = 2_500L
    }
}