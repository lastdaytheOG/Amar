package com.amar.vault

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amar.vault.ui.theme.AgenticBlue
import com.amar.vault.ui.theme.AgenticPink
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.CreamDark
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.WarmBrown
import com.amar.vault.ui.theme.WarmBrownDark
import com.amar.vault.ui.theme.ChevronGray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val sources: List<VaultItem> = emptyList(),
)

@Composable
fun AgenticScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    val searchResults by viewModel.results.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearchLoading.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var isGenerating by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Tracks active generation job to prevent C++ thread collisions
    var currentLlmJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var modelStatus by remember { mutableStateOf("Checking model…") }
    var modelReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                if (!NativeLlamaEngine.isLoaded()) {
                    val searchDirs = listOfNotNull(
                        java.io.File(context.filesDir, "models"),
                        context.getExternalFilesDir(null)?.let { java.io.File(it, "models") },
                        java.io.File("/sdcard/Android/data/${context.packageName}/files/models"),
                    )

                    val modelFile = searchDirs
                        .flatMap { dir ->
                            dir.listFiles()?.filter { it.name.endsWith(".gguf") }?.toList()
                                ?: emptyList()
                        }
                        .firstOrNull()

                    if (modelFile != null) {
                        val loaded = NativeLlamaEngine.loadModel(modelFile.absolutePath)
                        withContext(Dispatchers.Main) {
                            modelReady = loaded
                            modelStatus = if (loaded) "✅ ${modelFile.name}" else "⚠️ Model load failed"
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            modelStatus = "⚡ Smart mode (no LLM)"
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        modelReady = true
                        modelStatus = "✅ Model ready"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    modelStatus = "⚡ Smart mode"
                }
            }
        }
    }

    LaunchedEffect(messages.size, isGenerating) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val suggestions = listOf(
        "💰 How much did I spend?",
        "🔑 Show my last OTP",
        "📞 Find phone numbers",
        "📄 Search documents",
        "📸 Recent screenshots",
        "💳 UPI payments",
    )

    fun sendQuery(query: String) {
        if (query.isBlank()) return
        val q = query.trim()
        inputText = ""
        keyboard?.hide()

        // ── DEADLOCK FIX: Halt previous generation before starting new one ──
        currentLlmJob?.cancel()
        NativeLlamaEngine.cancelGeneration()

        messages.add(ChatMessage(q, isUser = true))
        isGenerating = true

        currentLlmJob = scope.launch(Dispatchers.IO) {
            val tier = QueryRouter.classify(q)
            android.util.Log.i("Agentic", "Query '$q' → $tier")

            viewModel.updateQuery(q)
            kotlinx.coroutines.delay(500)
            var waitMs = 0
            while (viewModel.isSearchLoading.value && waitMs < 5000) {
                kotlinx.coroutines.delay(150)
                waitMs += 150
            }

            val results = viewModel.results.value
            val sources = results.take(3)

            withContext(Dispatchers.Main) {
                when (tier) {
                    QueryRouter.QueryTier.TIER0_REGEX -> {
                        val answer = buildFallbackAnswer(q, results)
                        messages.add(ChatMessage("⚡ $answer", isUser = false, sources = sources))
                        isGenerating = false
                    }

                    QueryRouter.QueryTier.TIER1_SEARCH -> {
                        val answer = buildFallbackAnswer(q, results)
                        if (results.isEmpty() && modelReady) {
                            // No search results — let LLM handle it directly as a chat
                            val prompt = "<start_of_turn>user\n$q<end_of_turn>\n<start_of_turn>model\n"
                            val llmAnswer = NativeLlamaEngine.generateBlocking(prompt)
                            if (llmAnswer.isNotBlank()) {
                                messages.add(ChatMessage("🧠 ${llmAnswer.trim()}", isUser = false))
                            } else {
                                messages.add(ChatMessage("🔍 $answer", isUser = false))
                            }
                        } else {
                            messages.add(ChatMessage("🔍 $answer", isUser = false, sources = sources))
                        }
                        isGenerating = false
                    }

                    QueryRouter.QueryTier.TIER2_LLM -> {
                        val fallback = buildFallbackAnswer(q, results)
                        val msgIndex = messages.size
                        messages.add(ChatMessage(fallback, isUser = false, sources = sources))
                        isGenerating = false

                        if (modelReady) { // Removed sources.isNotEmpty() requirement
                            try {
                                val prompt = if (sources.isNotEmpty()) {
                                    val topSource = sources.first().ocrText
                                        .substringBefore("\n[").trim().take(200)
                                    "<start_of_turn>user\n" +
                                            "Answer briefly and accurately from the context below.\n\n" +
                                            "Context: $topSource\n\n" +
                                            "Question: $q<end_of_turn>\n" +
                                            "<start_of_turn>model\n"
                                } else {
                                    "<start_of_turn>user\n$q<end_of_turn>\n<start_of_turn>model\n"
                                }

                                // Runs safely on IO dispatcher to avoid UI jank
                                val llmAnswer = NativeLlamaEngine.generateBlocking(prompt)

                                if (llmAnswer.isNotBlank() && msgIndex < messages.size) {
                                    messages[msgIndex] = ChatMessage(
                                        "🧠 ${llmAnswer.trim()}",
                                        isUser = false,
                                        sources = sources,
                                    )
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("Agentic", "LLM error: ${e.message}")
                            }
                        }
                    }
                }
                viewModel.updateQuery("")
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Cream).imePadding()
    ) {
        // ── Top bar ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                // Ensure model unloads/stops when leaving screen
                NativeLlamaEngine.cancelGeneration()
                onBack()
            }) { Text("← Back", color = WarmBrown, fontSize = 15.sp) }
            Spacer(Modifier.weight(1f))
            Text("Agentic", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = CharcoalSoft)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(60.dp))
        }

        // ── Chat area ───────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AnimatedOrb()
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Ask me anything about\nyour vault…",
                            fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            color = CharcoalSoft, textAlign = TextAlign.Center, lineHeight = 28.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "I search your screenshots, documents,\nphotos — everything indexed on-device",
                            fontSize = 14.sp, color = WarmBrown,
                            textAlign = TextAlign.Center, lineHeight = 20.sp,
                        )
                        Spacer(Modifier.height(28.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            items(suggestions) { s ->
                                SuggestionChip(s) { sendQuery(s.drop(2).trim()) }
                            }
                        }
                    }
                }
            }

            items(messages) { msg ->
                if (msg.isUser) UserBubble(msg.text) else AgentBubble(msg)
            }

            if (isGenerating) {
                item { ThinkingBubble() }
            }
        }

        // ── Model status bar ────────────────────────────────────
        if (messages.isEmpty()) {
            Text(
                modelStatus,
                fontSize = 11.sp, color = WarmBrown,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                textAlign = TextAlign.Center,
            )
        }

        // ── Input bar ───────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().background(CreamLight)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Type your question…", color = ChevronGray, fontSize = 15.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Cream, unfocusedContainerColor = Cream,
                    focusedBorderColor = CreamDark, unfocusedBorderColor = CreamDark,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendQuery(inputText) }),
            )
            Spacer(Modifier.width(10.dp))
            Surface(
                onClick = { sendQuery(inputText) },
                shape = CircleShape,
                color = if (inputText.isNotBlank()) CharcoalSoft else CreamDark,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("→", fontSize = 20.sp,
                        color = if (inputText.isNotBlank()) Color.White else WarmBrown)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// UI components
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun AnimatedOrb() {
    val inf = rememberInfiniteTransition(label = "orb")
    val scale by inf.animateFloat(0.92f, 1.08f,
        infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Reverse), label = "orbS")

    Box(
        modifier = Modifier.size(80.dp).scale(scale).clip(CircleShape)
            .background(Brush.sweepGradient(listOf(
                AgenticPink, AgenticBlue,
                AgenticPink.copy(alpha = 0.7f),
                AgenticBlue.copy(alpha = 0.8f), AgenticPink
            )))
    )
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                .background(CharcoalSoft)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(0.8f)
        ) {
            Text(text, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun AgentBubble(msg: ChatMessage) {
    Column(modifier = Modifier.fillMaxWidth(0.9f)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp))
                .background(CreamLight)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(msg.text, color = CharcoalSoft, fontSize = 15.sp, lineHeight = 22.sp)
        }

        if (msg.sources.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Sources from your vault:", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = WarmBrown, letterSpacing = 0.5.sp,
                modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(6.dp))
            msg.sources.forEach { source ->
                SourceCard(source)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SourceCard(item: VaultItem) {
    val preview = item.ocrText.substringBefore("\n[").trim().take(80)
    val typeEmoji = when (item.itemType) {
        "pdf" -> "📄"; "word" -> "📝"; "excel" -> "📊"; "epub" -> "📖"
        "screenshot" -> "📸"; else -> "🖼"
    }
    val typeName = when (item.itemType) {
        "pdf" -> "PDF"; "word" -> "DOCX"; "excel" -> "XLSX"; "epub" -> "EPUB"
        "screenshot" -> "Screenshot"; else -> "Photo"
    }

    Surface(shape = RoundedCornerShape(12.dp), color = Cream, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(CreamDark),
                contentAlignment = Alignment.Center) {
                Text(typeEmoji, fontSize = 14.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.sourceFile?.takeIf { it.isNotBlank() } ?: typeName,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = WarmBrownDark,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (preview.isNotBlank()) {
                    Text(preview, fontSize = 11.sp, color = WarmBrown,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    val inf = rememberInfiniteTransition(label = "think")
    val d1 by inf.animateFloat(0.3f, 1f,
        infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "d1")
    val d2 by inf.animateFloat(0.3f, 1f,
        infiniteRepeatable(tween(500, delayMillis = 150), RepeatMode.Reverse), label = "d2")
    val d3 by inf.animateFloat(0.3f, 1f,
        infiniteRepeatable(tween(500, delayMillis = 300), RepeatMode.Reverse), label = "d3")

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp))
            .background(CreamLight)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(d1, d2, d3).forEach { a ->
                Box(Modifier.size(8.dp).clip(CircleShape).background(WarmBrown.copy(alpha = a)))
            }
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp),
        color = CreamLight, modifier = Modifier.height(40.dp)) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = WarmBrownDark, maxLines = 1)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Smart answer builder — extracts structured data from search results
// Used by Tier 0 and Tier 1 routes (no LLM)
// ═══════════════════════════════════════════════════════════════════════

private fun buildFallbackAnswer(query: String, results: List<VaultItem>): String {
    if (results.isEmpty()) {
        return "I couldn't find anything matching \"$query\" in your vault."
    }

    val qLower = query.lowercase()
    val allText = results.map { it.ocrText.substringBefore("\n[").trim() }

    val isMoneyQuery = listOf("expense", "spend", "payment", "upi", "paid", "amount",
        "total", "calculate", "money", "transaction", "rupee", "cost", "bill",
        "recharge", "credited", "debited", "received", "transfer").any { qLower.contains(it) }

    if (isMoneyQuery) return buildPaymentAnswer(allText, results)

    if (qLower.contains("otp") || qLower.contains("code") || qLower.contains("verification")) {
        return buildOtpAnswer(allText)
    }

    val isContactQuery = listOf("contact", "number", "call", "missed", "dial", "phone")
        .any { qLower.contains(it) }
    if (isContactQuery) return buildContactAnswer(allText, results)

    return buildGenericAnswer(qLower, allText, results)
}

private fun buildPaymentAnswer(texts: List<String>, results: List<VaultItem>): String {
    data class Transaction(val amount: Int, val name: String, val date: String, val type: String)

    val transactions = mutableListOf<Transaction>()

    texts.forEach { text ->
        Regex("₹\\s?([\\d,]+)").findAll(text).forEach { match ->
            val amount = match.groupValues[1].replace(",", "").toIntOrNull() ?: return@forEach
            val pos = match.range.first
            val contextStart = (pos - 200).coerceAtLeast(0)
            val context = text.substring(contextStart, (pos + 50).coerceAtMost(text.length))
            val contextLower = context.lowercase()

            val type = when {
                contextLower.contains("received") || contextLower.contains("credited") -> "received"
                contextLower.contains("paid") || contextLower.contains("debited") ||
                        contextLower.contains("payment") -> "paid"
                contextLower.contains("recharge") -> "recharge"
                else -> "transaction"
            }

            val name = Regex("(?:from|to)\\s+([A-Z][A-Za-z ]{2,20})").find(context)
                ?.groupValues?.getOrNull(1)?.trim() ?: ""

            val date = Regex("\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)(?:\\s+\\d{4})?")
                .find(context)?.value ?: ""

            transactions.add(Transaction(amount, name, date, type))
        }
    }

    if (transactions.isEmpty()) {
        return "Found ${results.size} results but couldn't extract amounts."
    }

    val sb = StringBuilder()
    val totalSpent = transactions.filter { it.type != "received" }.sumOf { it.amount }
    val totalReceived = transactions.filter { it.type == "received" }.sumOf { it.amount }

    sb.append("💰 Found ${transactions.size} transaction${if (transactions.size > 1) "s" else ""}:\n\n")

    transactions.distinctBy { "${it.amount}_${it.name}" }.take(6).forEach { t ->
        val icon = when (t.type) {
            "received" -> "⬇️"; "paid" -> "⬆️"
            "recharge" -> "📱"; else -> "💳"
        }
        val nameStr = if (t.name.isNotBlank()) " — ${t.name}" else ""
        val dateStr = if (t.date.isNotBlank()) " (${t.date})" else ""
        sb.append("$icon ₹${t.amount}$nameStr$dateStr\n")
    }

    if (totalSpent > 0 || totalReceived > 0) {
        sb.append("\n")
        if (totalSpent > 0) sb.append("Spent: ₹$totalSpent\n")
        if (totalReceived > 0) sb.append("Received: ₹$totalReceived\n")
        if (totalSpent > 0 && totalReceived > 0) {
            val net = totalReceived - totalSpent
            sb.append("Net: ${if (net >= 0) "+" else ""}₹$net")
        }
    }

    return sb.toString().trim()
}

private fun buildOtpAnswer(texts: List<String>): String {
    texts.forEach { text ->
        val tLower = text.lowercase()
        if (tLower.contains("otp") || tLower.contains("code") || tLower.contains("verif")) {
            val candidates = Regex("\\b(\\d{4,8})\\b").findAll(text)
                .map { it.groupValues[1] }
                .filter { it.length in 4..8 && !it.startsWith("20") && !it.startsWith("19") }
                .toList()

            if (candidates.isNotEmpty()) {
                return "🔑 OTP: ${candidates.last()}\n\nFrom: ${text.take(80)}…"
            }
        }
    }
    return "Couldn't extract a specific OTP."
}

private fun buildContactAnswer(texts: List<String>, results: List<VaultItem>): String {
    val phones = mutableSetOf<String>()
    val contactInfo = mutableListOf<String>()

    texts.forEach { text ->
        Regex("(?:\\+91[\\s-]?)?[6-9][\\d\\s-]{8,12}").findAll(text).forEach { m ->
            val digits = m.value.replace(Regex("[^0-9]"), "")
            when {
                digits.length == 10 -> phones.add("+91 ${digits.take(5)} ${digits.drop(5)}")
                digits.length == 12 && digits.startsWith("91") ->
                    phones.add("+${digits.take(2)} ${digits.substring(2, 7)} ${digits.drop(7)}")
            }
        }

        Regex("([A-Z][a-z]{2,15}(?:\\s[A-Z][a-z]{2,15})?)\\s*(?:Mobile|Call|Phone|\\+91|[6-9]\\d{4})")
            .findAll(text).forEach {
                contactInfo.add(it.groupValues[1])
            }

        Regex("(Missed call|Outgoing call|Incoming call|Video call)[^\\d]*(\\d{1,2}:\\d{2}\\s*(?:am|pm)?)",
            RegexOption.IGNORE_CASE).findAll(text).forEach {
            contactInfo.add("${it.groupValues[1]} — ${it.groupValues[2]}")
        }
    }

    if (phones.isEmpty() && contactInfo.isEmpty()) {
        return buildGenericAnswer("contact", texts, results)
    }

    val sb = StringBuilder("Found ${results.size} result${if (results.size > 1) "s" else ""}:\n\n")
    contactInfo.distinct().take(3).forEach { sb.append("👤 $it\n") }
    phones.distinct().take(5).forEach { sb.append("📱 $it\n") }

    return sb.toString().trim()
}

private fun buildGenericAnswer(query: String, texts: List<String>, results: List<VaultItem>): String {
    val sb = StringBuilder("Found ${results.size} result${if (results.size > 1) "s" else ""}:\n\n")

    val queryWords = query.split(Regex("\\s+")).filter { it.length >= 2 }

    results.take(3).forEach { item ->
        val text = item.ocrText.substringBefore("\n[").trim()
        val typeIcon = when (item.itemType) {
            "pdf" -> "📄"; "word" -> "📝"; "excel" -> "📊"
            "screenshot" -> "📸"; else -> "🖼"
        }

        val bestLine = text.lines()
            .filter { it.isNotBlank() && it.length > 3 }
            .maxByOrNull { line -> queryWords.count { line.lowercase().contains(it) } }
            ?: text.lines().firstOrNull { it.isNotBlank() }

        sb.append("$typeIcon ${bestLine?.trim()?.take(80) ?: text.take(80)}\n")
    }

    return sb.toString().trim()
}