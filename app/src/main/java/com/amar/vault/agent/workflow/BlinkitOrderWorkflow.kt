package com.amar.vault.agent.workflow

import com.amar.vault.agent.control.ControlLayer
import com.amar.vault.agent.control.TaskState
import com.amar.vault.agent.dsl.ActionEnvelope
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.dsl.TargetStrategy
import com.amar.vault.agent.observation.AgentStateObserver
import com.amar.vault.agent.observation.ScreenType
import com.amar.vault.agent.perception.SnapshotCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 7: Workflow abstraction + Blinkit order flow.
 *
 * A Workflow is a hardcoded state machine for completing a specific
 * multi-step task inside a specific app. It is deliberately app-specific —
 * we accept the maintenance cost of one workflow per critical flow in
 * exchange for reliability that a generic LLM-driven loop cannot match.
 */
interface Workflow {
    val id: String
    suspend fun run(query: String): WorkflowResult
}

sealed class WorkflowResult {
    data class Completed(val detail: String) : WorkflowResult()
    data class StoppedAtGate(val screen: ScreenType, val detail: String) : WorkflowResult()
    data class Failed(val detail: String, val atStep: String) : WorkflowResult()
}

/**
 * Registry of available workflows. CapabilityRouter stores a workflow id
 * string alongside each app; the coordinator resolves it here.
 */
@Singleton
class WorkflowRegistry @Inject constructor(
    blinkitOrderWorkflow: BlinkitOrderWorkflow
) {
    private val byId: Map<String, Workflow> = mapOf(
        blinkitOrderWorkflow.id to blinkitOrderWorkflow
    )

    fun get(id: String): Workflow? = byId[id]
}

/**
 * Hardcoded Blinkit ordering flow.
 */
@Singleton
class BlinkitOrderWorkflow @Inject constructor(
    private val controlLayer: ControlLayer,
    private val stateObserver: AgentStateObserver,
    private val snapshotCache: SnapshotCache
) : Workflow {

    override val id: String = "blinkit_order_v1"

    override suspend fun run(query: String): WorkflowResult {
        // Blinkit uses Grofers' legacy package id post-Zomato acquisition.
        val packageId = "com.grofers.customerapp"

        // Step 1: open app
        if (!submit(AgentAction.OpenApp(app = "Blinkit", packageId = packageId))) {
            return WorkflowResult.Failed("could not open Blinkit", "open_app")
        }

        // Step 2: wait for Blinkit window
        if (!awaitApp(packageId)) {
            return WorkflowResult.Failed("Blinkit did not become visible", "await_home")
        }
        stateObserver.observe("open_blinkit", true)
        checkGate(stateObserver.currentScreen())?.let { return it }

        // Step 3: focus search bar — TEXT then CONTENT_DESC fallback
        val focused = submit(
            AgentAction.Click(target = "Search", strategy = TargetStrategy.TEXT)
        ) || submit(
            AgentAction.Click(target = "Search", strategy = TargetStrategy.CONTENT_DESC)
        )

        if (!focused) {
            return WorkflowResult.Failed("could not focus search bar", "click_search")
        }

        delay(FIELD_SETTLE_MS)

        // Step 4: type query and submit via IME
        val typed = submit(
            AgentAction.TypeText(
                target = "Search",
                text = query,
                strategy = TargetStrategy.AUTO,
                submit = true
            )
        )

        if (!typed) {
            return WorkflowResult.Failed("could not type query", "type_query")
        }

        // Step 5: wait for results
        if (!awaitScreen(ScreenType.SEARCH_RESULTS, RESULTS_TIMEOUT_MS)) {
            return WorkflowResult.Failed("results did not appear", "await_results")
        }

        checkGate(stateObserver.currentScreen())?.let { return it }

        // Step 6: tap first product
        val tappedProduct = submit(
            AgentAction.Click(
                target = query,
                strategy = TargetStrategy.FIRST_CLICKABLE_IN_GRID
            )
        )

        if (!tappedProduct) {
            return WorkflowResult.Failed(
                "could not tap first product",
                "click_first_product"
            )
        }

        // Step 7: Add to cart
        val added = submit(
            AgentAction.Click(
                target = "Add to cart",
                strategy = TargetStrategy.TEXT
            )
        ) || submit(
            AgentAction.Click(
                target = "Add",
                strategy = TargetStrategy.TEXT
            )
        )

        if (!added) {
            return WorkflowResult.Failed(
                "could not add to cart",
                "click_add_to_cart"
            )
        }

        delay(CART_SETTLE_MS)
        stateObserver.observe("add_to_cart", true)

        // Step 8: open cart
        val openedCart = submit(
            AgentAction.Click(
                target = "View cart",
                strategy = TargetStrategy.TEXT
            )
        ) || submit(
            AgentAction.Click(
                target = "Cart",
                strategy = TargetStrategy.TEXT
            )
        ) || submit(
            AgentAction.Click(
                target = "Cart",
                strategy = TargetStrategy.CONTENT_DESC
            )
        )

        if (!openedCart) {
            return WorkflowResult.Failed(
                "could not open cart",
                "click_cart"
            )
        }

        if (!awaitScreen(ScreenType.CART, CART_TIMEOUT_MS)) {
            return WorkflowResult.Failed(
                "cart screen did not appear",
                "await_cart"
            )
        }

        checkGate(stateObserver.currentScreen())?.let { return it }

        // Step 9: proceed to checkout
        val proceeded = submit(
            AgentAction.Click(
                target = "Proceed to Pay",
                strategy = TargetStrategy.TEXT
            )
        ) || submit(
            AgentAction.Click(
                target = "Checkout",
                strategy = TargetStrategy.TEXT
            )
        ) || submit(
            AgentAction.Click(
                target = "Proceed",
                strategy = TargetStrategy.TEXT
            )
        )

        if (!proceeded) {
            return WorkflowResult.Failed(
                "could not proceed to checkout",
                "click_checkout"
            )
        }

        // Step 10: halt at payment
        if (awaitScreen(ScreenType.PAYMENT, PAYMENT_TIMEOUT_MS)) {
            return WorkflowResult.StoppedAtGate(
                screen = ScreenType.PAYMENT,
                detail = "arrived at payment screen — user action required"
            )
        }

        if (awaitScreen(ScreenType.CONFIRMATION_REQUIRED, PAYMENT_TIMEOUT_MS)) {
            return WorkflowResult.StoppedAtGate(
                screen = ScreenType.CONFIRMATION_REQUIRED,
                detail = "arrived at confirmation screen — user action required"
            )
        }

        return WorkflowResult.Failed(
            "did not reach payment screen",
            "await_payment"
        )
    }

    private suspend fun submit(action: AgentAction): Boolean {
        val envelope = ActionEnvelope(action = action)
        val ctx = controlLayer.submitEnvelope(envelope)
        val terminal = ctx.state.first { it.isTerminal }
        return terminal is TaskState.Succeeded
    }

    private suspend fun awaitApp(packageId: String): Boolean {

        val candidates = setOf(
            "com.grofers.customerapp",
            "app.blinkit.consumer"
        )

        val result = withTimeoutOrNull(APP_VISIBLE_TIMEOUT_MS) {

            while (true) {

                val s = snapshotCache.currentAnyAge()

                if (s != null && s.packageId in candidates) {
                    return@withTimeoutOrNull true
                }

                delay(POLL_INTERVAL_MS)
            }

            @Suppress("UNREACHABLE_CODE")
            false
        }

        // Accept on timeout — the OpenApp call already succeeded; snapshot may
        // just not have caught up yet.
        return result ?: true
    }

    private suspend fun awaitScreen(target: ScreenType, timeoutMs: Long): Boolean {

        val result = withTimeoutOrNull(timeoutMs) {

            while (true) {

                val w = stateObserver.observe()

                if (w.screen == target) {
                    return@withTimeoutOrNull true
                }

                delay(POLL_INTERVAL_MS)
            }

            @Suppress("UNREACHABLE_CODE")
            false
        }

        return result ?: false
    }

    private fun checkGate(screen: ScreenType): WorkflowResult.StoppedAtGate? =
        when (screen) {

            ScreenType.PAYMENT -> WorkflowResult.StoppedAtGate(
                screen,
                "unexpected early payment screen"
            )

            ScreenType.CONFIRMATION_REQUIRED -> WorkflowResult.StoppedAtGate(
                screen,
                "unexpected early confirmation gate"
            )

            else -> null
        }

    companion object {
        private const val APP_VISIBLE_TIMEOUT_MS = 6_000L
        private const val RESULTS_TIMEOUT_MS = 5_000L
        private const val CART_TIMEOUT_MS = 5_000L
        private const val PAYMENT_TIMEOUT_MS = 8_000L
        private const val POLL_INTERVAL_MS = 200L
        private const val FIELD_SETTLE_MS = 600L
        private const val CART_SETTLE_MS = 400L
    }
}