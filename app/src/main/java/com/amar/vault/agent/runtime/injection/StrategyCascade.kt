package com.amar.vault.agent.runtime.injection

import android.content.Context
import com.amar.vault.agent.runtime.injection.strategies.ClickThenPasteStrategy
import com.amar.vault.agent.runtime.injection.strategies.ClipboardPasteStrategy
import com.amar.vault.agent.runtime.injection.strategies.FocusThenSetTextStrategy
import com.amar.vault.agent.runtime.injection.strategies.SetTextStrategy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the ordered list of injection strategies to try.
 *
 * Ordering rationale (cheapest → most invasive):
 *   1. SET_TEXT          — pure a11y, no clipboard touch, no UI side-effects
 *   2. CLIPBOARD_PASTE   — touches clipboard, but cheap and routes through
 *                          the system text pipeline (defeats Compose/Flutter)
 *   3. FOCUS+SET_TEXT    — re-asserts input focus then re-tries SET_TEXT
 *   4. CLICK+PASTE       — last resort, triggers full focus pipeline then paste
 *
 * The architecture doc proposes 8 strategies. We ship the 4 most impactful
 * here; the rest (incremental humanized typing, IME commitText injection,
 * ADB shell input) are deferred to a future iteration if these 4 prove
 * insufficient in practice.
 *
 * Adaptive ordering (Step 14 — OEM Learning):
 *   This cascade is currently fixed. Step 14 will promote strategies that
 *   succeed on a given device, demoting ones that fail. Today the order is
 *   static and per-device tuning happens manually.
 */
@Singleton
class StrategyCascade @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val clipboardPaste = ClipboardPasteStrategy(context)
    private val clickThenPaste = ClickThenPasteStrategy(context)

    /**
     * Ordered list of strategies to attempt. Returned as a fresh list each
     * call so the engine can iterate without worrying about mutation.
     *
     * The ClipboardPasteStrategy instance is reused because the engine needs
     * to call its saveClipboard/restoreClipboard helpers across attempts.
     */
    fun strategies(): List<InjectionStrategy> = listOf(
        SetTextStrategy(),
        clipboardPaste,
        FocusThenSetTextStrategy(),
        clickThenPaste
    )

    /**
     * Direct accessor for the clipboard strategy, so the engine can call
     * saveClipboard()/restoreClipboard() lifecycle methods.
     */
    fun clipboardStrategy(): ClipboardPasteStrategy = clipboardPaste
}