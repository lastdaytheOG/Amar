package com.amar.vault.agent.dsl

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Hybrid-policy validator for incoming LLM output.
 *
 * Policy recap:
 *   - STRICT on action name & params (class-discriminated, unknown params rejected).
 *   - LENIENT on constraints / verify (unknown keys warn, not fail).
 *
 * Wire format is nested-params; see [ActionEnvelopeSerializer] for the translation
 * to/from the internal polymorphic representation.
 *
 * Usage:
 *   val result = ActionValidator.validate(llmJsonString)
 *   when (result) {
 *     is ValidationResult.Valid    -> controlLayer.execute(result.envelope)
 *     is ValidationResult.Degraded -> { log(result.warnings); controlLayer.execute(result.envelope) }
 *     is ValidationResult.Invalid  -> brain.correctAndRetry(result.reasons)
 *   }
 */
object ActionValidator {

    fun validate(rawJson: String): ValidationResult {
        // --- Step 1: parse to typed envelope (strict on action+params) ---
        val envelope = try {
            AgentJson.decodeFromString(ActionEnvelope.serializer(), rawJson)
        } catch (e: SerializationException) {
            return ValidationResult.Invalid(
                reasons = listOf(parseExceptionToReason(e)),
                rawJson = rawJson
            )
        } catch (e: IllegalArgumentException) {
            return ValidationResult.Invalid(
                reasons = listOf(parseExceptionToReason(e)),
                rawJson = rawJson
            )
        }

        // --- Step 2: semantic param validation (beyond what the type system catches) ---
        val reasons = mutableListOf<ValidationReason>()
        validateActionSemantics(envelope.action, reasons)
        validateConstraintsRange(envelope.effectiveConstraints, reasons)

        if (reasons.isNotEmpty()) {
            return ValidationResult.Invalid(reasons, rawJson)
        }

        // --- Step 3: collect lenient warnings (no blocking) ---
        val warnings = mutableListOf<ValidationWarning>()
        envelope.effectiveConstraints.unknownKeys.forEach {
            warnings += ValidationWarning.UnknownConstraintKey(it)
        }
        when (val v = envelope.effectiveVerify) {
            is VerifySpec.Unknown -> warnings += ValidationWarning.UnknownVerifyType(v.rawType)
            else -> { /* no warning */ }
        }

        return if (warnings.isEmpty())
            ValidationResult.Valid(envelope)
        else
            ValidationResult.Degraded(envelope, warnings)
    }

    // -------------------------------------------------------------------------
    // Semantic checks — things the type system can't enforce alone
    // -------------------------------------------------------------------------

    private fun validateActionSemantics(
        action: AgentAction,
        reasons: MutableList<ValidationReason>
    ) {
        when (action) {
            is AgentAction.OpenApp -> {
                if (action.app.isBlank()) {
                    reasons += ValidationReason.InvalidParamValue(
                        "open_app", "app", "must be non-empty"
                    )
                }
                action.packageId?.let {
                    if (!PACKAGE_ID_REGEX.matches(it)) {
                        reasons += ValidationReason.InvalidParamValue(
                            "open_app", "packageId",
                            "not a valid package id: '$it'"
                        )
                    }
                }
            }

            is AgentAction.SearchApp -> {
                if (action.app.isBlank()) {
                    reasons += ValidationReason.InvalidParamValue(
                        "search_app", "app", "must be non-empty"
                    )
                }
                if (action.query.isBlank()) {
                    reasons += ValidationReason.InvalidParamValue(
                        "search_app", "query", "must be non-empty"
                    )
                }
            }

            is AgentAction.SendSms -> {
                if (!looksLikePhoneNumber(action.to)) {
                    reasons += ValidationReason.InvalidParamValue(
                        "send_sms", "to", "not a plausible phone number: '${action.to}'"
                    )
                }
                if (action.body.isBlank()) {
                    reasons += ValidationReason.InvalidParamValue(
                        "send_sms", "body", "must be non-empty"
                    )
                }
                if (action.body.length > MAX_SMS_BODY_LEN) {
                    reasons += ValidationReason.InvalidParamValue(
                        "send_sms", "body",
                        "too long (${action.body.length} > $MAX_SMS_BODY_LEN)"
                    )
                }
            }

            is AgentAction.MakeCall -> {
                if (!looksLikePhoneNumber(action.to)) {
                    reasons += ValidationReason.InvalidParamValue(
                        "make_call", "to", "not a plausible phone number: '${action.to}'"
                    )
                }
            }

            is AgentAction.SendMessage -> {
                if (action.app.isBlank())
                    reasons += ValidationReason.InvalidParamValue(
                        "send_message", "app", "must be non-empty"
                    )
                if (action.to.isBlank())
                    reasons += ValidationReason.InvalidParamValue(
                        "send_message", "to", "must be non-empty"
                    )
                if (action.content.isBlank())
                    reasons += ValidationReason.InvalidParamValue(
                        "send_message", "content", "must be non-empty"
                    )
            }

            is AgentAction.Click -> {
                if (action.target.isBlank())
                    reasons += ValidationReason.InvalidParamValue(
                        "click", "target", "must be non-empty"
                    )
            }

            is AgentAction.TypeText -> {
                if (action.target.isBlank())
                    reasons += ValidationReason.InvalidParamValue(
                        "type_text", "target", "must be non-empty"
                    )
                // empty text is allowed (could be "clear field")
            }

            is AgentAction.Scroll -> { /* direction is enum-validated at parse time */ }

            is AgentAction.Wait -> {
                if (action.ms !in MIN_WAIT_MS..MAX_WAIT_MS) {
                    reasons += ValidationReason.InvalidParamValue(
                        "wait", "ms",
                        "must be in $MIN_WAIT_MS..$MAX_WAIT_MS (got ${action.ms})"
                    )
                }
            }

            is AgentAction.Sequence -> {
                if (action.steps.isEmpty()) {
                    reasons += ValidationReason.InvalidParamValue("sequence", "steps", "must not be empty")
                } else {
                    action.steps.forEach { step ->
                        validateActionSemantics(step.action, reasons)
                        validateConstraintsRange(step.effectiveConstraints, reasons)
                    }
                }
            }

            AgentAction.Home, AgentAction.ReadScreen -> { /* no params */ }
        }
    }

    private fun validateConstraintsRange(
        c: Constraints,
        reasons: MutableList<ValidationReason>
    ) {
        if (c.timeoutMs <= 0 || c.timeoutMs > Constraints.MAX_TIMEOUT_MS) {
            reasons += ValidationReason.ConstraintOutOfRange(
                "timeout_ms",
                "must be in 1..${Constraints.MAX_TIMEOUT_MS} (got ${c.timeoutMs})"
            )
        }
        if (c.retries < 0 || c.retries > Constraints.MAX_RETRIES) {
            reasons += ValidationReason.ConstraintOutOfRange(
                "retries",
                "must be in 0..${Constraints.MAX_RETRIES} (got ${c.retries})"
            )
        }
        if (c.retryBackoffMs < 0 || c.retryBackoffMs > 10_000) {
            reasons += ValidationReason.ConstraintOutOfRange(
                "retry_backoff_ms",
                "must be in 0..10000 (got ${c.retryBackoffMs})"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun parseExceptionToReason(e: Throwable): ValidationReason {
        val msg = e.message.orEmpty()

        val unknownActionMatch = UNKNOWN_ACTION_REGEX.find(msg)
        if (unknownActionMatch != null) {
            return ValidationReason.UnknownAction(unknownActionMatch.groupValues[1])
        }
        val unknownKeyMatch = UNKNOWN_KEY_REGEX.find(msg)
        if (unknownKeyMatch != null) {
            return ValidationReason.UnknownParam("<unknown>", unknownKeyMatch.groupValues[1])
        }
        val missingFieldMatch = MISSING_FIELD_REGEX.find(msg)
        if (missingFieldMatch != null) {
            return ValidationReason.MissingRequiredParam("<unknown>", missingFieldMatch.groupValues[1])
        }
        return ValidationReason.MalformedJson(msg.take(200))
    }

    private fun looksLikePhoneNumber(s: String): Boolean {
        // Deliberately permissive — real E.164 validation happens at executor time
        // with libphonenumber. Here we just screen out obvious garbage.
        val trimmed = s.replace(Regex("[\\s\\-().]"), "")
        if (trimmed.isEmpty()) return false
        val withoutLeadingPlus = trimmed.removePrefix("+")
        if (withoutLeadingPlus.length !in 6..15) return false
        return withoutLeadingPlus.all { it.isDigit() }
    }

    // ---- Constants ----
    private const val MAX_SMS_BODY_LEN = 1600
    private const val MIN_WAIT_MS = 50
    private const val MAX_WAIT_MS = 15_000

    private val PACKAGE_ID_REGEX = Regex("^[a-zA-Z][\\w]*(\\.[a-zA-Z][\\w]*)+$")

    // Heuristic regexes against kotlinx.serialization's error messages.
    // If library wording shifts between versions, ActionValidatorTest will catch it.
    private val UNKNOWN_ACTION_REGEX =
        Regex("serializer .*? was not found for (?:class discriminator )?['\"]([^'\"]+)['\"]")
    private val UNKNOWN_KEY_REGEX =
        Regex("Encountered an unknown key ['\"]([^'\"]+)['\"]")
    private val MISSING_FIELD_REGEX =
        Regex("Field ['\"]([^'\"]+)['\"] is required")
}

/**
 * PUBLIC Json instance — use this for encoding/decoding ActionEnvelope on the wire.
 *
 * This instance goes through [ActionEnvelopeSerializer], which produces/consumes
 * the nested-params wire format ({ "action": ..., "params": { ... } }).
 *
 * Note: This instance does NOT register polymorphic subclasses directly; the
 * lift from nested→flat happens inside the custom serializer, which uses
 * [AgentJsonInternal] for the polymorphic dispatch.
 */
val AgentJson: Json = Json {
    ignoreUnknownKeys = false   // strict on envelope keys (action/params/constraints/verify)
    isLenient = false
    encodeDefaults = true
    prettyPrint = false
}

/**
 * INTERNAL Json instance — used ONLY inside [ActionEnvelopeSerializer] to perform
 * the polymorphic dispatch on the lifted { action, ...params } form.
 *
 * Why split:
 *   - Public instance works on the nested wire format.
 *   - This instance works on the flat polymorphic form.
 *   - Keeping them separate means neither can be accidentally used to decode the
 *     wrong shape and silently produce garbage.
 *
 * Critical settings:
 *   - classDiscriminator = "action"      → matches what the lifted form produces.
 *   - ignoreUnknownKeys = false          → STRICT on action params (hallucination guard).
 *   - Polymorphic module registers all 10 v1 action subclasses.
 */
internal val AgentJsonInternal: Json = Json {
    classDiscriminator = "action"
    ignoreUnknownKeys = false
    isLenient = false
    encodeDefaults = true
    prettyPrint = false

    serializersModule = SerializersModule {
        polymorphic(AgentAction::class) {
            subclass(AgentAction.OpenApp::class)
            subclass(AgentAction.SearchApp::class)
            subclass(AgentAction.Home::class)
            subclass(AgentAction.SendSms::class)
            subclass(AgentAction.MakeCall::class)
            subclass(AgentAction.SendMessage::class)
            subclass(AgentAction.Click::class)
            subclass(AgentAction.TypeText::class)
            subclass(AgentAction.Scroll::class)
            subclass(AgentAction.Wait::class)
            subclass(AgentAction.ReadScreen::class)
            subclass(AgentAction.Sequence::class)
        }
    }
}