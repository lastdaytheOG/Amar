package com.amar.vault.agent.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amar.vault.agent.brain.BrainFailure
import com.amar.vault.agent.brain.BrainState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugBrainScreen(
    viewModel: BrainDebugViewModel = hiltViewModel()
) {
    val brainState by viewModel.brainState.collectAsState()
    val modelOutput by viewModel.modelOutput.collectAsState()
    val executionTrace by viewModel.executionTrace.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val busy by viewModel.busy.collectAsState()

    var userInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Brain Debug", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            BrainStateCard(
                state = brainState,
                modelName = viewModel.selectedModelName,
                onWarmup = viewModel::warmupBrain
            )

            metrics?.let { m ->
                Text(
                    "TTFT: ${m.ttftMs}ms • Total: ${m.totalDurationMs}ms • " +
                            "Tokens: ${m.tokensGenerated} • Speed: ${
                                if (m.totalDurationMs > 0) (m.tokensGenerated * 1000L / m.totalDurationMs) else 0
                            } tok/s",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            OutlinedTextField(
                value = userInput,
                onValueChange = { userInput = it },
                label = { Text("Your command") },
                placeholder = { Text("e.g., open whatsapp / search dog on google") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (userInput.isNotBlank()) viewModel.submit(userInput)
                })
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.submit(userInput) },
                    enabled = !busy && userInput.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Submit") }

                OutlinedButton(
                    onClick = viewModel::cancel,
                    enabled = busy,
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
            }

            Button(
                onClick = { viewModel.submitGoal(userInput) },
                enabled = !busy && userInput.isNotBlank() && brainState is BrainState.Ready,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Run as Goal (Coordinator)") }

            Text("Model output", style = MaterialTheme.typography.labelSmall)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0F1419),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            ) {
                LazyColumn(contentPadding = PaddingValues(12.dp)) {
                    item {
                        Text(
                            text = modelOutput.ifEmpty { "(model hasn't emitted anything yet)" },
                            color = if (modelOutput.isEmpty()) Color(0xFF6A737D) else Color(0xFF7CE38B),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Text("Execution trace", style = MaterialTheme.typography.labelSmall)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF111111),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(executionTrace) { line ->
                        Text(
                            text = line,
                            color = traceColor(line),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                    if (executionTrace.isEmpty()) {
                        item {
                            Text(
                                "(no runs yet)",
                                color = Color(0xFF6A737D),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrainStateCard(
    state: BrainState,
    modelName: String,
    onWarmup: () -> Unit
) {
    val (bg, label) = when (state) {
        is BrainState.Ready -> Color(0xFF2E7D32) to "READY • $modelName"
        is BrainState.Uninitialized -> Color(0xFF455A64) to "UNINITIALIZED • tap Warmup"
        is BrainState.Downloading -> Color(0xFF1976D2) to
                "DOWNLOADING ${(state.progress * 100).toInt()}% " +
                "(${state.bytesReceived / 1_000_000}MB / ${state.bytesTotal / 1_000_000}MB)"
        is BrainState.Loading -> Color(0xFF00838F) to "LOADING model into engine..."
        is BrainState.Warming -> Color(0xFF00897B) to "WARMING (priming KV cache)..."
        is BrainState.Failed -> Color(0xFFB71C1C) to "FAILED • ${failureDetail(state.reason)}"
        is BrainState.Shutdown -> Color(0xFF6D4C41) to "SHUTDOWN"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Medium)

        if (state is BrainState.Downloading) {
            LinearProgressIndicator(
                progress = state.progress,
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f)
            )
        }

        if (state is BrainState.Uninitialized || state is BrainState.Failed) {
            Button(onClick = onWarmup) {
                Text(if (state is BrainState.Failed) "Retry warmup" else "Warm up the Brain")
            }
        }
    }
}

private fun failureDetail(reason: BrainFailure): String = when (reason) {
    is BrainFailure.ModelDownloadFailed -> "download: ${reason.detail.take(60)}"
    is BrainFailure.ModelCorrupted -> "SHA mismatch"
    is BrainFailure.ModelLoadFailed -> "load: ${reason.detail.take(60)}"
    is BrainFailure.BackendUnavailable -> "no backend worked: ${reason.tried.joinToString()}"
    is BrainFailure.OutOfMemory -> "OOM"
    is BrainFailure.Unknown -> reason.detail.take(80)
}

private fun traceColor(line: String): Color = when {
    line.startsWith("✓") -> Color(0xFF7CE38B)
    line.startsWith("✗") -> Color(0xFFFF7B72)
    line.startsWith("∅") -> Color(0xFFFFB454)
    line.startsWith("⏸") -> Color(0xFFFFB454)
    line.startsWith("[submit]") -> Color(0xFF58A6FF)
    line.startsWith("[goal]") -> Color(0xFFBC8CFF)
    line.startsWith("[router]") -> Color(0xFF7CE38B)
    line.startsWith("[coord]") -> Color(0xFFFFD27D)
    line.startsWith("[brain]") -> Color(0xFFC9D1D9)
    line.startsWith("[brain failed]") -> Color(0xFFFF7B72)
    line.startsWith("[brain not ready]") -> Color(0xFFFFB454)
    line.startsWith("[dispatch]") -> Color(0xFFBC8CFF)
    line.startsWith("[exception]") -> Color(0xFFFF7B72)
    line.startsWith("  Retrying") -> Color(0xFFFFB454)
    else -> Color(0xFFD1D5DA)
}