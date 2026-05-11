package com.amar.vault.agent.dsl

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Custom serializer that translates between the WIRE format (nested params, LLM-friendly):
 *
 *   {
 *     "action": "send_message",
 *     "params": { "app": "whatsapp", "to": "Mom", "content": "otw" },
 *     "constraints": { ... },
 *     "verify": { ... }
 *   }
 *
 * ...and the INTERNAL polymorphic format kotlinx.serialization natively understands
 * (discriminator + flattened params at the same level):
 *
 *   {
 *     "action": "send_message",
 *     "app": "whatsapp",
 *     "to": "Mom",
 *     "content": "otw"
 *   }
 *
 * Why do this in a serializer instead of a pre-processing step?
 *   - Symmetric: encoding envelopes back to wire format (for checkpoints, logs,
 *     test fixtures) gets the same shape for free.
 *   - No double-parse: the lift happens inside the decode path, so we keep the
 *     zero-copy advantages of kotlinx's tree decoder.
 *   - Error locality: malformed params surface with the correct JSON path in
 *     validator diagnostics.
 *
 * Caveats (documented, not bugs):
 *   - Only works with JSON format (requires JsonDecoder/JsonEncoder). Don't reuse
 *     the same serializer for ProtoBuf. We don't need to.
 *   - If the Brain emits BOTH nested `params` AND flattened top-level keys,
 *     nested wins. We log a warning-worthy event but don't fail — forgiving the
 *     LLM on redundant structure is cheaper than failing correct-but-noisy output.
 */
object ActionEnvelopeSerializer : KSerializer<ActionEnvelope> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ActionEnvelope") {
            element<String>("action")
            element<JsonObject>("params", isOptional = true)
            element<JsonObject>("constraints", isOptional = true)
            element<JsonObject>("verify", isOptional = true)
        }

    override fun deserialize(decoder: Decoder): ActionEnvelope {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException(
                "ActionEnvelopeSerializer only supports JSON decoders"
            )

        val root = jsonDecoder.decodeJsonElement().jsonObject

        val actionName = (root["action"] as? JsonPrimitive)?.content
        val stepsNode = root["steps"]

        if (actionName == null && stepsNode != null) {
            val flattened = buildJsonObject {
                put("action", "sequence")
                put("steps", stepsNode)
            }
            val action = AgentJsonInternal.decodeFromJsonElement(
                AgentAction.serializer(),
                flattened
            )
            return ActionEnvelope(
                action = action,
                constraints = root["constraints"] as? JsonObject,
                verify = root["verify"] as? JsonObject
            )
        }

        if (actionName == null) {
            throw SerializationException("Missing required field 'action' or 'steps'")
        }

        val paramsNode = root["params"]?.let {
            it as? JsonObject ?: throw SerializationException(
                "Field 'params' must be a JSON object (got ${it::class.simpleName})"
            )
        } ?: JsonObject(emptyMap())

        // Build the internal polymorphic shape: action discriminator + flattened params.
        // If a key exists in BOTH `params` and at root level (shouldn't happen but LLMs
        // can be weird), `params` wins — it's the canonical location per our wire spec.
        val flattened = buildJsonObject {
            put("action", actionName)
            paramsNode.forEach { (k, v) -> put(k, v) }
        }

        val action = AgentJsonInternal.decodeFromJsonElement(
            AgentAction.serializer(),
            flattened
        )

        return ActionEnvelope(
            action = action,
            constraints = root["constraints"] as? JsonObject,
            verify = root["verify"] as? JsonObject
        )
    }

    override fun serialize(encoder: Encoder, value: ActionEnvelope) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException(
                "ActionEnvelopeSerializer only supports JSON encoders"
            )

        // Encode the action as its polymorphic form (discriminator + flattened params),
        // then re-split into { action, params } for the wire output.
        val internalForm = AgentJsonInternal.encodeToJsonElement(
            AgentAction.serializer(),
            value.action
        ).jsonObject

        val actionName = (internalForm["action"] as? JsonPrimitive)?.content
            ?: throw SerializationException("Internal: missing 'action' discriminator after encode")

        if (actionName == "sequence") {
            val stepsNode = internalForm["steps"] ?: throw SerializationException("Internal: missing 'steps' array")
            val wire = buildJsonObject {
                put("steps", stepsNode)
                value.constraints?.let { put("constraints", it) }
                value.verify?.let { put("verify", it) }
            }
            jsonEncoder.encodeJsonElement(wire)
            return
        }

        val paramsOut = buildJsonObject {
            internalForm.forEach { (k, v) -> if (k != "action") put(k, v) }
        }

        val wire = buildJsonObject {
            put("action", actionName)
            if (paramsOut.isNotEmpty()) put("params", paramsOut)
            value.constraints?.let { put("constraints", it) }
            value.verify?.let { put("verify", it) }
        }

        jsonEncoder.encodeJsonElement(wire)
    }
}