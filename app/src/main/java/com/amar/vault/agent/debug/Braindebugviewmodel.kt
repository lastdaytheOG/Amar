package com.amar.vault.agent.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amar.vault.agent.brain.BrainState
import com.amar.vault.agent.brain.ModelSpec
import com.amar.vault.agent.brain.PlanContext
import com.amar.vault.agent.brain.PlanUpdate
import com.amar.vault.agent.brain.PrimaryBrain
import com.amar.vault.agent.control.ControlLayer
import com.amar.vault.agent.control.TaskState
import com.amar.vault.agent.coordinator.AgentCoordinator
import com.amar.vault.agent.coordinator.CoordinatorProgress
import com.amar.vault.agent.coordinator.CoordinatorResult
import com.amar.vault.agent.dsl.ActionEnvelope
import com.amar.vault.agent.intent.IntentRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrainDebugViewModel @Inject constructor(
    private val brain: PrimaryBrain,
    private val controlLayer: ControlLayer,
    private val coordinator: AgentCoordinator,
    private val modelSpec: ModelSpec
) : ViewModel() {

    val brainState: StateFlow<BrainState> = brain.state

    private val _modelOutput = MutableStateFlow("")
    val modelOutput: StateFlow<String> = _modelOutput.asStateFlow()

    private val _executionTrace = MutableStateFlow<List<String>>(emptyList())
    val executionTrace: StateFlow<List<String>> = _executionTrace.asStateFlow()

    private val _metrics = MutableStateFlow<PlanMetrics?>(null)
    val metrics: StateFlow<PlanMetrics?> = _metrics.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val selectedModelName: String = modelSpec.displayName

    private var activeJob: Job? = null

    fun warmupBrain() {
        viewModelScope.launch { brain.warmup() }
    }

    fun submit(request: String) {
        if (request.isBlank()) return
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _busy.value = true
            _modelOutput.value = ""
            _executionTrace.value = listOf("[submit] \"$request\"")
            _metrics.value = null
            try {
                val routed = IntentRouter.route(request)
                if (routed != null) {
                    _modelOutput.value = routed.action.toString()
                    _executionTrace.value = _executionTrace.value +
                            "[router] fast-path matched, no LLM call"
                    dispatchAndStream(routed)
                    return@launch
                }
                if (brain.state.value !is BrainState.Ready) {
                    _executionTrace.value = _executionTrace.value +
                            "[brain not ready - current state: ${brain.state.value}]"
                    return@launch
                }
                var envelope: ActionEnvelope? = null
                brain.plan(request, PlanContext()).collect { update ->
                    when (update) {
                        is PlanUpdate.Delta -> {
                            _modelOutput.value = _modelOutput.value + update.text
                        }
                        is PlanUpdate.Completed -> {
                            _metrics.value = PlanMetrics(
                                ttftMs = update.ttftMs,
                                totalDurationMs = update.totalDurationMs,
                                tokensGenerated = update.tokensGenerated
                            )
                            envelope = update.envelope
                            _executionTrace.value = _executionTrace.value +
                                    "[brain] ttft=${update.ttftMs}ms total=${update.totalDurationMs}ms tokens=${update.tokensGenerated}"
                        }
                        is PlanUpdate.Failed -> {
                            _executionTrace.value = _executionTrace.value +
                                    "[brain failed] ${update.reason}"
                        }
                    }
                }
                val e = envelope ?: return@launch
                dispatchAndStream(e)
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Multi-step goal via AgentCoordinator. Brain parses intent, router
     * picks a plan, executor loop runs steps until terminal.
     */
    fun submitGoal(request: String) {
        if (request.isBlank()) return
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _busy.value = true
            _modelOutput.value = ""
            _executionTrace.value = listOf("[goal] \"$request\"")
            _metrics.value = null
            try {
                if (brain.state.value !is BrainState.Ready) {
                    _executionTrace.value = _executionTrace.value +
                            "[brain not ready - current state: ${brain.state.value}]"
                    return@launch
                }
                val progressJob = launch {
                    coordinator.progress.collect { p ->
                        _executionTrace.value = _executionTrace.value + formatProgress(p)
                    }
                }
                val result = try {
                    coordinator.runGoal(request)
                } finally {
                    progressJob.cancel()
                }
                _executionTrace.value = _executionTrace.value + formatResult(result)
            } catch (t: Throwable) {
                _executionTrace.value = _executionTrace.value +
                        "[exception] ${t.javaClass.simpleName}: ${t.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun dispatchAndStream(envelope: ActionEnvelope) {
        _executionTrace.value = _executionTrace.value + "[dispatch] ${envelope.action.kind}"
        val ctx = controlLayer.submitEnvelope(envelope)
        ctx.state
            .takeWhile { !it.isTerminal }
            .collect { st ->
                _executionTrace.value = _executionTrace.value + formatState(st)
            }
        _executionTrace.value = _executionTrace.value + formatState(ctx.current)
    }

    fun cancel() {
        activeJob?.cancel()
        _busy.value = false
    }

    private fun formatState(state: TaskState): String = when (state) {
        is TaskState.Pending    -> "  Pending"
        is TaskState.Running    -> "  Running attempt=${state.attemptNumber}"
        is TaskState.Verifying  -> "  Verifying (exec=${state.executionDurationMs}ms)"
        is TaskState.Retrying   -> "  Retrying next=${state.nextAttemptNumber}"
        is TaskState.Succeeded  -> "✓ Succeeded attempts=${state.attemptsUsed} dur=${state.totalDurationMs}ms"
        is TaskState.Failed     -> "✗ Failed attempts=${state.attemptsUsed} reason=${state.reason}"
        is TaskState.Cancelled  -> "∅ Cancelled"
    }

    private fun formatProgress(p: CoordinatorProgress): String = when (p) {
        is CoordinatorProgress.Idle      -> "[coord] idle"
        is CoordinatorProgress.Parsing   -> "[coord] parsing: \"${p.input}\""
        is CoordinatorProgress.Parsed    -> "[coord] parsed → intent=${p.parsed.intent} app='${p.parsed.app}' query='${p.parsed.query ?: "—"}'"
        is CoordinatorProgress.Routed    -> "[coord] routed → ${p.plan::class.simpleName}"
        is CoordinatorProgress.Step      -> "[coord] step ${p.index}: ${p.plan::class.simpleName}"
        is CoordinatorProgress.Finished  -> "[coord] finished"
    }

    private fun formatResult(r: CoordinatorResult): String = when (r) {
        is CoordinatorResult.Completed       -> "✓ Goal completed (${r.stepsExecuted} steps, screen=${r.finalScreen})"
        is CoordinatorResult.StoppedAtGate   -> "⏸ Stopped at gate: ${r.screen} (${r.stepsExecuted} steps)"
        is CoordinatorResult.Failed          -> "✗ Goal failed: ${r.reason} (${r.stepsExecuted} steps)"
        is CoordinatorResult.MaxStepsReached -> "✗ Max steps reached (${r.stepsExecuted}, last=${r.lastScreen})"
    }
}

data class PlanMetrics(
    val ttftMs: Long,
    val totalDurationMs: Long,
    val tokensGenerated: Int
)