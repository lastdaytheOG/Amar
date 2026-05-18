package com.amar.vault.agent.runtime.adapters

import com.amar.vault.agent.runtime.injection.InjectionStrategy
import com.amar.vault.agent.runtime.injection.StrategyCascade
import com.amar.vault.agent.runtime.state.SemanticIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Framework adapter for Jetpack Compose TextField / OutlinedTextField.
 *
 * Compose accessibility plumbing has a known gap: TextField produces an
 * AccessibilityNodeInfo with className "androidx.compose.ui.platform.AndroidComposeView"
 * (the host view) plus a virtual child for the text field. ACTION_SET_TEXT
 * on the parent often returns true mechanically but doesn't actually
 * commit through to the Compose state — the InputConnection routes via
 * a different path.
 *
 * Mitigation: prefer ACTION_PASTE (via clipboard) over ACTION_SET_TEXT,
 * because PASTE routes through the standard text pipeline that Compose
 * binds to. Also add a short pre-injection delay to let recomposition
 * settle — Compose can rebuild the focused-editable tree mid-cascade.
 *
 * Detection: this adapter matches when className starts with
 * "androidx.compose" or contains "ComposeView". It's a pattern match,
 * not a package match — applies to any app using Compose for input.
 *
 * Caveat: WhatsApp's main UI is partly Compose too, but the WhatsAppAdapter
 * (exact package match) wins in registry resolution before this one. So
 * WhatsApp still gets generic strategies via WhatsAppAdapter.classifyFocusedNode
 * returning null → null override → generic cascade.
 */
@Singleton
class ComposeAdapter @Inject constructor(
    private val cascade: StrategyCascade
) : FrameworkAdapter {

    override fun matches(packageId: String?, className: String?, resourceId: String?): Boolean {
        if (className == null) return false
        return className.contains("androidx.compose", true) ||
                className.contains("ComposeView", true) ||
                className == "androidx.compose.ui.platform.AndroidComposeView"
    }

    override fun overrideStrategies(identity: SemanticIdentity): List<InjectionStrategy>? {
        // Reorder: clipboard-paste FIRST, then SET_TEXT, then click-paste.
        // Compose responds reliably to ACTION_PASTE; ACTION_SET_TEXT is
        // unreliable. We keep SET_TEXT as a secondary attempt because some
        // simpler Compose composables (BasicTextField with default semantics)
        // do honor it.
        val all = cascade.strategies()
        // Original order: [SetText, ClipboardPaste, FocusThenSetText, ClickThenPaste]
        // Reorder to:     [ClipboardPaste, ClickThenPaste, SetText, FocusThenSetText]
        return listOfNotNull(
            all.firstOrNull { it.name == "CLIPBOARD_PASTE" },
            all.firstOrNull { it.name == "CLICK+PASTE" },
            all.firstOrNull { it.name == "ACTION_SET_TEXT" },
            all.firstOrNull { it.name == "FOCUS+SET_TEXT" }
        )
    }

    override fun perStrategyDelayMs(strategy: InjectionStrategy, attemptIndex: Int): Long {
        // Give recomposition 200ms to settle on the first attempt.
        // Subsequent attempts don't need extra time — they're re-acquiring
        // a fresh node anyway.
        return if (attemptIndex == 0) 200L else 0L
    }
}