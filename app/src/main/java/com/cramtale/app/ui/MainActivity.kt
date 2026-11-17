package com.cramtale.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cramtale.app.AppState
import com.cramtale.app.CramtaleApp

enum class Screen(val title: String) {
    HOME("Cramtale"), STORY("Story"), VOCAB("Vocabulary check"), CALIBRATE("Calibrate on text"), DECK("Your decks"),
    LOGS("Review log and costs"), FILES("Prompts and story files"), TARGET("AnkiDroid connection"), PROBE("AnkiDroid probe"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val state = (application as CramtaleApp).state
        setContent { CramtaleTheme { App(state) } }
    }
}

@Composable
fun App(state: AppState) {
    var onboarding by remember { mutableStateOf(!state.onboardingDone) }
    val stack = remember { mutableStateListOf(Screen.HOME) }
    val screen = stack.last()
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    BackHandler(enabled = !onboarding && stack.size > 1) { back() }

    // a Surface, so every text gets the theme's content colour (dark mode included)
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        if (onboarding) {
            OnboardingFlow(state) { onboarding = false; stack.clear(); stack.add(Screen.HOME) }
            return@Column
        }
        when (screen) {
            Screen.HOME -> HomeContent(homeInfo(state), go = { stack.add(it) }, onRestartSetup = { state.onboardingStep = 0; state.onboardingDone = false; onboarding = true })
            Screen.STORY -> StoryScreen(state, onBack = back)
            Screen.VOCAB -> { AppBar(screen.title, back); VocabCheckFlow(state) { back() } }
            else -> {
                AppBar(screen.title, back)
                when (screen) {
                    Screen.CALIBRATE -> CalibrateScreen(state)
                    Screen.DECK -> DeckScreen(state)
                    Screen.LOGS -> LogsScreen(state)
                    Screen.FILES -> FilesScreen(state)
                    Screen.TARGET -> TargetScreen(state) { stack.add(Screen.PROBE) }
                    Screen.PROBE -> ProbeScreen(state)
                    else -> {}
                }
            }
        }
    }
    }
}

fun homeInfo(state: AppState): HomeInfo {
    val s = state.story
    val t = state.target
    return HomeInfo(
        place = s.place(), turn = s.turn(), chapter = s.chapter(), lastLines = s.lastLines(), totalCost = s.totalCost(),
        collection = t?.let { (if (it.isReal) "Real collection" else "Copy") + " · " + it.packageName } ?: "Not connected",
        collectionIsReal = t?.isReal == true, connected = t != null && state.deck().isNotEmpty(),
        deckWords = state.deck().size, cutoff = state.policy.frequencyThreshold,
    )
}
