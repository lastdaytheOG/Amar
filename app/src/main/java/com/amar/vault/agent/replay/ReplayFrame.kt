package com.amar.vault.agent.replay

import com.amar.vault.agent.perception.UiSnapshot
import kotlinx.serialization.Serializable

/**
 * Tagged-union frame written to a replay file. Each frame is one event in
 * the workflow timeline (snapshot capture, action submission, environment
 * verification, world state transition, or step boundary marker).
 *
 * All frames carry a ts_ms relative to workflow start.
 */
@Serializable
sealed class ReplayFrame {
    abstract val tsMs: Long

    @Serializable
    data class Snapshot(
        override val tsMs: Long,
        val snapshotId: String,       // UUID — future-proofs delta-based replay
        val packageId: String?,
        val elementCount: Int,
        val elements: List<SerializableUiElement>
    ) : ReplayFrame()

    @Serializable
    data class Action(
        override val tsMs: Long,
        val kind: String,            // "Click", "TypeText", "OpenApp", etc.
        val target: String?,
        val strategy: String?,
        val extra: Map<String, String> = emptyMap()
    ) : ReplayFrame()

    @Serializable
    data class ActionResult(
        override val tsMs: Long,
        val state: String,           // "Succeeded", "Failed", "Pending"
        val durationMs: Long,
        val reason: String? = null
    ) : ReplayFrame()

    @Serializable
    data class EnvironmentVerification(
        override val tsMs: Long,
        val targetEnv: String,
        val targetConfidence: Double,
        val state: String,           // STABLE / TRANSITIONING / WRONG_ENVIRONMENT / etc.
        val winningEnv: String?,
        val allConfidences: Map<String, Double>
    ) : ReplayFrame()

    @Serializable
    data class WorldStateChange(
        override val tsMs: Long,
        val focusedEditableKind: String?,     // "SearchInput" | "ChatInput" | "Unknown" | null
        val focusedEditablePackage: String?
    ) : ReplayFrame()

    @Serializable
    data class StepMarker(
        override val tsMs: Long,
        val step: String,            // "step_1_open", "step_3_winner", "step_5_SUCCESS", etc.
        val detail: String? = null
    ) : ReplayFrame()
}

/**
 * Wire-format UiElement: same fields as runtime UiElement but without
 * Android-only types (Rect, AccessibilityNodeInfo). Safe to serialize.
 */
@Serializable
data class SerializableUiElement(
    val type: String,
    val text: String? = null,
    val contentDesc: String? = null,
    val hint: String? = null,
    val resourceId: String? = null,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val focused: Boolean = false,
    val depth: Int = 0,
    val path: String = ""
) {
    companion object {
        fun from(el: com.amar.vault.agent.perception.UiElement): SerializableUiElement =
            SerializableUiElement(
                type = el.type.name,
                text = el.text,
                contentDesc = el.contentDesc,
                hint = el.hint,
                resourceId = el.resourceId,
                left = el.bounds?.left ?: 0,
                top = el.bounds?.top ?: 0,
                right = el.bounds?.right ?: 0,
                bottom = el.bounds?.bottom ?: 0,
                clickable = el.clickable,
                editable = el.editable,
                focused = el.focused,
                depth = el.depth,
                path = el.path
            )
    }
}