package com.amar.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.agent.debug.DebugAgentScreen
import com.amar.vault.agent.debug.DebugBrainScreen
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.WarmBrown

/**
 * App navigation. Uses an enum-backed state machine (not Jetpack NavHost) to match
 * the existing Amar Vault architecture. Adding a new screen = add an enum value +
 * a branch in the when-block + a call-site that sets currentScreen.
 *
 * Debug screens (AGENT_DEBUG, BRAIN_DEBUG) are accessible only in BuildConfig.DEBUG
 * builds. Release builds never render the entry buttons and defensively redirect
 * to HOME if the state is somehow reached.
 */
enum class Screen {
    ONBOARDING,
    HOME,
    AGENTIC,
    PHOTOS,
    DOCUMENTS,
    SETTINGS,
    AGENT_DEBUG,   // Debug-only: hardcoded envelope harness for ControlLayer
    BRAIN_DEBUG    // Debug-only: natural-language → Brain → ControlLayer harness
}

@Composable
fun VaultApp() {
    val context = LocalContext.current

    // `prefsState` holds the Flow-backed delegate. Direct use of delegated properties
    // doesn't smart-cast through null checks (each access is a fresh getValue() call).
    // We snapshot ONCE into a local val below so the rest of the function gets clean
    // non-null access without `!!`.
    val prefsState by ScanPreferences.prefsFlow(context).collectAsState(initial = null)
    val prefs = prefsState ?: return

    var currentScreen by remember(prefs.onboardingDone) {
        mutableStateOf(if (prefs.onboardingDone) Screen.HOME else Screen.ONBOARDING)
    }

    when (currentScreen) {
        Screen.ONBOARDING -> {
            OnboardingScreen(onComplete = { currentScreen = Screen.HOME })
        }

        Screen.HOME -> {
            HomeScreen(
                onAgenticClick = { currentScreen = Screen.AGENTIC },
                onPhotosClick = { currentScreen = Screen.PHOTOS },
                onDocumentsClick = { currentScreen = Screen.DOCUMENTS },
                onSettingsClick = { currentScreen = Screen.SETTINGS },
            )
        }

        Screen.AGENTIC -> {
            AgenticScreen(onBack = { currentScreen = Screen.HOME })
        }

        Screen.PHOTOS -> {
            PhotosScreen(onBack = { currentScreen = Screen.HOME })
        }

        Screen.DOCUMENTS -> {
            ScreenWithBack(onBack = { currentScreen = Screen.HOME }) {
                DocumentPickerScreen()
            }
        }

        Screen.SETTINGS -> {
            SettingsScreen(
                onBack = { currentScreen = Screen.HOME },
                // Debug entry points. SettingsScreen guards the buttons with
                // BuildConfig.DEBUG, so these lambdas are never invoked in release.
                onOpenAgentDebug = { currentScreen = Screen.AGENT_DEBUG },
                onOpenBrainDebug = { currentScreen = Screen.BRAIN_DEBUG }
            )
        }

        Screen.AGENT_DEBUG -> {
            // Belt-and-suspenders: the button that sets this state is behind
            // BuildConfig.DEBUG, but guard the render too so release builds can't
            // accidentally land here via state restoration or deep links.
            if (BuildConfig.DEBUG) {
                ScreenWithBack(onBack = { currentScreen = Screen.HOME }) {
                    DebugAgentScreen()
                }
            } else {
                currentScreen = Screen.HOME
            }
        }

        Screen.BRAIN_DEBUG -> {
            if (BuildConfig.DEBUG) {
                ScreenWithBack(onBack = { currentScreen = Screen.HOME }) {
                    DebugBrainScreen()
                }
            } else {
                currentScreen = Screen.HOME
            }
        }
    }
}

@Composable
private fun ScreenWithBack(onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Cream)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Back", color = WarmBrown, fontSize = 16.sp) }
        }
        content()
    }
}