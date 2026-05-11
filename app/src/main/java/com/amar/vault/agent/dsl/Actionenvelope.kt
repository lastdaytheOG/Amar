package com.amar.vault.agent.dsl

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Top-level DSL envelope — what the Brain outputs and the Control Layer consumes.
 *
 * Wire format (LLM-facing, canonical):
 * {
 *   "action": "send_message",
 *   "params": { "app": "whatsapp", "to": "Mom", "content": "otw" },
 *   "constraints": { "timeout_ms": 3000, "retries": 2 },
 *   "verify": { "type": "ui_contains", "query": "Message sent" }
 * }
 *
 * Internally, `action` is a typed AgentAction subclass. The translation between
 * nested-params-on-the-wire and polymorphic-on-the-inside lives in
 * [ActionEnvelopeSerializer].
 *
 * Validation policy (HYBRID):
 * - STRICT on action name + params — enforced by kotlinx.serialization's
 *   polymorphic dispatch with ignoreUnknownKeys=false on the internal Json
 *   instance. Unknown action names and unknown params both fail at parse.
 * - LENIENT on constraints + verify — kept as JsonObject here so unknown keys
 *   are preserved for telemetry but don't break parsing.
 */
@Serializable(with = ActionEnvelopeSerializer::class)
data class ActionEnvelope(
    val action: AgentAction,
    val constraints: JsonObject? = null,
    val verify: JsonObject? = null
) {
    val effectiveConstraints: Constraints
        get() = Constraints.fromJson(constraints)

    val effectiveVerify: VerifySpec
        get() = VerifySpec.fromJson(verify)
}

/**
 * Parsed constraints. Fields are optional; executors apply defaults.
 * Bounds are enforced by ActionValidator, not here.
 */
data class Constraints(
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val retries: Int = DEFAULT_RETRIES,
    val retryBackoffMs: Long = DEFAULT_BACKOFF_MS,
    val requiresConfirmation: Boolean = false,
    /** Keys we didn't recognize — kept for telemetry, ignored by executor. */
    val unknownKeys: List<String> = emptyList()
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
        const val DEFAULT_RETRIES = 2
        const val DEFAULT_BACKOFF_MS = 300L

        // Hard caps — validator enforces these.
        const val MAX_TIMEOUT_MS = 30_000L
        const val MAX_RETRIES = 5

        private val KNOWN_KEYS = setOf(
            "timeout_ms", "retries", "retry_backoff_ms", "requires_confirmation"
        )

        fun fromJson(json: JsonObject?): Constraints {
            if (json == null) return Constraints()
            val unknown = json.keys.filter { it !in KNOWN_KEYS }
            return Constraints(
                timeoutMs = json["timeout_ms"]?.asLongOrNull() ?: DEFAULT_TIMEOUT_MS,
                retries = json["retries"]?.asIntOrNull() ?: DEFAULT_RETRIES,
                retryBackoffMs = json["retry_backoff_ms"]?.asLongOrNull() ?: DEFAULT_BACKOFF_MS,
                requiresConfirmation = json["requires_confirmation"]?.asBoolOrNull() ?: false,
                unknownKeys = unknown
            )
        }
    }
}

/**
 * Verification spec. Unknown types degrade to [Unknown] (lenient) so forward-compat
 * Brain outputs don't break v1 clients.
 */
sealed class VerifySpec {

    data class UiContains(val query: String, val isRegex: Boolean = false) : VerifySpec()

    data class AppOpened(val packageId: String) : VerifySpec()

    data object TextSent : VerifySpec()

    data object None : VerifySpec()

    /** Forward-compat escape hatch — preserves raw JSON for logging. */
    data class Unknown(val rawType: String, val raw: JsonObject) : VerifySpec()

    companion object {
        fun fromJson(json: JsonObject?): VerifySpec {
            if (json == null) return None
            val type = json["type"]?.asStringOrNull() ?: return None
            return when (type) {
                "ui_contains" -> UiContains(
                    query = json["query"]?.asStringOrNull() ?: return Unknown(type, json),
                    isRegex = json["is_regex"]?.asBoolOrNull() ?: false
                )
                "app_opened" -> AppOpened(
                    packageId = json["package"]?.asStringOrNull() ?: return Unknown(type, json)
                )
                "text_sent" -> TextSent
                else -> Unknown(type, json)
            }
        }
    }
}

// ---------- Tiny JsonElement helpers — file-private ----------

private fun kotlinx.serialization.json.JsonElement.asLongOrNull(): Long? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

private fun kotlinx.serialization.json.JsonElement.asIntOrNull(): Int? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()

private fun kotlinx.serialization.json.JsonElement.asBoolOrNull(): Boolean? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()

private fun kotlinx.serialization.json.JsonElement.asStringOrNull(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.content