package com.amar.vault.agent.control.observers

import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.control.TaskState
import com.amar.vault.agent.control.TaskTerminalObserver
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.perception.PackageWatchlist
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Terminal observer that releases package watchlist refcounts when tasks finish.
 *
 * The problem this solves:
 *   OpenAppExecutor calls `watchlist.acquire(packageId)` when it launches an
 *   app — that tells PerceptionService to start caching snapshots for that
 *   package. Without a matching release() when the task ends, every OpenApp
 *   leaks a refcount. After 100 tasks, we're caching snapshots for 100 apps,
 *   wasting memory and tree-walk CPU on every window change.
 *
 *   The symmetric release COULD live inside OpenAppExecutor, but it has to
 *   fire exactly once per task regardless of outcome — success, failure, or
 *   cancellation. The cleanest way to guarantee that is to hook it to the
 *   TERMINAL state transition, not the executor's return.
 *
 * Why a terminal observer:
 *   ControlLayer calls terminal observers on every task's final transition
 *   (Succeeded / Failed / Cancelled). The observer inspects the envelope,
 *   figures out which packages were acquired during execution, and releases
 *   them. Idempotent via the watchlist's refcount semantics.
 *
 * Scope:
 *   v1 only handles OpenApp's acquire. SendMessage also acquires the target
 *   app's package temporarily (to watch for confirmation snapshots) — that
 *   acquire is inside SendMessageExecutor and released by the executor itself
 *   on exit. If we later add more executors that acquire, extend this observer
 *   or add a parallel one.
 */
@Singleton
class WatchlistReleaser @Inject constructor(
    private val watchlist: PackageWatchlist
) : TaskTerminalObserver {

    override fun onTerminal(ctx: TaskContext, terminal: TaskState) {
        // Figure out what to release based on the envelope's action.
        val envelope = when (terminal) {
            is TaskState.Succeeded,
            is TaskState.Failed,
            is TaskState.Cancelled -> ctx.current.envelopeOrNull()
            else -> null
        } ?: return

        when (val action = envelope.action) {
            is AgentAction.OpenApp -> {
                // Release by packageId if we have it, else by the known alias.
                // OpenAppExecutor acquires exactly one of these two on launch.
                val pkg = action.packageId ?: resolveAliasPackage(action.app)
                pkg?.let { watchlist.release(it) }
            }
            else -> {
                // Other actions don't acquire package watchlist refcounts in v1.
                // SendMessage etc. manage their own acquire/release internally.
            }
        }
    }

    /**
     * Mirror of the hardcoded alias map in OpenAppExecutor. Kept in sync manually
     * — if OpenAppExecutor grows its alias list, add entries here too.
     * A future refactor could share this map via a singleton; v1 accepts the
     * duplication to avoid introducing a new Hilt binding just for a lookup table.
     */
    private fun resolveAliasPackage(app: String): String? {
        return when (app.lowercase()) {
            "whatsapp" -> "com.whatsapp"
            "telegram" -> "org.telegram.messenger"
            "instagram" -> "com.instagram.android"
            "gmail" -> "com.google.android.gm"
            "maps" -> "com.google.android.apps.maps"
            "phone" -> "com.google.android.dialer"
            "messages" -> "com.google.android.apps.messaging"
            "camera" -> "com.android.camera2"
            "settings" -> "com.android.settings"
            "chrome" -> "com.android.chrome"
            "paytm" -> "net.one97.paytm"
            "phonepe" -> "com.phonepe.app"
            "gpay" -> "com.google.android.apps.nbu.paisa.user"
            else -> null
        }
    }

    private fun TaskState.envelopeOrNull() = when (this) {
        is TaskState.Pending -> envelope
        is TaskState.Running -> envelope
        is TaskState.Verifying -> envelope
        is TaskState.Retrying -> envelope
        is TaskState.Succeeded, is TaskState.Failed, is TaskState.Cancelled -> null
    }
}