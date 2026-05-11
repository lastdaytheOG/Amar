package com.amar.vault.agent.control.persistence

import android.util.Log
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.StateMachine
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.control.TaskState
import com.amar.vault.agent.dsl.ActionEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes task checkpoints to the agent database asynchronously.
 *
 * - Fire-and-forget: launches on writerScope (SupervisorJob + IO), failures
 *   are logged but never bubble up. Checkpointing is audit trail, not
 *   correctness-critical — the task succeeds or fails regardless.
 *
 * - attach(ctx): wires a TaskContext so every state transition is persisted
 *   automatically. Called by ControlLayer on task creation.
 */
@Singleton
class CheckpointWriter @Inject constructor(
    private val dao: TaskCheckpointDao,
    scope: CoroutineScope
) {
    private val writerScope = CoroutineScope(
        scope.coroutineContext + SupervisorJob() + Dispatchers.IO
    )

    /**
     * Subscribe to a TaskContext's state flow. Writes the envelope once on
     * Pending, then a checkpoint row for every state transition afterward.
     */
    fun attach(ctx: TaskContext) {
        writerScope.launch {
            val initial = ctx.current
            if (initial is TaskState.Pending) {
                writeEnvelope(ctx.taskId, initial.envelope)
            }
            var previous: TaskState = initial
            ctx.state.collect { state ->
                if (state === previous) return@collect
                val label = StateMachine.transitionName(previous, state)
                writeTransition(ctx.taskId, previous, state, label)
                previous = state
            }
        }
    }

    fun writeEnvelope(taskId: String, envelope: ActionEnvelope) {
        writerScope.launch {
            try {
                val envelopeJson = runCatching {
                    Json.Default.encodeToString(ActionEnvelope.serializer(), envelope)
                }.getOrElse {
                    Log.w(TAG, "envelope encode failed, storing placeholder: ${it.message}")
                    """{"encode_error":"${it.message?.take(100)}"}"""
                }

                dao.insertEnvelope(
                    TaskEnvelopeSnapshot(
                        taskId = taskId,
                        envelopeJson = envelopeJson,
                        createdAt = System.currentTimeMillis()
                    )
                )
            } catch (t: Throwable) {
                Log.w(TAG, "writeEnvelope($taskId) failed: ${t.message}")
            }
        }
    }

    fun writeTransition(
        taskId: String,
        fromState: TaskState,
        toState: TaskState,
        transitionLabel: String
    ) {
        writerScope.launch {
            try {
                dao.insertCheckpoint(
                    TaskCheckpoint(
                        taskId = taskId,
                        stateName = toState.javaClass.simpleName,
                        transition = transitionLabel,
                        timestamp = System.currentTimeMillis(),
                        attemptNumber = attemptNumberOf(toState),
                        statePayloadJson = payloadOf(toState),
                        isTerminal = toState.isTerminal
                    )
                )
            } catch (t: Throwable) {
                Log.w(TAG, "writeTransition($taskId, $transitionLabel) failed: ${t.message}")
            }
        }
    }

    suspend fun pruneOlderThan(keepDays: Int = 7): PruneStats {
        val cutoff = System.currentTimeMillis() - (keepDays * 24L * 60 * 60 * 1000)
        return try {
            val envelopes = dao.pruneEnvelopesBefore(cutoff)
            val checkpoints = dao.pruneTerminalBefore(cutoff)
            PruneStats(envelopesRemoved = envelopes, checkpointsRemoved = checkpoints)
        } catch (t: Throwable) {
            Log.w(TAG, "prune failed: ${t.message}")
            PruneStats(envelopesRemoved = 0, checkpointsRemoved = 0, error = t.message)
        }
    }

    private fun attemptNumberOf(state: TaskState): Int = when (state) {
        is TaskState.Running    -> state.attemptNumber
        is TaskState.Verifying  -> state.attemptNumber
        is TaskState.Retrying   -> state.nextAttemptNumber
        is TaskState.Succeeded  -> state.attemptsUsed
        is TaskState.Failed     -> state.attemptsUsed
        is TaskState.Pending, is TaskState.Cancelled -> -1
    }

    private fun payloadOf(state: TaskState): String {
        val obj: JsonObject = when (state) {
            is TaskState.Pending -> buildJsonObject { }
            is TaskState.Running -> buildJsonObject {
                put("started_at", state.startedAt)
            }
            is TaskState.Verifying -> buildJsonObject {
                put("execution_duration_ms", state.executionDurationMs)
            }
            is TaskState.Retrying -> buildJsonObject {
                put("backoff_until", state.backoffUntil)
                put("failure", failureSummary(state.lastFailure))
            }
            is TaskState.Succeeded -> buildJsonObject {
                put("total_duration_ms", state.totalDurationMs)
            }
            is TaskState.Failed -> buildJsonObject {
                put("total_duration_ms", state.totalDurationMs)
                put("failure", failureSummary(state.reason))
            }
            is TaskState.Cancelled -> buildJsonObject {
                put("reason", state.reason.toString())
            }
        }
        return obj.toString()
    }

    private fun failureSummary(reason: FailureReason): String {
        return "${reason.javaClass.simpleName}: ${reason.toString().take(200)}"
    }

    companion object {
        private const val TAG = "CheckpointWriter"
    }
}

data class PruneStats(
    val envelopesRemoved: Int,
    val checkpointsRemoved: Int,
    val error: String? = null
)