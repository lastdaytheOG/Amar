package com.amar.vault.agent.dsl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed hierarchy of all agent actions.
 *
 * Stage 2d addition: GestureTap is a coordinate-based tap that bypasses
 * ACTION_CLICK semantic action. Used as a fallback when target views handle
 * input via OnTouchListener or gesture detectors rather than the a11y click
 * pipeline.
 */
@Serializable
sealed class AgentAction {

    abstract val kind: ActionKind

    // ---------- Navigation ----------

    @Serializable
    @SerialName("open_app")
    data class OpenApp(
        val app: String,
        val packageId: String? = null
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.OPEN_APP
    }

    @Serializable
    @SerialName("search_app")
    data class SearchApp(
        val app: String,
        val query: String
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.SEARCH_APP
    }

    @Serializable
    @SerialName("home")
    data object Home : AgentAction() {
        override val kind: ActionKind = ActionKind.HOME
    }

    // ---------- Communication ----------

    @Serializable
    @SerialName("send_sms")
    data class SendSms(
        val to: String,
        val body: String
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.SEND_SMS
    }

    @Serializable
    @SerialName("make_call")
    data class MakeCall(
        val to: String
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.MAKE_CALL
    }

    @Serializable
    @SerialName("send_message")
    data class SendMessage(
        val app: String,
        val to: String,
        val content: String
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.SEND_MESSAGE
    }

    // ---------- UI interaction ----------

    @Serializable
    @SerialName("click")
    data class Click(
        val target: String,
        val strategy: TargetStrategy = TargetStrategy.AUTO
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.CLICK
    }

    /**
     * Coordinate gesture tap fallback. Resolves [target] to a node, then
     * dispatches a real MotionEvent at the bounds center. Bypasses
     * ACTION_CLICK for apps where the visible target isn't a11y-clickable
     * (custom OnTouchListener / gesture detector / Compose interaction
     * source / Material container delegation).
     */
    @Serializable
    @SerialName("gesture_tap")
    data class GestureTap(
        val target: String,
        val strategy: TargetStrategy = TargetStrategy.AUTO
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.GESTURE_TAP
    }

    @Serializable
    @SerialName("type_text")
    data class TypeText(
        val target: String,
        val text: String,
        val strategy: TargetStrategy = TargetStrategy.AUTO,
        val submit: Boolean = false
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.TYPE_TEXT
    }

    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val direction: ScrollDirection,
        val target: String? = null
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.SCROLL
    }

    // ---------- Meta / pacing ----------

    @Serializable
    @SerialName("wait")
    data class Wait(
        val ms: Int
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.WAIT
    }

    @Serializable
    @SerialName("read_screen")
    data object ReadScreen : AgentAction() {
        override val kind: ActionKind = ActionKind.READ_SCREEN
    }

    @Serializable
    @SerialName("sequence")
    data class Sequence(
        val steps: List<ActionEnvelope>
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.SEQUENCE
    }
}

/**
 * Stable action identifiers.
 */
enum class ActionKind {
    OPEN_APP,
    SEARCH_APP,
    HOME,
    SEND_SMS,
    MAKE_CALL,
    SEND_MESSAGE,
    CLICK,
    GESTURE_TAP,
    TYPE_TEXT,
    SCROLL,
    WAIT,
    READ_SCREEN,
    SEQUENCE
}

@Serializable
enum class TargetStrategy {
    @SerialName("auto")                      AUTO,
    @SerialName("text")                      TEXT,
    @SerialName("content_desc")              CONTENT_DESC,
    @SerialName("resource_id")               RESOURCE_ID,
    @SerialName("first_clickable_in_grid")   FIRST_CLICKABLE_IN_GRID,
    @SerialName("focused_editable")          FOCUSED_EDITABLE,
    @SerialName("structural_dna")            STRUCTURAL_DNA
}

@Serializable
enum class ScrollDirection {
    @SerialName("up")    UP,
    @SerialName("down")  DOWN,
    @SerialName("left")  LEFT,
    @SerialName("right") RIGHT
}