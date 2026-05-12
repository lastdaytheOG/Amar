package com.amar.vault.agent.dsl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed hierarchy of all agent actions (v1 = 10 actions).
 *
 * Design:
 * - Polymorphic serialization keyed on the "action" field (see AgentJson).
 * - Each subclass declares its own typed params — strict validation at deserialization.
 * - Unknown action names fail fast; unknown params inside a known action also fail
 *   (ignoreUnknownKeys is false on purpose for the inner payload).
 *
 * Validation policy (v1 = HYBRID):
 * - STRICT on action name + params (this file, via kotlinx.serialization).
 * - LENIENT on constraints + verify (see ActionEnvelope — those are JsonObject).
 */
@Serializable
sealed class AgentAction {

    /** Stable identifier used in logs, checkpoints, telemetry. */
    abstract val kind: ActionKind

    // ---------- Navigation ----------

    @Serializable
    @SerialName("open_app")
    data class OpenApp(
        val app: String,                       // display name OR package id; executor resolves
        val packageId: String? = null          // optional explicit override
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.OPEN_APP
    }

    @Serializable
    @SerialName("search_app")
    data class SearchApp(
        val app: String,                       // target app: "YouTube", "Spotify", etc.
        val query: String                      // search query text
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.SEARCH_APP
    }

    @Serializable
    @SerialName("home")
    data object Home : AgentAction() {
        override val kind: ActionKind = ActionKind.HOME
    }

    // NOTE: "back" is reserved for later — deferred out of v1 per user's 10-action choice.

    // ---------- Communication ----------

    @Serializable
    @SerialName("send_sms")
    data class SendSms(
        val to: String,                        // phone number, E.164 preferred
        val body: String
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.SEND_SMS
    }

    @Serializable
    @SerialName("make_call")
    data class MakeCall(
        val to: String                         // phone number
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.MAKE_CALL
    }

    @Serializable
    @SerialName("send_message")
    data class SendMessage(
        val app: String,                       // "whatsapp", "telegram", etc.
        val to: String,                        // contact name or number
        val content: String
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.SEND_MESSAGE
    }

    // ---------- UI interaction (Accessibility fallback) ----------

    @Serializable
    @SerialName("click")
    data class Click(
        val target: String,                    // text, contentDesc, or resourceId
        val strategy: TargetStrategy = TargetStrategy.AUTO
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.CLICK
    }

    @Serializable
    @SerialName("type_text")
    data class TypeText(
        val target: String,                    // hint/label of input field, or resourceId
        val text: String,
        val strategy: TargetStrategy = TargetStrategy.AUTO,
        val submit: Boolean = false            // true = press IME action after typing
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.TYPE_TEXT
    }

    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val direction: ScrollDirection,
        val target: String? = null             // optional: scroll a specific container
    ) : AgentAction() {
        override val kind: ActionKind = ActionKind.SCROLL
    }

    // ---------- Meta / pacing ----------

    @Serializable
    @SerialName("wait")
    data class Wait(
        val ms: Int                            // bounded by validator: 50..15000
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
 * Stable action identifiers. Never renumber — these persist in Room checkpoints
 * and will appear in Shadow Brain's failure analysis logs.
 */
enum class ActionKind {
    OPEN_APP,
    SEARCH_APP,
    HOME,
    SEND_SMS,
    MAKE_CALL,
    SEND_MESSAGE,
    CLICK,
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
    @SerialName("focused_editable")          FOCUSED_EDITABLE
}

@Serializable
enum class ScrollDirection {
    @SerialName("up")    UP,
    @SerialName("down")  DOWN,
    @SerialName("left")  LEFT,
    @SerialName("right") RIGHT
}