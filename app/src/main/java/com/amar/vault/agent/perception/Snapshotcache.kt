package com.amar.vault.agent.perception

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory cache for the latest [UiSnapshot].
 *
 * Policy:
 *   - On window-change events, [PerceptionService] calls [invalidate] and then
 *     posts a freshly walked snapshot via [put].
 *   - On read, callers get the cached snapshot if it exists and is younger than
 *     [staleAfterMs]. Otherwise they get null → caller should force a fresh walk.
 *   - [currentFlow] lets UI or VerificationEngine observe reactively.
 *
 * Threading:
 *   - Writes come from the AccessibilityService binder thread.
 *   - Reads come from executor coroutines on Dispatchers.Default.
 *   - AtomicReference + StateFlow = no locks needed.
 *
 * Staleness:
 *   Default 2s. Walking the tree typically takes 10–80ms on the Moto G34 floor
 *   device. A 2s stale window means we serve cache for the common case (an
 *   executor firing multiple operations against a stable screen) but refresh
 *   promptly after transitions the service might have missed (e.g., dialogs
 *   that don't emit WINDOW_STATE_CHANGED).
 */
class SnapshotCache(
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val ref = AtomicReference<UiSnapshot?>(null)
    private val _flow = MutableStateFlow<UiSnapshot?>(null)

    /** Latest snapshot observable. Emits null on init and on [invalidate]. */
    val currentFlow: StateFlow<UiSnapshot?> = _flow.asStateFlow()

    /** Latest snapshot or null if cleared / never set. Does NOT check staleness. */
    val raw: UiSnapshot?
        get() = ref.get()

    /**
     * Current snapshot if it's fresh enough, otherwise null.
     * Callers treat null as "walk tree now".
     */
    fun currentIfFresh(): UiSnapshot? {
        val snap = ref.get() ?: return null
        val age = clock() - snap.capturedAt
        return if (age <= staleAfterMs) snap else null
    }

    /**
     * Returns current snapshot regardless of staleness. Use sparingly — mostly
     * for diagnostics and the Brain's "what do you see" context.
     */
    fun currentAnyAge(): UiSnapshot? = ref.get()

    /**
     * Replace the cached snapshot. Emits on [currentFlow]. Should be called by
     * PerceptionService after a successful tree walk.
     */
    fun put(snapshot: UiSnapshot) {
        ref.set(snapshot)
        _flow.value = snapshot
    }

    /**
     * Clear the cache. Called on window change events before the fresh walk
     * completes, so stale reads don't see the pre-change UI.
     */
    fun invalidate() {
        ref.set(null)
        _flow.value = null
    }

    /**
     * Milliseconds since the last [put]. Returns Long.MAX_VALUE if never set.
     */
    fun ageMs(): Long {
        val snap = ref.get() ?: return Long.MAX_VALUE
        return clock() - snap.capturedAt
    }

    companion object {
        const val DEFAULT_STALE_AFTER_MS = 2_000L
    }
}