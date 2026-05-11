package com.amar.vault.agent.observation

import com.amar.vault.agent.perception.SnapshotCache
import com.amar.vault.agent.perception.UiSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 5: Observation + Minimal State.
 *
 * Gives the coordinator a simple world model:
 *   { current_app, screen_type, last_action, last_succeeded }
 *
 * `screen_type` is classified by scanning the latest UI snapshot for
 * characteristic text cues. Deliberately coarse (5-6 categories) — enough
 * for stop-condition detection and workflow progress tracking without
 * needing full accessibility-tree understanding.
 *
 * Why not LLM-driven screen classification:
 *   Text cues are fast (<5ms), deterministic, and cheap. LLM classification
 *   would add ~2s per observation and introduce non-determinism into a
 *   control-flow decision.
 */
@Singleton
class AgentStateObserver @Inject constructor(
    private val snapshotCache: SnapshotCache
) {

    private val _state = MutableStateFlow(AgentWorldState.EMPTY)
    val state: StateFlow<AgentWorldState> = _state

    fun currentScreen(): ScreenType = _state.value.screen

    fun observe(
        lastAction: String? = null,
        lastSucceeded: Boolean = true
    ): AgentWorldState {
        val snap = snapshotCache.currentAnyAge()

        val next = if (snap == null) {
            AgentWorldState(
                currentApp = _state.value.currentApp,
                screen = ScreenType.UNKNOWN,
                lastAction = lastAction ?: _state.value.lastAction,
                lastSucceeded = lastSucceeded
            )
        } else {
            AgentWorldState(
                currentApp = snap.packageId,
                screen = classify(snap),
                lastAction = lastAction ?: _state.value.lastAction,
                lastSucceeded = lastSucceeded
            )
        }

        _state.value = next
        return next
    }

    private fun classify(snap: UiSnapshot): ScreenType {
        val textsLower = snap.allVisibleTextLower()
        if (textsLower.isEmpty()) return ScreenType.UNKNOWN

        if (matchesAny(textsLower, PAYMENT_CUES))         return ScreenType.PAYMENT
        if (matchesAny(textsLower, CONFIRM_CUES))         return ScreenType.CONFIRMATION_REQUIRED
        if (matchesAny(textsLower, CHECKOUT_CUES))        return ScreenType.CHECKOUT
        if (matchesAny(textsLower, CART_CUES))            return ScreenType.CART
        if (matchesAny(textsLower, PRODUCT_CUES))         return ScreenType.PRODUCT
        if (matchesAny(textsLower, SEARCH_RESULTS_CUES))  return ScreenType.SEARCH_RESULTS
        if (matchesAny(textsLower, HOME_CUES))            return ScreenType.APP_HOME

        return ScreenType.UNKNOWN
    }

    private fun matchesAny(visibleLower: Set<String>, cues: Array<String>): Boolean {
        for (cue in cues) {
            if (visibleLower.contains(cue)) return true
            for (text in visibleLower) {
                if (text.contains(cue)) return true
            }
        }
        return false
    }

    /**
     * Collects lowercased text + contentDesc from all elements in the snapshot.
     * Uses the real field names on UiElement: `text` and `contentDesc`.
     */
    private fun UiSnapshot.allVisibleTextLower(): Set<String> {
        val result = HashSet<String>()
        for (el in this.elements) {
            val t = el.text
            if (!t.isNullOrBlank()) result.add(t.lowercase().trim())
            val d = el.contentDesc
            if (!d.isNullOrBlank()) result.add(d.lowercase().trim())
        }
        return result
    }

    companion object {
        private val PAYMENT_CUES = arrayOf(
            "payment", "pay now", "pay with", "place order", "enter upi",
            "card number", "credit card", "debit card", "cvv", "net banking"
        )
        private val CONFIRM_CUES = arrayOf(
            "confirm order", "confirm payment", "authenticate",
            "verify otp", "enter otp", "biometric"
        )
        private val CHECKOUT_CUES = arrayOf(
            "checkout", "proceed to pay", "proceed to checkout",
            "continue to payment", "review order"
        )
        private val CART_CUES = arrayOf(
            "cart", "my cart", "your cart", "bag", "shopping bag",
            "cart total", "subtotal"
        )
        private val PRODUCT_CUES = arrayOf(
            "add to cart", "buy now", "add item", "view details"
        )
        private val SEARCH_RESULTS_CUES = arrayOf(
            "results for", "results in", "showing results",
            "no results found"
        )
        private val HOME_CUES = arrayOf(
            "home", "for you", "trending", "explore", "recommended"
        )
    }
}

data class AgentWorldState(
    val currentApp: String?,
    val screen: ScreenType,
    val lastAction: String?,
    val lastSucceeded: Boolean
) {
    companion object {
        val EMPTY = AgentWorldState(null, ScreenType.UNKNOWN, null, true)
    }
}

enum class ScreenType {
    UNKNOWN,
    APP_HOME,
    SEARCH_RESULTS,
    PRODUCT,
    CART,
    CHECKOUT,
    CONFIRMATION_REQUIRED,
    PAYMENT
}