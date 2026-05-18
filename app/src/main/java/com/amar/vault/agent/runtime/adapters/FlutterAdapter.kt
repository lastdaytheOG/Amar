package com.amar.vault.agent.runtime.adapters

import com.amar.vault.agent.runtime.injection.InjectionStrategy
import com.amar.vault.agent.runtime.injection.StrategyCascade
import com.amar.vault.agent.runtime.state.SemanticIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Framework adapter for Flutter (io.flutter.* views).
 *
 * Flutter is the hardest case for accessibility-based injection:
 *   - The entire UI is one giant Canvas drawn by Skia.
 *   - Accessibility nodes are SEMANTIC SHADOWS published by Flutter's
 *     SemanticsService, not real views. ACTION_SET_TEXT on these shadows
 *     does nothing — the Flutter framework receives the call but its
 *     EditableText controller ignores it because there's no InputConnection
 *     bound on the shadow node.
 *   - ACTION_PASTE works because Flutter handles paste through the system
 *     clipboard pipeline, which DOES route to the EditableText regardless
 *     of accessibility plumbing.
 *
 * Strategy: clipboard-paste ONLY. SET_TEXT-family strategies are removed
 * from the cascade because they waste verification time on certain failure.
 *
 * Detection: className starts with "io.flutter" or "FlutterView".
 *
 * Known limitation: Flutter's first paste after IME slide-up sometimes
 * misfires because Flutter's framework rebuilds the InputConnection
 * asynchronously. The 400ms per-strategy delay buys time for that
 * rebuild to complete.
 */
@Singleton
class FlutterAdapter @Inject constructor(
    private val cascade: StrategyCascade
) : FrameworkAdapter {

    override fun matches(packageId: String?, className: String?, resourceId: String?): Boolean {
        if (className == null) return false
        return className.startsWith("io.flutter", true) ||
                className.contains("FlutterView", true) ||
                className.contains("FlutterSurfaceView", true) ||
                className.contains("FlutterTextureView", true)
    }

    override fun overrideStrategies(identity: SemanticIdentity): List<InjectionStrategy>? {
        // Flutter: clipboard-only cascade. SET_TEXT is a guaranteed no-op
        // on Flutter EditableText shadows.
        val all = cascade.strategies()
        return listOfNotNull(
            all.firstOrNull { it.name == "CLIPBOARD_PASTE" },
            all.firstOrNull { it.name == "CLICK+PASTE" }
        )
    }

    override fun perStrategyDelayMs(strategy: InjectionStrategy, attemptIndex: Int): Long {
        // Flutter rebuilds the InputConnection asynchronously after IME
        // visibility changes. Wait 400ms on first attempt for the binding
        // to settle.
        return if (attemptIndex == 0) 400L else 100L
    }

    override fun shouldClearBeforeInject(identity: SemanticIdentity, attemptIndex: Int): Boolean {
        // Flutter doesn't auto-replace on paste — concatenates instead.
        // Clear before EVERY attempt to prevent the hellohellohello bug
        // (same root cause as our pre-fix WhatsApp issue).
        return attemptIndex > 0
    }
}