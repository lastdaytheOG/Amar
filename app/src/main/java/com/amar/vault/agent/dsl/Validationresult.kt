package com.amar.vault.agent.dsl

/**
 * Outcome of validating an ActionEnvelope.
 *
 * - Valid:    envelope passed all strict checks, no warnings worth surfacing.
 * - Degraded: envelope is safe to execute, but had forward-compat warnings
 *             (unknown keys in constraints/verify). The Control Layer proceeds
 *             but writes warnings to the task checkpoint for later analysis.
 * - Invalid:  envelope is REJECTED. Control Layer must not execute. Reasons
 *             are structured so the caller (eventually the Brain's retry loop)
 *             can produce a corrective prompt.
 *
 * Rationale:
 *   Distinct Degraded vs Invalid is the whole point of "hybrid" validation.
 *   Collapsing them would either force strict-everywhere (brittle) or
 *   lenient-everywhere (hallucinated actions slip through).
 */
sealed class ValidationResult {

    data class Valid(val envelope: ActionEnvelope) : ValidationResult()

    data class Degraded(
        val envelope: ActionEnvelope,
        val warnings: List<ValidationWarning>
    ) : ValidationResult()

    data class Invalid(
        val reasons: List<ValidationReason>,
        /** Raw JSON preserved so we can show the Brain what it sent. */
        val rawJson: String? = null
    ) : ValidationResult()

    val isExecutable: Boolean
        get() = this is Valid || this is Degraded
}

/**
 * Hard failure reasons. Each has a stable code so the Brain's correction
 * prompt can be template-driven instead of NL-scraping error strings.
 */
sealed class ValidationReason(val code: String, val message: String) {

    data class MalformedJson(val detail: String) :
        ValidationReason("E_JSON", "JSON could not be parsed: $detail")

    data class UnknownAction(val name: String) :
        ValidationReason("E_ACTION_UNKNOWN", "Unknown action '$name'")

    data class MissingRequiredParam(val action: String, val param: String) :
        ValidationReason("E_PARAM_MISSING", "Action '$action' is missing required param '$param'")

    data class InvalidParamValue(val action: String, val param: String, val detail: String) :
        ValidationReason("E_PARAM_INVALID", "Action '$action' param '$param' is invalid: $detail")

    data class UnknownParam(val action: String, val param: String) :
        ValidationReason("E_PARAM_UNKNOWN", "Action '$action' does not accept param '$param'")

    data class ConstraintOutOfRange(val field: String, val detail: String) :
        ValidationReason("E_CONSTRAINT_RANGE", "Constraint '$field' out of range: $detail")
}

/**
 * Soft warnings. Do NOT block execution. Emitted on unknown fields in
 * constraints/verify (lenient surface) and on lossy parses of verify types.
 */
sealed class ValidationWarning(val code: String, val message: String) {

    data class UnknownConstraintKey(val key: String) :
        ValidationWarning("W_CONSTRAINT_UNKNOWN", "Ignored unknown constraint key '$key'")

    data class UnknownVerifyType(val type: String) :
        ValidationWarning("W_VERIFY_UNKNOWN", "Unknown verify type '$type' — verification will be skipped")

    data class VerifyMissingField(val type: String, val field: String) :
        ValidationWarning("W_VERIFY_INCOMPLETE", "Verify type '$type' missing field '$field' — treated as unknown")
}