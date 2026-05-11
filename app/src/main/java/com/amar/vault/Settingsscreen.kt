package com.amar.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.ChevronGray
import com.amar.vault.ui.theme.WarmBrown
import com.amar.vault.ui.theme.WarmBrownDark
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAgentDebug: () -> Unit = {}, // Default empty lambda for compatibility
    onOpenBrainDebug: () -> Unit = {}  // Default empty lambda for compatibility
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by ScanPreferences.prefsFlow(context).collectAsState(initial = ScanPreferences.Prefs())

    // Stats from DB
    val allItems by VaultDatabase.get(context).vaultDao().getAllItems()
        .collectAsState(initial = emptyList())

    val photoCount = allItems.count { it.itemType !in setOf("pdf", "word", "excel", "epub") }
    val docCount = allItems.count { it.itemType in setOf("pdf", "word", "excel", "epub") }
    val totalCount = allItems.size

    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onBack) {
            Text("← Home", color = WarmBrown, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = CharcoalSoft, letterSpacing = (-0.5).sp)

        // ── Storage Stats ───────────────────────────────────────
        Spacer(Modifier.height(28.dp))
        Text("VAULT STATS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WarmBrown, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().background(CreamLight, RoundedCornerShape(16.dp)).padding(20.dp)) {
            Column {
                StatRow("📸", "Photos indexed", "$photoCount")
                Spacer(Modifier.height(10.dp))
                StatRow("📄", "Document chunks", "$docCount")
                Spacer(Modifier.height(10.dp))
                StatRow("📦", "Total items", "$totalCount")
                Spacer(Modifier.height(10.dp))
                StatRow("✅", "Initial scan", if (prefs.initialScanDone) "Complete" else "Pending")
            }
        }

        // ── Scan Preferences ────────────────────────────────────
        Spacer(Modifier.height(28.dp))
        Text("SCAN PREFERENCES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WarmBrown, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))

        SettingToggle("🌐", "All Photos", prefs.scanAll) { value ->
            scope.launch { ScanPreferences.save(context, prefs.copy(scanAll = value)) }
        }
        if (!prefs.scanAll) {
            Spacer(Modifier.height(8.dp))
            SettingToggle("📸", "Screenshots", prefs.scanScreenshots) { value ->
                scope.launch { ScanPreferences.save(context, prefs.copy(scanScreenshots = value)) }
            }
            Spacer(Modifier.height(8.dp))
            SettingToggle("📷", "Camera", prefs.scanCamera) { value ->
                scope.launch { ScanPreferences.save(context, prefs.copy(scanCamera = value)) }
            }
            Spacer(Modifier.height(8.dp))
            SettingToggle("💬", "WhatsApp Images", prefs.scanWhatsApp) { value ->
                scope.launch { ScanPreferences.save(context, prefs.copy(scanWhatsApp = value)) }
            }
            Spacer(Modifier.height(8.dp))
            SettingToggle("📥", "Downloads", prefs.scanDownloads) { value ->
                scope.launch { ScanPreferences.save(context, prefs.copy(scanDownloads = value)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        SettingToggle("📄", "Documents", prefs.scanDocuments) { value ->
            scope.launch { ScanPreferences.save(context, prefs.copy(scanDocuments = value)) }
        }

        // ── Actions ─────────────────────────────────────────────
        Spacer(Modifier.height(32.dp))
        Text("ACTIONS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WarmBrown, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { BulkScanWorker.enqueue(context) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CharcoalSoft),
        ) {
            Text("🔄 Re-scan Now", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = { showClearDialog = true },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("🗑 Clear Vault", fontSize = 15.sp, color = Color(0xFFCC3333), fontWeight = FontWeight.SemiBold)
        }

        // ── Developer Section ───────────────────────────────────
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(32.dp))
            Text("DEVELOPER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WarmBrown, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onOpenAgentDebug,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Agent execution debug", fontSize = 14.sp)
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = onOpenBrainDebug,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Brain debug (LiteRT-LM)", fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(40.dp))

        // App info
        Text("Amar Vault", fontSize = 13.sp, color = WarmBrown, fontWeight = FontWeight.Medium)
        Text("On-device AI • Nothing leaves your phone", fontSize = 12.sp, color = WarmBrown)
        Text("BGE-M3 · ONNX · HNSW · BM25", fontSize = 11.sp, color = ChevronGray)

        Spacer(Modifier.height(60.dp))
    }

    // Clear confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Vault?", fontWeight = FontWeight.Bold) },
            text = { Text("This will delete all indexed data ($totalCount items). Your original photos and documents are NOT affected.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { VaultDatabase.get(context).vaultDao().deleteAll() }
                        runCatching { ScanPreferences.save(context, prefs.copy(initialScanDone = false)) }
                        showClearDialog = false
                    }
                }) { Text("Clear", color = Color(0xFFCC3333), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StatRow(emoji: String, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 14.sp, color = WarmBrownDark, fontWeight = FontWeight.Medium)
        }
        Text(value, fontSize = 14.sp, color = CharcoalSoft, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingToggle(emoji: String, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.fillMaxWidth().background(CreamLight, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = CharcoalSoft, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = CharcoalSoft))
        }
    }
}