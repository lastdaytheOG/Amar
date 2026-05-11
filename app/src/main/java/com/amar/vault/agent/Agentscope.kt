package com.amar.vault.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier

/**
 * Qualifier for the agent's process-lifetime coroutine scope.
 *
 * Why a custom scope and not a Service / ViewModel scope:
 *   - ControlLayer submits tasks that may outlive the UI (a long-running
 *     message send shouldn't die when the user navigates away).
 *   - Service scopes are wrong because there IS no long-running foreground
 *     service for the agent in v1 — the AccessibilityService handles its own
 *     lifecycle and shouldn't own task coroutines.
 *   - A SupervisorJob ensures one failed task doesn't cascade-cancel siblings.
 *
 * Dispatchers.Default because executors are CPU-bound (tree walks, node matching).
 * IO work (DB writes) is explicitly switched via withContext(Dispatchers.IO)
 * inside CheckpointWriter.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AgentScope

/**
 * Factory helper — kept in its own object so Hilt's generated code doesn't
 * try to reflect on a top-level function when constructing the scope.
 */
object AgentScopeFactory {
    fun create(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}