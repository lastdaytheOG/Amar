package com.amar.vault.agent.runtime.adapters

import android.view.accessibility.AccessibilityNodeInfo
import com.amar.vault.agent.runtime.injection.InjectionStrategy
import com.amar.vault.agent.runtime.state.BoundsQuadrant
import com.amar.vault.agent.runtime.state.SemanticIdentity

/**
 * Per-app or per-framework override layer on top of the generic engine.
 *
 * # When to use an adapter
 * The generic engine (SemanticResolver + InjectionEngine cascade) handles
 * stock Android apps cleanly. You write an adapter when an app:
 *   - Has nonstandard accessibility plumbing (Compose without Modifier.semantics,
 *     Flutter's single-node tree, React Native opaque containers)
 *   - Uses bespoke resource_id naming that doesn't match generic heuristics
 *   - Needs specific timing tweaks (e.g. extra delay before ACTION_PASTE
 *     because the framework rebuilds the InputConnection asynchronously)
 *   - Requires a strategy the generic cascade doesn't have
 *     (e.g. dispatchGesture with custom path for Flutter)
 *
 * # When NOT to use an adapter
 *   - If the generic engine already works, don't add an adapter "for safety."
 *     Adapters are debt — they need maintenance every time the target app
 *     updates its UI structure.
 *   - Don't write adapters for trivial cosmetic differences. The 4 generic
 *     strategies cover most apps.
 *
 * # Adapter responsibilities (all optional)
 * Each method has a default implementation that returns null / no-op,
 * meaning "use the generic behavior." Adapters override only what they
 * need to override.
 *
 *   - [classifyFocusedNode]: substitute a custom SemanticIdentity classifier
 *     for this app. Useful when resource_id patterns differ from generic.
 *
 *   - [overrideStrategies]: replace the cascade for this app. Useful when
 *     the generic strategies all fail (e.g. Flutter where SET_TEXT is a
 *     no-op and only clipboard works) or when one strategy is dangerous
 *     (e.g. CLICK+PASTE on a button-like editable that submits on click).
 *
 *   - [perStrategyDelayMs]: insert an additional wait before strategy
 *     execution. Some frameworks rebuild the IME binding multiple times
 *     during startup; an adapter knows its app well enough to time the
 *     gap precisely.
 *
 *   - [shouldClearBeforeInject]: some apps prepend non-removable marker
 *     characters (WhatsApp's zero-width space, see Confidence normalization)
 *     and the engine should NOT try to clear them. Other apps require an
 *     explicit clear because they refuse SET_TEXT on a non-empty field.
 *
 * # Lifecycle
 * Adapters are singletons. Pick once via [FrameworkAdapterRegistry.adapterFor]
 * based on the foreground package, then call methods as needed. No per-call
 * construction.
 *
 * # Order of layering
 * For a given inject() call:
 *   1. Engine asks the adapter to classify (overrides SemanticResolver if non-null)
 *   2. Engine asks the adapter for strategies (overrides StrategyCascade if non-null)
 *   3. For each strategy: engine inserts perStrategyDelayMs before invoking
 *   4. After mechanical success: engine consults shouldClearBeforeInject for the next attempt
 *
 * The adapter never sees the verification step — that's always the generic
 * ConfidenceEngine (which already handles normalization for WhatsApp-style
 * marker chars). If you need adapter-specific verification, that's a
 * future Step 10 extension.
 */
interface FrameworkAdapter {

    /**
     * Package(s) this adapter applies to. Registry matches by exact package
     * name. Use [matches] for broader pattern matching (framework adapters
     * like Compose/Flutter check className patterns instead).
     */
    val packageIds: Set<String> get() = emptySet()

    /**
     * Optional pattern-based match. The Compose and Flutter adapters use
     * this — they don't know specific packages, they detect their framework
     * by class names in the focused node.
     *
     * Default: false. Overrides return true if the adapter recognizes the
     * framework from [className] or [resourceId].
     */
    fun matches(packageId: String?, className: String?, resourceId: String?): Boolean = false

    /**
     * Classify the focused node into a SemanticIdentity. Return null to
     * defer to the generic SemanticResolver.
     *
     * Implementations look at app-specific resource_id / content-desc /
     * className patterns the generic resolver doesn't know about.
     */
    fun classifyFocusedNode(
        packageId: String,
        resourceId: String?,
        contentDesc: String?,
        hint: String?,
        className: String?,
        isEditable: Boolean,
        boundsQuadrant: BoundsQuadrant?,
        generationId: Long
    ): SemanticIdentity? = null

    /**
     * Override the strategy cascade for this app. Return null to use the
     * generic 4-strategy cascade.
     *
     * Note: the engine will still wrap each strategy attempt with identity
     * re-check + ConfidenceEngine verification. Adapters return strategies
     * only — they don't bypass verification.
     */
    fun overrideStrategies(identity: SemanticIdentity): List<InjectionStrategy>? = null

    /**
     * Extra delay before invoking a given strategy. Returns 0L for no
     * additional delay (the engine's default behavior).
     */
    fun perStrategyDelayMs(strategy: InjectionStrategy, attemptIndex: Int): Long = 0L

    /**
     * Whether to clear the field before each injection attempt.
     * Default: false (the generic engine never clears).
     *
     * Set true when:
     *   - The field doesn't accept SET_TEXT on non-empty content
     *   - Re-injecting on retry would concatenate instead of replace
     */
    fun shouldClearBeforeInject(identity: SemanticIdentity, attemptIndex: Int): Boolean = false

    /**
     * Adapter name for diagnostics. Default returns the simple class name.
     */
    val adapterName: String get() = this::class.simpleName ?: "UnknownAdapter"
}