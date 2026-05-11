package com.amar.vault.agent.control.executors

import com.amar.vault.agent.control.ActionExecutor
import com.amar.vault.agent.control.CancellationReason
import com.amar.vault.agent.control.ControlLayer
import com.amar.vault.agent.control.ExecutionResult
import com.amar.vault.agent.control.ExecutorTier
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.control.TaskState
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.AgentAction
import kotlinx.coroutines.flow.first

class SequenceExecutor(
    private val lazyControlLayer: dagger.Lazy<ControlLayer>
) : ActionExecutor {

    override val handles: ActionKind = ActionKind.SEQUENCE
    override val tier: ExecutorTier = ExecutorTier.INTENT

    override fun isAvailable(): Boolean = true

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        val sequence = action as? AgentAction.Sequence ?: return ExecutionResult.FatalFailure(
            reason = FailureReason.Unexpected("SequenceExecutor received ${action.kind}"),
            durationMs = 0
        )

        val controlLayer = lazyControlLayer.get()
        val startedAt = System.currentTimeMillis()
        var lastResultData = emptyMap<String, String>()

        for (step in sequence.steps) {
            if (ctx.isCancelRequested) {
                return ExecutionResult.Cancelled(
                    ctx.pendingCancellationReason ?: CancellationReason.UserRequested
                )
            }

            val stepCtx = controlLayer.submitEnvelope(step)
            val finalState = stepCtx.state.first { it.isTerminal }

            when (finalState) {
                is TaskState.Failed -> return ExecutionResult.Failed(
                    reason = FailureReason.Unexpected("Step failed: ${finalState.reason}"),
                    durationMs = System.currentTimeMillis() - startedAt
                )
                is TaskState.Cancelled -> return ExecutionResult.Cancelled(finalState.reason)
                is TaskState.Succeeded -> lastResultData = finalState.resultData
                else -> {}
            }
        }

        return ExecutionResult.Executed(
            durationMs = System.currentTimeMillis() - startedAt,
            resultData = lastResultData
        )
    }
}
