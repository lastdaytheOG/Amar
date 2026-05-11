package com.amar.vault.agent.brain

import com.amar.vault.agent.dsl.ActionEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface PrimaryBrain {

    val isReady: Boolean

    val state: StateFlow<BrainState>

    fun plan(request: String, context: PlanContext = PlanContext.Empty): Flow<PlanUpdate>

    suspend fun warmup()

    suspend fun shutdown()
}

sealed class BrainState {
    data object Uninitialized : BrainState()

    data class Downloading(val progress: Float, val bytesReceived: Long, val bytesTotal: Long) : BrainState()

    data object Loading : BrainState()

    data object Warming : BrainState()

    data object Ready : BrainState()

    data class Failed(val reason: BrainFailure) : BrainState()

    data object Shutdown : BrainState()
}

sealed class BrainFailure {
    data class ModelDownloadFailed(val detail: String) : BrainFailure()
    data class ModelCorrupted(val expectedSha: String, val actualSha: String) : BrainFailure()
    data class ModelLoadFailed(val detail: String) : BrainFailure()
    data class BackendUnavailable(val tried: List<String>) : BrainFailure()
    data class OutOfMemory(val detail: String) : BrainFailure()
    data class Unknown(val detail: String) : BrainFailure()
}

sealed class PlanUpdate {
    data class Delta(val text: String) : PlanUpdate()

    data class Completed(
        val envelope: ActionEnvelope,
        val rawOutput: String,
        val tokensGenerated: Int,
        val totalDurationMs: Long,
        val ttftMs: Long
    ) : PlanUpdate()

    data class Failed(
        val rawOutput: String,
        val reason: PlanFailure,
        val totalDurationMs: Long
    ) : PlanUpdate()
}

sealed class PlanFailure {
    data class InvalidEnvelope(val validationReasons: List<String>) : PlanFailure()
    data class Timeout(val tokensGenerated: Int) : PlanFailure()
    data class EngineError(val detail: String) : PlanFailure()
    data object NotReady : PlanFailure()
    data object Cancelled : PlanFailure()
}

data class PlanContext(
    val currentApp: String? = null,
    val screenSummary: String? = null,
    val recentTurns: List<ConversationTurn> = emptyList(),
    val locale: String? = null
) {
    companion object {
        val Empty = PlanContext()
    }
}

data class ConversationTurn(
    val userRequest: String,
    val planJson: String,
    val outcome: TurnOutcome,
    val timestampMs: Long
)

enum class TurnOutcome {
    SUCCEEDED, FAILED, CANCELLED, UNKNOWN
}