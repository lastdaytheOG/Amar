package com.amar.vault.agent.coordinator

import com.amar.vault.agent.capability.CapabilityRouter
import com.amar.vault.agent.capability.ExecutionPlan
import com.amar.vault.agent.capability.RoutingResult
import com.amar.vault.agent.capability.UnresolvableReason
import com.amar.vault.agent.control.ControlLayer
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.control.TaskState
import com.amar.vault.agent.dsl.ActionEnvelope
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.execution.ExecutionOutcome
import com.amar.vault.agent.execution.NativeSearchExecutor
import com.amar.vault.agent.execution.UiSearchExecutor
import com.amar.vault.agent.intent.IntentEntityParser
import com.amar.vault.agent.intent.ParsedIntent
import com.amar.vault.agent.observation.AgentStateObserver
import com.amar.vault.agent.observation.ScreenType
import com.amar.vault.agent.workflow.WorkflowRegistry
import com.amar.vault.agent.workflow.WorkflowResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 4: AgentCoordinator — the agent loop.
 *
 * This is the conductor. It owns the "keep going until done" behavior:
 *
 *   parse intent -> route -> execute -> observe -> decide -> repeat
 *
 * Unlike the ControlLayer (which runs a SINGLE envelope end-to-end with
 * retries), the Coordinator runs MULTIPLE envelopes in sequence,
 * re-evaluating state after each step and choosing what to do next.
 *
 * Safety:
 *   - Hard step cap (MAX_STEPS) prevents runaway loops on weak model
 *     responses. If the loop hits the cap without terminating, we stop and
 *     report MaxStepsReached rather than letting the agent drift forever.
 *   - Every action goes through ControlLayer, which enforces its own
 *     timeouts, verification, and retry policy.
 *   - Stop conditions (payment screen, confirmation gates) fire AT THE TOP
 *     of each loop iteration, so even if an executor succeeds unexpectedly
 *     into a payment screen we halt before firing the next step.
 *
 * State exposure:
 *   `progress` is a StateFlow<CoordinatorProgress> the UI can bind to for
 *   live step-by-step feedback. Every meaningful transition emits a new
 *   progress value.
 */
@Singleton
class AgentCoordinator @Inject constructor(
    private val intentParser: IntentEntityParser,
    private val router: CapabilityRouter,
    private val controlLayer: ControlLayer,
    private val nativeSearchExecutor: NativeSearchExecutor,
    private val uiSearchExecutor: UiSearchExecutor,
    private val workflowRegistry: WorkflowRegistry,
    private val stateObserver: AgentStateObserver
) {

    private val _progress = MutableStateFlow<CoordinatorProgress>(CoordinatorProgress.Idle)
    val progress: StateFlow<CoordinatorProgress> = _progress

    /**
     * Run a user goal end-to-end. Returns a terminal result once the loop
     * concludes.
     */
    suspend fun runGoal(userInput: String): CoordinatorResult {
        emit(CoordinatorProgress.Parsing(userInput))

        val parsed = intentParser.parse(userInput)
            ?: return finish(
                CoordinatorResult.Failed(
                    reason = "could not parse intent from input",
                    stepsExecuted = 0
                )
            )

        emit(CoordinatorProgress.Parsed(parsed))

        val routing = router.route(parsed)
        if (routing is RoutingResult.Unresolvable) {
            return finish(
                CoordinatorResult.Failed(
                    reason = formatUnresolvable(routing.reason),
                    stepsExecuted = 0
                )
            )
        }
        val plan = (routing as RoutingResult.Plan)
        emit(CoordinatorProgress.Routed(plan.plan))

        var stepCount = 0
        while (stepCount < MAX_STEPS) {
            // Stop-condition check BEFORE acting, so a prior step landing
            // on payment halts us immediately.
            val currentScreen = stateObserver.currentScreen()
            if (isStopScreen(currentScreen)) {
                return finish(
                    CoordinatorResult.StoppedAtGate(
                        screen = currentScreen,
                        stepsExecuted = stepCount
                    )
                )
            }

            emit(CoordinatorProgress.Step(stepCount + 1, plan.plan))

            val outcome = executeOnce(plan.plan, stepCount, parsed)
            stepCount++

            when (outcome) {
                is StepOutcome.Done -> {
                    return finish(
                        CoordinatorResult.Completed(
                            stepsExecuted = stepCount,
                            finalScreen = stateObserver.currentScreen()
                        )
                    )
                }
                is StepOutcome.Continue -> {
                    // Loop again; state observer will pick up the new screen
                    // on next iteration.
                }
                is StepOutcome.Failed -> {
                    return finish(
                        CoordinatorResult.Failed(
                            reason = outcome.detail,
                            stepsExecuted = stepCount
                        )
                    )
                }
                is StepOutcome.StoppedAtGate -> {
                    return finish(
                        CoordinatorResult.StoppedAtGate(
                            screen = outcome.screen,
                            stepsExecuted = stepCount
                        )
                    )
                }
            }
        }

        return finish(
            CoordinatorResult.MaxStepsReached(
                stepsExecuted = stepCount,
                lastScreen = stateObserver.currentScreen()
            )
        )
    }

    // =========================================================================
    // Per-step execution
    // =========================================================================

    private suspend fun executeOnce(
        plan: ExecutionPlan,
        stepIndex: Int,
        parsed: ParsedIntent
    ): StepOutcome {
        return when (plan) {
            is ExecutionPlan.OpenApp -> executeOpenApp(plan)
            is ExecutionPlan.NativeSearch -> executeNativeSearch(plan)
            is ExecutionPlan.UiSearch -> executeUiSearch(plan)
            is ExecutionPlan.OrderWorkflow -> executeOrderWorkflow(plan)
        }
    }

    private suspend fun executeOpenApp(plan: ExecutionPlan.OpenApp): StepOutcome {
        val envelope = ActionEnvelope(
            action = AgentAction.OpenApp(app = plan.packageId, packageId = plan.packageId)
        )
        val taskCtx = controlLayer.submitEnvelope(envelope)
        val terminal = awaitTerminal(taskCtx)
        return when (terminal) {
            is TaskState.Succeeded -> StepOutcome.Done
            is TaskState.Failed    -> StepOutcome.Failed(
                detail = "open_app failed: ${terminal.reason}"
            )
            is TaskState.Cancelled -> StepOutcome.Failed(detail = "cancelled")
            else -> StepOutcome.Failed(detail = "unexpected state $terminal")
        }
    }

    private suspend fun executeNativeSearch(plan: ExecutionPlan.NativeSearch): StepOutcome {
        val outcome = nativeSearchExecutor.execute(plan)
        return when (outcome) {
            is ExecutionOutcome.Started      -> StepOutcome.Done
            is ExecutionOutcome.NotSupported -> {
                // Fall back to UI search by re-routing.
                val fallback = ExecutionPlan.UiSearch(plan.packageId, plan.query)
                executeUiSearch(fallback)
            }
            is ExecutionOutcome.Failed       -> StepOutcome.Failed(outcome.detail)
        }
    }

    private suspend fun executeUiSearch(plan: ExecutionPlan.UiSearch): StepOutcome {
        // UiSearchExecutor needs a TaskContext for its internal primitives.
        // We synthesize one by submitting a no-op envelope first, then using
        // its context. This is a minor hack — a cleaner design would have the
        // UiSearchExecutor manage its own context; deferred.
        val placeholderEnv = ActionEnvelope(action = AgentAction.Wait(ms = 1))
        val ctx = controlLayer.submitEnvelope(placeholderEnv)
        awaitTerminal(ctx)

        val outcome = uiSearchExecutor.execute(plan, ctx)
        return when (outcome) {
            is ExecutionOutcome.Started      -> StepOutcome.Done
            is ExecutionOutcome.NotSupported -> StepOutcome.Failed(outcome.detail)
            is ExecutionOutcome.Failed       -> StepOutcome.Failed(outcome.detail)
        }
    }

    private suspend fun executeOrderWorkflow(plan: ExecutionPlan.OrderWorkflow): StepOutcome {
        val workflow = workflowRegistry.get(plan.workflowId)
            ?: return StepOutcome.Failed("unknown workflow: ${plan.workflowId}")

        val result = workflow.run(query = plan.query)
        return when (result) {
            is WorkflowResult.StoppedAtGate -> StepOutcome.StoppedAtGate(result.screen)
            is WorkflowResult.Completed     -> StepOutcome.Done
            is WorkflowResult.Failed        -> StepOutcome.Failed(result.detail)
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun isStopScreen(screen: ScreenType): Boolean =
        screen == ScreenType.PAYMENT ||
                screen == ScreenType.CONFIRMATION_REQUIRED

    private suspend fun awaitTerminal(ctx: TaskContext): TaskState {
        return ctx.state.first { it.isTerminal }
    }

    private fun emit(progress: CoordinatorProgress) {
        _progress.value = progress
    }

    private fun finish(result: CoordinatorResult): CoordinatorResult {
        _progress.value = CoordinatorProgress.Finished(result)
        return result
    }

    private fun formatUnresolvable(reason: UnresolvableReason): String = when (reason) {
        is UnresolvableReason.AppNotInstalled      -> "app not installed: ${reason.appName}"
        is UnresolvableReason.MissingQuery         -> "missing query for intent"
        is UnresolvableReason.NoWorkflowRegistered -> "no workflow for ${reason.packageId}"
    }

    private sealed class StepOutcome {
        data object Done : StepOutcome()
        data object Continue : StepOutcome()
        data class Failed(val detail: String) : StepOutcome()
        data class StoppedAtGate(val screen: ScreenType) : StepOutcome()
    }

    companion object {
        /** Hard cap on iterations. Matches the Android guidance for agent loops. */
        private const val MAX_STEPS = 12
    }
}

// =============================================================================
// Public surface: progress + result
// =============================================================================

sealed class CoordinatorProgress {
    data object Idle : CoordinatorProgress()
    data class Parsing(val input: String) : CoordinatorProgress()
    data class Parsed(val parsed: ParsedIntent) : CoordinatorProgress()
    data class Routed(val plan: ExecutionPlan) : CoordinatorProgress()
    data class Step(val index: Int, val plan: ExecutionPlan) : CoordinatorProgress()
    data class Finished(val result: CoordinatorResult) : CoordinatorProgress()
}

sealed class CoordinatorResult {
    data class Completed(
        val stepsExecuted: Int,
        val finalScreen: ScreenType
    ) : CoordinatorResult()

    data class StoppedAtGate(
        val screen: ScreenType,
        val stepsExecuted: Int
    ) : CoordinatorResult()

    data class Failed(
        val reason: String,
        val stepsExecuted: Int
    ) : CoordinatorResult()

    data class MaxStepsReached(
        val stepsExecuted: Int,
        val lastScreen: ScreenType
    ) : CoordinatorResult()
}