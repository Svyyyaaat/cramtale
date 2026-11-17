package com.cramtale.app.ui

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.LaunchedEffect
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
    SETTINGS("Settings"),
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
    var scale by remember { mutableStateOf(state.textScale) }
    var home by remember { mutableStateOf<HomeInfo?>(null) }
    val stack = remember { mutableStateListOf(Screen.HOME) }
    val screen = stack.last()
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    BackHandler(enabled = !onboarding && stack.size > 1) { back() }

    // a Surface, so every text gets the theme's content colour (dark mode included)
    // home data reads files, the deck snapshot and AnkiDroid: off the main thread, fresh on each return
    LaunchedEffect(screen, onboarding) {
        if (!onboarding && screen == Screen.HOME) home = withContext(Dispatchers.IO) { runCatching { homeInfo(state) }.getOrNull() } ?: home
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalReadingScale provides scale) {
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        if (onboarding) {
            OnboardingFlow(state) { onboarding = false; stack.clear(); stack.add(Screen.HOME) }
            return@Column
        }
        when (screen) {
            Screen.HOME -> home?.let { HomeContent(it, go = { s -> stack.add(s) }, onRestartSetup = { state.onboardingStep = 0; state.onboardingDone = false; onboarding = true }) }
                ?: Busy("Opening…")
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
                    Screen.SETTINGS -> SettingsScreen(state, onScale = { scale = it }, onPickStyle = {
                        state.story.clearStyle(); state.onboardingStep = 7; state.onboardingDone = false; onboarding = true
                    }, onNewStory = {
                        if (state.story.archiveAndReset() != null) {
                            // straight to "Your story": the rest of setup is done
                            state.onboardingStep = 6; state.onboardingDone = false; onboarding = true
                        }
                    })
                    else -> {}
                }
            }
        }
    }
    }
    }
}

/** Everything the home screen shows. Reads files and AnkiDroid: call off the main thread. */
fun homeInfo(state: AppState): HomeInfo {
    val s = state.story
    val t = state.target
    val words = state.deck()
    val due = runCatching {
        val reader = com.cramtale.app.anki.AnkiReader(state.context, t!!)
        if (!reader.hasPermission()) return@runCatching null
        val names = words.map { it.deck }.toSet()
        // a parent deck's counts include its children: count only the top-most of the vocabulary decks
        val top = names.filter { n -> names.none { it != n && n.startsWith("$it::") } }.toSet()
        reader.decks().filter { it.name in top }.let { ds -> DueCounts(ds.sumOf { it.review }, ds.sumOf { it.learn }, ds.sumOf { it.new }) }
    }.getOrNull()
    val today = s.gradeLog.today()
    return HomeInfo(
        due = due, perPassage = s.cardsPerPassage() ?: state.passageLength.estimate, readToday = today.cards, goodToday = today.good, againToday = today.again,
        place = s.place(), turn = s.turn(), chapter = s.chapter(), lastLines = s.lastLines(), totalCost = s.totalCost(),
        collection = t?.let { (if (it.isReal) "Real collection" else "Copy") + " · " + it.packageName } ?: "Not connected",
        collectionIsReal = t?.isReal == true, connected = t != null && words.isNotEmpty(),
        deckWords = words.size, cutoff = state.policy.frequencyThreshold,
    )
}
