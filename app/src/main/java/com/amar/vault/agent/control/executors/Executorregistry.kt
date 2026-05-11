package com.amar.vault.agent.control

import com.amar.vault.agent.dsl.ActionKind
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry that picks the right executor for an action, enforcing the
 * 3-tier fallback priority per spec §4:
 *
 *   1. INTENT        (direct app intents — fastest, preferred)
 *   2. SYSTEM_API    (SmsManager, TelecomManager — reliable, no UI)
 *   3. ACCESSIBILITY (fallback — tree traversal, slowest, most fragile)
 *
 * For a given [ActionKind] the registry returns executors in tier order, filtered
 * by [ActionExecutor.isAvailable]. The ControlLayer walks this list on failures.
 *
 * Registration is one-time at app start (wired via Hilt's AgentModule).
 */
class ExecutorRegistry {

    private val byAction: MutableMap<ActionKind, MutableList<ActionExecutor>> =
        ConcurrentHashMap()

    /**
     * Register an executor. Safe to call from any thread during init.
     * After init, the registry should be treated as read-only.
     */
    fun register(executor: ActionExecutor) {
        val list = byAction.getOrPut(executor.handles) { mutableListOf() }
        synchronized(list) {
            list += executor
            // Keep the list sorted by tier ordinal so iteration order == priority order.
            list.sortBy { it.tier.ordinal }
        }
    }

    /**
     * Returns the ordered list of executors for [kind], highest priority first,
     * filtered to only those currently available.
     *
     * Empty list ⇒ no capable executor right now. The ControlLayer turns this into
     * [FailureReason.NoExecutor] or a more specific reason (e.g., AccessibilityUnavailable)
     * depending on why every candidate was filtered out.
     */
    fun availableExecutorsFor(kind: ActionKind): List<ActionExecutor> {
        val all = byAction[kind] ?: return emptyList()
        val snapshot = synchronized(all) { all.toList() }
        return snapshot.filter { it.isAvailable() }
    }

    /**
     * Returns ALL registered executors for [kind] regardless of availability.
     * Used by the ControlLayer to distinguish "no executor registered" (config bug)
     * from "all executors unavailable right now" (recoverable, may fire PermissionDenied
     * or AccessibilityUnavailable).
     */
    fun allExecutorsFor(kind: ActionKind): List<ActionExecutor> {
        val all = byAction[kind] ?: return emptyList()
        return synchronized(all) { all.toList() }
    }

    /** Diagnostic: describe what's registered. Useful for settings / debug screens. */
    fun describe(): Map<ActionKind, List<String>> {
        return byAction.mapValues { (_, execs) ->
            synchronized(execs) {
                execs.map { "${it.tier.name}/${it::class.simpleName}" }
            }
        }
    }
}