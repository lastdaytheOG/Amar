package com.amar.vault.agent.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amar.vault.agent.control.ControlLayer
import com.amar.vault.agent.control.TaskState
import com.amar.vault.agent.perception.PerceptionService
import com.amar.vault.agent.perception.UiSnapshot
import com.amar.vault.agent.perception.SnapshotCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug-only ViewModel for driving the Control Layer with hardcoded envelopes
 * and inspecting accessibility snapshots of arbitrary apps.
 */
@HiltViewModel
class AgentDebugViewModel @Inject constructor(
    private val controlLayer: ControlLayer,
    private val snapshotCache: SnapshotCache
) : ViewModel() {

    private val _lastRunTrace = MutableStateFlow<List<String>>(emptyList())
    val lastRunTrace: StateFlow<List<String>> = _lastRunTrace.asStateFlow()

    private val _lastSnapshot = MutableStateFlow<UiSnapshot?>(null)
    val lastSnapshot: StateFlow<UiSnapshot?> = _lastSnapshot.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    val serviceAvailable: Boolean
        get() = PerceptionService.get() != null

    /**
     * Run a raw JSON envelope through the full pipeline and collect the state trace.
     */
    fun runEnvelope(rawJson: String) {
        viewModelScope.launch {

            android.util.Log.w(
                "AgentDebugVM",
                "runEnvelope START, running=${_isRunning.value}"
            )

            if (_isRunning.value) {
                _lastRunTrace.value =
                    listOf("[already running — abort previous first]")
                return@launch
            }

            _isRunning.value = true

            val trace = mutableListOf<String>()

            trace += "[submit] $rawJson"

            _lastRunTrace.value = trace.toList()

            try {

                val ctx = controlLayer.submitRaw(rawJson)

                trace += "[accepted] task=${ctx.taskId}"

                _lastRunTrace.value = trace.toList()

                ctx.state
                    .takeWhile { !it.isTerminal }
                    .collect { state ->

                        trace += formatState(state)

                        _lastRunTrace.value = trace.toList()

                        _lastSnapshot.value = snapshotCache.currentAnyAge()
                    }

                val finalState = ctx.current

                trace += formatState(finalState)

                _lastRunTrace.value = trace.toList()

            } catch (t: Throwable) {

                trace += "[exception] ${t.javaClass.simpleName}: ${t.message}"

                _lastRunTrace.value = trace.toList()

            } finally {
                _isRunning.value = false
            }
        }
    }

    /** Force-walk the current UI tree and expose the result. */
    fun captureSnapshotNow() {

        viewModelScope.launch {

            val svc = PerceptionService.get()

            if (svc == null) {

                _lastRunTrace.value =
                    listOf("[snapshot] PerceptionService not bound — enable Accessibility")

                return@launch
            }

            val snap = svc.forceSnapshot()

            _lastSnapshot.value = snap

            _lastRunTrace.value = listOf(
                "[snapshot] package=${snap.packageId} window=${snap.windowClass}",
                "[snapshot] elements=${snap.size} truncated=${snap.truncated}",
                "[snapshot] reason=${snap.captureReason}"
            )
        }
    }

    // -------------------------------------------------------------------------
    // App inspector — opens an app, waits, dumps the accessibility tree
    // -------------------------------------------------------------------------

    /**
     * Generic inspector. Opens [appName], waits for the window to settle,
     * then dumps EVERY node (not just clickables) with its selectors so we
     * can see what's actually addressable in that app.
     *
     * Every trace line is also Log.w()-ed so it shows up in logcat under
     * the "InspectVM" tag — useful when the in-app trace pane is too small
     * to see the full output.
     */
    fun inspect(appName: String) {

        viewModelScope.launch {

            if (_isRunning.value) {
                _lastRunTrace.value = listOf("[already running]")
                return@launch
            }

            _isRunning.value = true

            val trace = mutableListOf<String>()

            fun addLine(line: String) {
                trace += line
                _lastRunTrace.value = trace.toList()
                android.util.Log.w("InspectVM", line)
            }

            addLine("[inspect] opening $appName...")

            try {

                val openJson = """
                    {
                      "action": "open_app",
                      "params": { "app": "$appName" },
                      "constraints": { "timeout_ms": 5000, "retries": 0 }
                    }
                """.trimIndent()

                val openCtx = controlLayer.submitRaw(openJson)

                openCtx.state
                    .takeWhile { !it.isTerminal }
                    .collect { }

                addLine("[inspect] launched, waiting 2.5s for window to settle...")

                delay(2500)

                val svc = PerceptionService.get()

                if (svc == null) {
                    addLine("[inspect] PerceptionService not bound")
                    return@launch
                }

                val snap = svc.forceSnapshot()

                _lastSnapshot.value = snap

                addLine("[inspect] foreground=${snap.packageId} window=${snap.windowClass}")
                addLine("[inspect] elements=${snap.size} truncated=${snap.truncated}")

                if (snap.size == 0) {
                    addLine("[inspect] tree is empty — service may not have permission to read this app")
                    return@launch
                }

                addLine("[inspect] -- elements (clickable | editable | text) --")

                var dumped = 0

                for (el in snap.elements) {

                    if (dumped >= 50) {
                        addLine("  ... (${snap.size - dumped} more, truncated for readability)")
                        break
                    }

                    val hasContent =
                        !el.text.isNullOrBlank() ||
                                !el.contentDesc.isNullOrBlank() ||
                                !el.resourceId.isNullOrBlank() ||
                                el.clickable ||
                                el.editable

                    if (!hasContent) continue

                    val parts = buildList {

                        add(el.type.name)

                        if (el.clickable) add("CLK")

                        if (el.editable) add("EDIT")

                        el.text?.takeIf { it.isNotBlank() }?.let {
                            add("t='${it.take(40)}'")
                        }

                        el.contentDesc?.takeIf { it.isNotBlank() }?.let {
                            add("d='${it.take(40)}'")
                        }

                        el.resourceId?.takeIf { it.isNotBlank() }?.let {
                            add("id='${it.substringAfter('/').take(30)}'")
                        }
                    }

                    addLine("  " + parts.joinToString(" "))

                    dumped++
                }

            } catch (t: Throwable) {

                addLine("[exception] ${t.javaClass.simpleName}: ${t.message}")

            } finally {
                _isRunning.value = false
            }
        }
    }

    fun inspectSettings() = inspect("Settings")

    fun inspectWhatsApp() = inspect("WhatsApp")

    fun inspectChrome() = inspect("Chrome")

    // -------------------------------------------------------------------------
    // State formatting
    // -------------------------------------------------------------------------

    private fun formatState(state: TaskState): String = when (state) {

        is TaskState.Pending ->
            "  Pending"

        is TaskState.Running ->
            "  Running attempt=${state.attemptNumber}"

        is TaskState.Verifying ->
            "  Verifying (exec took ${state.executionDurationMs}ms)"

        is TaskState.Retrying ->
            "  Retrying next=${state.nextAttemptNumber} " +
                    "after=${state.lastFailure::class.simpleName} " +
                    "backoff=${state.backoffUntil - System.currentTimeMillis()}ms"

        is TaskState.Succeeded ->
            "✓ Succeeded attempts=${state.attemptsUsed} " +
                    "duration=${state.totalDurationMs}ms " +
                    "result=${state.resultData}"

        is TaskState.Failed ->
            "✗ Failed attempts=${state.attemptsUsed} " +
                    "duration=${state.totalDurationMs}ms " +
                    "reason=${state.reason::class.simpleName}(${state.reason})"

        is TaskState.Cancelled ->
            "∅ Cancelled reason=${state.reason::class.simpleName}"
    }

    // -------------------------------------------------------------------------
    // Preset envelopes
    // -------------------------------------------------------------------------

    fun presetOpenWhatsApp() = runEnvelope("""
        {
          "action": "open_app",
          "params": { "app": "WhatsApp" },
          "constraints": { "timeout_ms": 5000, "retries": 1 },
          "verify": { "type": "app_opened", "package": "com.whatsapp" }
        }
    """.trimIndent())

    fun presetOpenSettings() = runEnvelope("""
        {
          "action": "open_app",
          "params": { "app": "Settings" },
          "constraints": { "timeout_ms": 5000, "retries": 1 },
          "verify": { "type": "app_opened", "package": "com.android.settings" }
        }
    """.trimIndent())

    fun presetHome() = runEnvelope("""
        {
          "action": "home",
          "params": {},
          "constraints": { "timeout_ms": 2000, "retries": 0 }
        }
    """.trimIndent())

    fun presetWait1s() = runEnvelope("""
        {
          "action": "wait",
          "params": { "ms": 1000 },
          "constraints": { "timeout_ms": 2000 }
        }
    """.trimIndent())

    fun presetReadScreen() = runEnvelope("""
        {
          "action": "read_screen",
          "params": {},
          "constraints": { "timeout_ms": 2000 }
        }
    """.trimIndent())

    fun presetScrollDown() = runEnvelope("""
        {
          "action": "scroll",
          "params": { "direction": "down" },
          "constraints": { "timeout_ms": 2000, "retries": 0 }
        }
    """.trimIndent())

    fun presetSendMessageWhatsApp() = runEnvelope("""
        {
          "action": "send_message",
          "params": {
            "app": "whatsapp",
            "to": "919999999999",
            "content": "Test message from Amar Vault agent"
          },
          "constraints": { "timeout_ms": 5000, "retries": 1 }
        }
    """.trimIndent())

    fun presetSendSms() = runEnvelope("""
        {
          "action": "send_sms",
          "params": {
            "to": "+919999999999",
            "body": "Agent self-test"
          },
          "constraints": { "timeout_ms": 15000, "retries": 1 }
        }
    """.trimIndent())

    fun presetMakeCall() = runEnvelope("""
        {
          "action": "make_call",
          "params": { "to": "+919999999999" },
          "constraints": { "timeout_ms": 3000, "retries": 0 }
        }
    """.trimIndent())

    fun presetClickDisplayInSettings() {

        viewModelScope.launch {

            if (_isRunning.value) {
                _lastRunTrace.value = listOf("[already running]")
                return@launch
            }

            _isRunning.value = true

            val trace = mutableListOf<String>()

            fun addLine(line: String) {
                trace += line
                _lastRunTrace.value = trace.toList()
                android.util.Log.w("ClickTest", line)
            }

            try {

                // Step 1: open Settings
                addLine("[step 1] opening Settings")

                val openCtx = controlLayer.submitRaw("""
                    {
                      "action": "open_app",
                      "params": { "app": "Settings" },
                      "constraints": { "timeout_ms": 5000, "retries": 0 }
                    }
                """.trimIndent())

                openCtx.state
                    .takeWhile { !it.isTerminal }
                    .collect { }

                addLine("[step 1] open result: ${openCtx.current::class.simpleName}")

                delay(2000)

                // Step 2: click "Display" by text
                addLine("[step 2] clicking 'Display'")

                val clickCtx = controlLayer.submitRaw("""
                    {
                      "action": "click",
                      "params": {
                        "target": "Display",
                        "strategy": "auto"
                      },
                      "constraints": {
                        "timeout_ms": 4000,
                        "retries": 1
                      }
                    }
                """.trimIndent())

                clickCtx.state
                    .takeWhile { !it.isTerminal }
                    .collect { state ->
                        addLine("  ${formatState(state)}")
                    }

                addLine("[step 2] click result: ${clickCtx.current::class.simpleName}")

                delay(1500)

                // Step 3: snapshot to verify Display screen opened
                val svc = PerceptionService.get()

                if (svc != null) {

                    val snap = svc.forceSnapshot()

                    addLine("[step 3] after click foreground=${snap.packageId}")
                    addLine("[step 3] window=${snap.windowClass} elements=${snap.size}")

                    val texts = snap.elements
                        .mapNotNull { it.text }
                        .filter { it.isNotBlank() }
                        .take(5)

                    addLine("[step 3] visible text: ${texts.joinToString(" | ")}")
                }

            } catch (t: Throwable) {

                addLine("[exception] ${t.message}")

            } finally {
                _isRunning.value = false
            }
        }
    }

    fun presetTypeTextInSettings() {

        viewModelScope.launch {

            if (_isRunning.value) {
                _lastRunTrace.value = listOf("[already running]")
                return@launch
            }

            _isRunning.value = true

            val trace = mutableListOf<String>()

            fun addLine(line: String) {
                trace += line
                _lastRunTrace.value = trace.toList()
                android.util.Log.w("TypeTextTest", line)
            }

            try {

                // Step 1: open Settings
                addLine("[step 1] opening Settings")

                val openCtx = controlLayer.submitRaw("""
                    {
                      "action": "open_app",
                      "params": { "app": "Settings" },
                      "constraints": { "timeout_ms": 5000, "retries": 0 }
                    }
                """.trimIndent())

                openCtx.state
                    .takeWhile { !it.isTerminal }
                    .collect { }

                addLine("[step 1] result: ${openCtx.current::class.simpleName}")

                delay(1800)

                // Step 2: click the search bar
                addLine("[step 2] clicking search bar")

                val clickCtx = controlLayer.submitRaw("""
                    {
                      "action": "click",
                      "params": {
                        "target": "Search settings",
                        "strategy": "auto"
                      },
                      "constraints": {
                        "timeout_ms": 4000,
                        "retries": 1
                      }
                    }
                """.trimIndent())

                clickCtx.state
                    .takeWhile { !it.isTerminal }
                    .collect { }

                addLine("[step 2] result: ${clickCtx.current::class.simpleName}")

                delay(1500)

                // Snapshot to see what input field appeared
                val svc = PerceptionService.get()

                val preSnap = svc?.forceSnapshot()

                if (preSnap != null) {

                    addLine("[step 2.5] after click foreground=${preSnap.packageId}")

                    val editables = preSnap.elements.filter { it.editable }

                    addLine("[step 2.5] editable fields=${editables.size}")

                    for (el in editables.take(3)) {

                        val parts = listOfNotNull(
                            el.type.name,
                            el.text?.let { "t='$it'" },
                            el.contentDesc?.let { "d='$it'" },
                            el.resourceId?.let { "id='${it.substringAfter('/')}'" },
                            el.hint?.let { "hint='$it'" }
                        )

                        addLine("  " + parts.joinToString(" "))
                    }
                }

                // Step 3: type "wifi" into the search field
                addLine("[step 3] typing 'wifi'")

                val typeCtx = controlLayer.submitRaw("""
                    {
                      "action": "type_text",
                      "params": {
                        "target": "search_src_text",
                        "text": "wifi",
                        "strategy": "resource_id",
                        "submit": false
                      },
                      "constraints": {
                        "timeout_ms": 4000,
                        "retries": 1
                      }
                    }
                """.trimIndent())

                typeCtx.state
                    .takeWhile { !it.isTerminal }
                    .collect { state ->
                        addLine("  ${formatState(state)}")
                    }

                addLine("[step 3] result: ${typeCtx.current::class.simpleName}")

                delay(1500)

                // Step 4: verify by snapshot
                val finalSnap = svc?.forceSnapshot()

                if (finalSnap != null) {

                    addLine("[step 4] foreground=${finalSnap.packageId} elements=${finalSnap.size}")

                    val texts = finalSnap.elements
                        .mapNotNull { it.text }
                        .filter { it.isNotBlank() }
                        .take(8)

                    addLine("[step 4] visible: ${texts.joinToString(" | ")}")
                }

            } catch (t: Throwable) {

                addLine("[exception] ${t.message}")

            } finally {
                _isRunning.value = false
            }
        }
    }

    fun presetMalformedJson() =
        runEnvelope("""{"action":"nope","params":{}}""")

    fun presetFailingClick() = runEnvelope("""
        {
          "action": "click",
          "params": {
            "target": "xqzzy_nonexistent_button_xqzzy",
            "strategy": "auto"
          },
          "constraints": {
            "timeout_ms": 2000,
            "retries": 2,
            "retry_backoff_ms": 300
          }
        }
    """.trimIndent())
}