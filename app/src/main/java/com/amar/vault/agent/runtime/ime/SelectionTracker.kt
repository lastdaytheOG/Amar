package com.amar.vault.agent.runtime.ime

import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks the most recent text-selection event per package.
 *
 * Why this exists:
 *   TYPE_VIEW_TEXT_SELECTION_CHANGED is the authoritative signal that an
 *   InputConnection has bound to a focused editable — the OS only emits it
 *   AFTER the IME has fully attached. The architecture doc identifies this
 *   as "the ultimate readiness signal."
 *
 *   We don't store selections in WorldState directly (size + privacy), but
 *   we DO need to know "was there a selection event for this package in the
 *   last N ms?" to compute inputConnectionReady. SelectionTracker is that
 *   short-lived memory.
 *
 * Lifecycle:
 *   Singleton. Updated by PerceptionService's event hook. Read by
 *   ImeCoordinator when computing readiness.
 */
class SelectionTracker {

    private val last = AtomicReference<Snapshot?>(null)

    fun record(packageId: String?, resourceId: String?, atMillis: Long) {
        last.set(Snapshot(packageId, resourceId, atMillis))
    }

    /**
     * True if a selection event was recorded for [packageId] within [withinMs].
     * Conservative — returns false if package mismatch or no recent record.
     */
    fun isRecent(packageId: String?, withinMs: Long = 1_500L): Boolean {
        if (packageId == null) return false
        val s = last.get() ?: return false
        if (s.packageId != packageId) return false
        return (System.currentTimeMillis() - s.atMillis) < withinMs
    }

    fun mostRecent(): Snapshot? = last.get()

    fun resetForTest() {
        last.set(null)
    }

    data class Snapshot(
        val packageId: String?,
        val resourceId: String?,
        val atMillis: Long
    )

    companion object {
        // Singleton via Hilt @Provides in Step 6 wiring. Could be @Singleton
        // with @Inject constructor — we use object-style provide to match
        // the existing module style.
    }
}