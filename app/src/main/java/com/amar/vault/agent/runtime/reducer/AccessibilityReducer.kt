package com.amar.vault.agent.runtime.reducer

import com.amar.vault.agent.runtime.events.AgentEvent
import com.amar.vault.agent.runtime.state.SemanticIdentity
import com.amar.vault.agent.runtime.state.WorldState

/**
 * Pure reducer: (state, event) → new state.
 *
 * Properties this MUST preserve:
 *   - Pure: no I/O, no side effects, no Logs (caller logs).
 *   - Deterministic: same inputs always produce same outputs.
 *   - Total: every AgentEvent subtype handled. Sealed when() enforces this
 *     at compile time — adding a new event without updating reducer = warning.
 *   - Monotonic seq: only applies events with sequence > state.lastEventSequence.
 *
 * Why pure:
 *   The whole point of event sourcing is replayability. If reducer.reduce()
 *   has side effects, replaying the event log produces different results than
 *   the original run. Side effects belong in subscribers/executors, not here.
 *
 * Not handled here (today):
 *   - SemanticIdentity construction from raw nodes — that's Step 7's resolver.
 *     Reducer just propagates identities that have been computed elsewhere.
 *   - Overlay detection logic — Step 13. Reducer accepts OverlayDetected
 *     events but doesn't decide WHEN to emit them.
 *   - Recovery state transitions — Step 12.
 */
object AccessibilityReducer {

    /**
     * Heuristic: is this package an Input Method Editor (keyboard)?
     *
     * IME windows fire WindowContentChanged events frequently — every
     * suggestion bar update, every layout swap, every internal state
     * change. If we let these events flip foregroundPackage to the IME,
     * downstream consumers (InputConnectionMonitor) see the IME as
     * "foreground app" and conclude inputConnectionReady=false because
     * the IME has no focused editable of its own.
     *
     * The user-visible foreground is the app BEHIND the IME, not the
     * IME itself. So we ignore IME-sourced package transitions and
     * keep the existing foregroundPackage.
     *
     * Match common IME packages plus any package whose id contains
     * "inputmethod" (covers Gboard, Samsung Honeyboard, SwiftKey,
     * AOSP latin, custom OEM keyboards).
     */
    private fun isImePackage(packageId: String?): Boolean {
        if (packageId.isNullOrEmpty()) return false
        return packageId.contains("inputmethod", ignoreCase = true) ||
                packageId == "com.samsung.android.honeyboard" ||
                packageId == "com.touchtype.swiftkey" ||
                packageId == "com.touchtype.swiftkey.beta" ||
                packageId == "com.microsoft.swiftkey"
    }

    /**
     * Apply a single event to the current state.
     *
     * Returns:
     *   - The new state if the event was relevant and applied.
     *   - The OLD state unchanged if the event was stale (lower sequence)
     *     or carries no state-relevant information.
     *
     * Callers (Step 2 of this file, WorldStateStore.reduce {}) should compare
     * the returned reference to detect whether anything changed.
     */
    fun reduce(state: WorldState, event: AgentEvent): WorldState {
        // Drop stale events. Bus publishes in-order, but subscribers may
        // process out-of-order under load. Sequence guard makes that safe.
        if (event.sequence > 0L && event.sequence <= state.lastEventSequence) {
            return state
        }

        return when (event) {
            // ----------------------------------------------------------------
            // ACCESSIBILITY: foreground context, window structure, focus, text
            // ----------------------------------------------------------------

            is AgentEvent.Accessibility.WindowStateChanged -> {
                // Don't let IME WindowStateChanged events overwrite the app's
                // foreground package. The IME is a transient overlay window;
                // the "foreground app" is what's behind it.
                if (isImePackage(event.packageId)) {
                    state.copy(lastEventSequence = event.sequence)
                } else {
                    state.copy(
                        foregroundPackage = event.packageId ?: state.foregroundPackage,
                        foregroundWindowClass = event.windowClass ?: state.foregroundWindowClass,
                        lastEventSequence = event.sequence
                    )
                }
            }

            is AgentEvent.Accessibility.WindowContentChanged -> {
                // IME WindowContentChanged events fire many times per second
                // during typing. Ignoring them prevents readiness flap.
                if (isImePackage(event.packageId)) {
                    state.copy(lastEventSequence = event.sequence)
                } else {
                    state.copy(
                        foregroundPackage = event.packageId ?: state.foregroundPackage,
                        lastEventSequence = event.sequence
                    )
                }
            }

            is AgentEvent.Accessibility.WindowsChanged -> state.copy(
                lastEventSequence = event.sequence
                // Foreground package is updated by WindowStateChanged events,
                // not by raw enumeration. The reducer should not infer
                // "this app is foreground" from WindowsChanged alone.
            )

            is AgentEvent.Accessibility.ViewFocused -> {
                // Only update focusedEditableIdentity when the focused node is
                // editable. Non-editable focus events (button getting a11y
                // focus, list item highlight) must NOT clear an established
                // editable identity — the EditText still holds input focus
                // even when accessibility focus moves around.
                if (event.isEditable && event.packageId != null) {
                    val newIdentity = SemanticIdentity.Unknown(
                        packageId = event.packageId,
                        generationId = state.generationId,
                        hint = "editable_focused:${event.resourceId ?: event.className ?: "anon"}"
                    )
                    state.copy(
                        focusedEditableIdentity = newIdentity,
                        lastEventSequence = event.sequence
                    )
                } else {
                    // Non-editable focus event. Leave focusedEditableIdentity
                    // alone — it gets cleared only by WindowStateChanged to a
                    // different app, or by explicit ImeVisibilityChanged(false).
                    state.copy(lastEventSequence = event.sequence)
                }
            }

            is AgentEvent.Accessibility.TextChanged -> state.copy(
                lastEventSequence = event.sequence
                // TextChanged is consumed by the Confidence Engine (Step 10).
                // Reducer just bookkeeps the sequence — text content itself
                // does not belong in WorldState (privacy + size).
            )

            is AgentEvent.Accessibility.TextSelectionChanged -> {
                // This is the AUTHORITATIVE input-ready signal per the
                // architecture doc. Selection changing means the IME has
                // bound an InputConnection to the focused editable and
                // text injection is now safe.
                val ready = state.imeVisible && state.focusedEditableIdentity != null
                state.copy(
                    inputConnectionReady = ready,
                    lastEventSequence = event.sequence
                )
            }

            // ----------------------------------------------------------------
            // EXECUTOR: injection lifecycle. Reducer just records sequence.
            // Actual semantic effect handled by Confidence Engine (Step 10).
            // ----------------------------------------------------------------

            is AgentEvent.Executor.InjectionStarted,
            is AgentEvent.Executor.InjectionSucceeded,
            is AgentEvent.Executor.InjectionFailed -> state.copy(
                lastEventSequence = event.sequence
            )

            // ----------------------------------------------------------------
            // RUNTIME: phase/recovery/overlay/cancellation
            // ----------------------------------------------------------------

            is AgentEvent.Runtime.SearchActivated -> state.copy(
                lastEventSequence = event.sequence
                // SearchActivated is a hint for the orchestrator (Step 17) —
                // reducer doesn't store a "search is active" flag because
                // the actual signal is focusedEditableIdentity becoming non-null.
            )

            is AgentEvent.Runtime.ImeVisibilityChanged -> {
                val newReady = event.visible &&
                        state.focusedEditableIdentity != null &&
                        state.inputConnectionReady
                state.copy(
                    imeVisible = event.visible,
                    // If IME hid, inputConnection is definitionally not ready.
                    inputConnectionReady = if (!event.visible) false else newReady,
                    lastEventSequence = event.sequence
                )
            }

            is AgentEvent.Runtime.InputReadiness -> state.copy(
                inputConnectionReady = event.ready,
                lastEventSequence = event.sequence
            )

            is AgentEvent.Runtime.RecoveryStarted -> state.copy(
                inRecovery = true,
                lastEventSequence = event.sequence
            )

            is AgentEvent.Runtime.RecoveryCompleted -> state.copy(
                inRecovery = false,
                lastEventSequence = event.sequence
            )

            is AgentEvent.Runtime.OverlayDetected -> state.copy(
                lastEventSequence = event.sequence
                // Reducer doesn't flip a flag for overlay-active because
                // the OverlayManager (Step 13) handles dismissal and the
                // resulting state change comes via WindowStateChanged.
            )

            is AgentEvent.Runtime.WorkflowCancelled -> {
                val wasActive = state.activeWorkflowId == event.workflowId
                state.copy(
                    activeWorkflowId = if (wasActive) null else state.activeWorkflowId,
                    lastEventSequence = event.sequence
                )
            }

            // ----------------------------------------------------------------
            // DIAGNOSTIC: never affects state, only sequence bookkeeping.
            // ----------------------------------------------------------------

            is AgentEvent.Diagnostic.Heartbeat,
            is AgentEvent.Diagnostic.CriticalViolation -> state.copy(
                lastEventSequence = event.sequence
            )
        }
    }
}