package com.amar.vault.agent.runtime.state

/**
 * The agent's complete view of the world, derived by reducing accessibility
 * events into a single immutable snapshot.
 *
 * This is the ONLY state executors should consult in the new architecture.
 * Direct queries to PerceptionService / AccessibilityNodeInfo become forbidden
 * once Step 8 (Injection Engine) is in place.
 *
 * Update model:
 *   - WorldState is immutable. Every event produces a new instance via reducer.
 *   - Held in a single StateFlow inside [WorldStateStore].
 *   - Consumers observe via Flow; they do NOT mutate.
 *
 * Field categories:
 *   1. Foreground context — what app/window the user is currently on.
 *   2. Focus & input readiness — is the keyboard ready to accept text?
 *   3. Semantic targets — what known logical roles exist on screen.
 *   4. Generation — invalidation counter for stale-node detection.
 *   5. Telemetry — cancellation, recovery state, last-event diagnostics.
 *
 * Generation field:
 *   Tracks the RootGeneration counter at the time this state was built.
 *   Executors capturing nodes during phase N must abort if state.generationId
 *   advances before they act.
 */
data class WorldState(

    // ---------- Foreground context ----------

    /** Foreground application package, e.g. "com.whatsapp". Null at boot. */
    val foregroundPackage: String? = null,

    /** Foreground activity / window class. Useful for routing context. */
    val foregroundWindowClass: String? = null,

    // ---------- IME & input readiness ----------

    /**
     * True if the soft input method is visible on screen.
     * Detected via AccessibilityEvent windows enumeration in the reducer.
     */
    val imeVisible: Boolean = false,

    /**
     * True if a focused editable node is currently selectable / can accept text.
     * This is the AUTHORITATIVE signal for "safe to inject text".
     *
     * Required conditions (all true):
     *   - imeVisible == true
     *   - focusedEditableIdentity != null
     *   - last TextSelectionChanged event for that node happened recently
     *
     * Step 6 (IME Coordinator) populates this via TextSelectionChanged events.
     * Until then, default false — injection paths can fall back to legacy.
     */
    val inputConnectionReady: Boolean = false,

    /**
     * Identity of the currently focused editable, if any. Null when nothing
     * is focused or the focused node is not editable.
     */
    val focusedEditableIdentity: SemanticIdentity? = null,

    // ---------- Semantic targets ----------

    /**
     * Known semantic identities currently present on screen. Built by Step 7's
     * SemanticResolver. Today: always empty.
     */
    val knownIdentities: Map<String, SemanticIdentity> = emptyMap(),

    // ---------- Generation & invalidation ----------

    /**
     * RootGeneration value at the time this state was built. If a captured
     * AccessibilityNodeInfo predates state.generationId, treat it as stale.
     */
    val generationId: Long = 0L,

    // ---------- Runtime telemetry ----------

    /** Number of times this state has been reduced. Monotonic. */
    val reductionCount: Long = 0L,

    /** Wall-clock time of the last reduction. */
    val lastReducedAtMillis: Long = 0L,

    /** Last event sequence consumed by the reducer. */
    val lastEventSequence: Long = 0L,

    /**
     * Currently-active workflow id (set by orchestrator). Null when idle.
     * Used by Step 5 cancellation to scope what gets aborted.
     */
    val activeWorkflowId: String? = null,

    /**
     * True if recovery is currently in progress. Step 12 (Recovery Engine)
     * sets this when dispatching, clears when complete.
     */
    val inRecovery: Boolean = false,

    // ---------- Circuit Breaker: target-app liveness tracking ----------

    /**
     * Package the executor is currently working with. Null when no workflow
     * is targeted. Updated by [WorldStateStore.startTrackingTarget] when a
     * workflow begins. When non-null, the reducer/perception pipeline checks
     * each window event against this and updates lastTargetSeenAtMillis.
     */
    val circuitBreakerTargetPackage: String? = null,

    /**
     * Wall-clock time of the last AccessibilityEvent that confirmed the
     * target package was on-screen. Zero when not tracking. The circuit
     * breaker predicate compares (now - this) against an absence window.
     */
    val lastTargetSeenAtMillis: Long = 0L,

    /**
     * Last package observed in an unexpected (non-target, non-system-allowed)
     * window event. Helps post-mortem analysis: distinguishes "target died and
     * launcher took over" from "system permission dialog briefly interrupted".
     */
    val lastInterruptingPackage: String? = null
) {

    /**
     * Convenience query: is everything aligned to safely inject text right now?
     *
     * The IME Coordinator (Step 6) authoritatively computes this; we expose
     * the same property here for callers that just want a boolean. Equivalent
     * to checking all four conditions inline.
     */
    val isSafeToInject: Boolean
        get() = imeVisible &&
                inputConnectionReady &&
                focusedEditableIdentity != null &&
                !inRecovery

    companion object {
        /** Initial state at process start. All fields default. */
        val INITIAL: WorldState = WorldState()
    }
}