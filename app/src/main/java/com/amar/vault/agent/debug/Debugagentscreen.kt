package com.amar.vault.agent.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Debug screen for exercising the agent pipeline end-to-end.
 *
 * Layout (top to bottom):
 *   1. Status chip (fixed)
 *   2. Scrollable controls — all preset + inspect buttons (vertical scroll)
 *   3. Snapshot info line (fixed)
 *   4. Trace pane — always visible, scrollable internally (fixed minimum height)
 *
 * Trace pane gets weight=1 so it expands to fill remaining space and is
 * never pushed off-screen by adding more buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugAgentScreen(
    viewModel: AgentDebugViewModel = hiltViewModel()
) {
    val trace by viewModel.lastRunTrace.collectAsState()
    val snapshot by viewModel.lastSnapshot.collectAsState()
    val running by viewModel.isRunning.collectAsState()

    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Debug", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            Spacer(Modifier.height(4.dp))

            ServiceStatusChip(available = viewModel.serviceAvailable)

            // Controls section — scrollable, takes ~half the screen
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                Text(
                    "Preset envelopes",
                    style = MaterialTheme.typography.titleSmall
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                    Button(
                        onClick = viewModel::presetOpenWhatsApp,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Open WhatsApp")
                    }

                    Button(
                        onClick = viewModel::presetOpenSettings,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Open Settings")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                    Button(
                        onClick = viewModel::presetHome,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Home")
                    }

                    Button(
                        onClick = viewModel::presetReadScreen,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Read Screen")
                    }

                    Button(
                        onClick = viewModel::presetScrollDown,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Scroll ↓")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                    OutlinedButton(
                        onClick = viewModel::presetWait1s,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Wait 1s")
                    }

                    OutlinedButton(
                        onClick = viewModel::presetSendMessageWhatsApp,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Send msg (WA)")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                    OutlinedButton(
                        onClick = viewModel::presetSendSms,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Send SMS")
                    }

                    OutlinedButton(
                        onClick = viewModel::presetMakeCall,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Make call")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                    OutlinedButton(
                        onClick = viewModel::presetFailingClick,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Failing click")
                    }

                    OutlinedButton(
                        onClick = viewModel::presetMalformedJson,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Malformed JSON")
                    }
                }

                Text(
                    "Inspectors",
                    style = MaterialTheme.typography.titleSmall
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                    OutlinedButton(
                        onClick = viewModel::inspectSettings,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Settings")
                    }

                    OutlinedButton(
                        onClick = viewModel::inspectWhatsApp,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("WhatsApp")
                    }

                    OutlinedButton(
                        onClick = viewModel::inspectChrome,
                        enabled = !running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Chrome")
                    }
                }

                Button(
                    onClick = viewModel::presetTypeTextInSettings,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Type 'wifi' in Settings (E2E test)")
                }

                Button(
                    onClick = viewModel::presetClickDisplayInSettings,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Click 'Display' in Settings (E2E test)")
                }

                OutlinedButton(
                    onClick = viewModel::captureSnapshotNow,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Capture snapshot now")
                }
            }

            // Snapshot summary — fixed, between controls and trace
            Text(
                "Snapshot: ${snapshot?.packageId ?: "(none)"} • " +
                        "${snapshot?.size ?: 0} elements" +
                        if (snapshot?.truncated == true) " (truncated)" else "",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                "Run trace",
                style = MaterialTheme.typography.titleSmall
            )

            // Trace pane — fixed height so it's always visible
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF111111),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {

                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {

                    items(trace) { line ->

                        Text(
                            text = line,
                            color = traceColor(line),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    if (trace.isEmpty()) {

                        item {

                            Text(
                                "(no runs yet — tap a preset above)",
                                color = Color(0xFF888888),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceStatusChip(available: Boolean) {

    val bg =
        if (available) Color(0xFF2E7D32)
        else Color(0xFFB71C1C)

    val label =
        if (available)
            "Accessibility service: CONNECTED"
        else
            "Accessibility service: NOT BOUND — enable in system Settings"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {

        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun traceColor(line: String): Color = when {

    line.startsWith("✓") ->
        Color(0xFF7CE38B)

    line.startsWith("✗") ->
        Color(0xFFFF7B72)

    line.startsWith("∅") ->
        Color(0xFFFFB454)

    line.startsWith("[submit]") ->
        Color(0xFF58A6FF)

    line.startsWith("[inspect]") ->
        Color(0xFFFFD27D)

    line.startsWith("[exception]") ->
        Color(0xFFFF7B72)

    line.startsWith("  Retrying") ->
        Color(0xFFFFB454)

    else ->
        Color(0xFFD1D5DA)
}