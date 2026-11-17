package com.cramtale.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cramtale.app.AppState
import com.cramtale.app.anki.AnkiReader
import com.cramtale.app.anki.AnkiTarget
import com.cramtale.core.match.CardState
import com.cramtale.core.story.StoryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val SETUP_STEPS = 7

// ---------------------------------------------------------------- stateless step layouts

@Composable
fun WelcomeContent(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Page(Modifier.weight(1f)) {
            Spacer(Modifier.height(32.dp))
            Text("Cramtale", style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Serif), color = MaterialTheme.colorScheme.primary)
            Text("物語で、復習を。", style = ReadingText.copy(fontSize = 22.sp), color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
            Text("A story you play in Japanese, where reading is your Anki review.", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Bullet("A game master writes a hard-edged story at your level. You reply in Japanese, and your mistakes are marked beside your message.")
            Bullet("Words Anki says are due are woven into the passages. Read one without tapping and it counts as Good; tap it and it counts as Again.")
            Bullet("Tap any word for its reading and meaning in that sentence.")
            Bullet("This version is read-only: it logs what it would answer and never changes your collection.")
            Spacer(Modifier.height(4.dp))
            Text("Setup takes about five minutes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StepFooter("Get started", onStart)
    }
}

data class InstallUi(val packageName: String, val title: String, val detail: String, val granted: Boolean, val isReal: Boolean)

@Composable
fun AnkiStepContent(installs: List<InstallUi>, selected: String?, onSelect: (String) -> Unit, onGrant: (String) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    val chosen = installs.firstOrNull { it.packageName == selected }
    StepFrame(onBack, footer = { StepFooter("Continue", onNext, enabled = chosen?.granted == true) }) {
        StepHeader(1, SETUP_STEPS, "Connect AnkiDroid",
            "Cramtale reads your decks and Anki's due queue from AnkiDroid. It only reads; nothing in your collection changes.")
        if (installs.isEmpty()) ErrorNote("AnkiDroid isn't installed on this phone.")
        for (i in installs) {
            Surface(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onSelect(i.packageName) },
                shape = MaterialTheme.shapes.medium,
                color = if (i.packageName == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, if (i.packageName == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(i.packageName == selected, { onSelect(i.packageName) })
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(i.title, style = MaterialTheme.typography.titleSmall)
                        Text(i.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (i.granted) Pill("Access granted", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }
        if (chosen != null && !chosen.granted) PrimaryButton("Allow access to ${chosen.title}", { onGrant(chosen.packageName) })
        PaperCard {
            Text("Using a copy", style = MaterialTheme.typography.titleSmall)
            Text("Later versions will answer cards and add notes. For that, use a parallel AnkiDroid build with a backup of your collection imported, never logged into sync, with an empty deck named ${AnkiTarget.SANDBOX_MARKER_DECK}. Reading your real AnkiDroid now is safe.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DictionaryStepContent(status: String, busy: Boolean, ready: Boolean, onDownload: () -> Unit, onImport: () -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    StepFrame(onBack, footer = { StepFooter("Continue", onNext, enabled = ready) }) {
        StepHeader(2, SETUP_STEPS, "Get the dictionary",
            "JMdict gives every word's readings and meanings, and lets Cramtale recognise spelling variants of your cards (付属 / 附属, なぜ / 何故). It comes with the app and only needs unpacking.")
        PaperCard {
            if (busy) Busy(status) else Text(status, style = MaterialTheme.typography.bodyLarge)
            if (!ready && !busy) {
                PrimaryButton("Download (10 MB)", onDownload, icon = Icons.Filled.Download)
                SecondaryButton("I have JMdict_e.gz", onImport, icon = Icons.Filled.FolderOpen)
            }
        }
        Text("JMdict © EDRDG, CC BY-SA 4.0.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

data class DeckRowUi(val name: String, val total: Int, val known: Int, val new: Int)

@Composable
fun DeckStepContent(rows: List<DeckRowUi>, status: String?, busy: Boolean, error: String?, onRead: () -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    StepFrame(onBack, footer = { StepFooter("Continue", onNext, enabled = rows.isNotEmpty() && !busy) }) {
        StepHeader(3, SETUP_STEPS, "Read your decks",
            "Your Core 5k, V2K and sentence decks, with the state of each recognition card. This decides which words count as known.")
        if (busy) Busy(status ?: "Reading…")
        else if (rows.isEmpty()) PrimaryButton("Read my decks", onRead)
        if (error != null) ErrorNote(error)
        if (rows.isNotEmpty()) {
            PaperCard {
                Text("%,d words".format(rows.sumOf { it.total }), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("%,d known · %,d not studied yet".format(rows.sumOf { it.known }, rows.sumOf { it.new }),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PaperCard {
                for (r in rows) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name.substringAfterLast("::"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("%,d".format(r.total), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            QuietButton("Read again", onRead, icon = Icons.Filled.Refresh)
        }
    }
}

@Composable
fun VocabIntroContent(cutoff: Int, onStart: () -> Unit, onSkip: () -> Unit, onBack: () -> Unit) {
    StepFrame(onBack, footer = { StepFooter("Start (2 minutes)", onStart, secondary = "Skip for now", onSecondary = onSkip) }) {
        StepHeader(4, SETUP_STEPS, "How far does your vocabulary reach?",
            "About 80 words that aren't in your decks, from common to rare. Say whether you know each one. If you tap the wrong button, Back undoes it.")
        PaperCard {
            Text("Why", style = MaterialTheme.typography.titleSmall)
            Text("Passages are kept to under 3% words you don't know. Your decks cover most of what you know; this finds the frequency cut-off for the rest.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Current cut-off: %,d".format(cutoff), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun KeyStepContent(key: String, onKey: (String) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    StepFrame(onBack, footer = { StepFooter("Save key", onNext, enabled = key.trim().startsWith("sk-ant-")) }) {
        StepHeader(5, SETUP_STEPS, "Your Claude API key",
            "The game master is Claude Opus 5.5, with Claude Haiku 5.5 doing the checks. Calls are paid from your Anthropic account: roughly 5–6 cents a turn.")
        OutlinedTextField(key, onKey, Modifier.fillMaxWidth(), label = { Text("API key") }, placeholder = { Text("sk-ant-…") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = MaterialTheme.shapes.medium)
        Text("Stored only on this phone, encrypted with a key from the Android Keystore. Every call's cost is logged.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StoryStepContent(seed: String, onSeed: (String) -> Unit, busy: String?, error: String?, onCreate: () -> Unit, onBack: () -> Unit,
                     taste: String = "", wait: (@Composable (String) -> Unit)? = null) {
    StepFrame(onBack, footer = { StepFooter("Create the story", onCreate, enabled = busy == null) }) {
        StepHeader(6, SETUP_STEPS, "Your story",
            "The game master builds a small world from your taste: a place, three or four people with their own goals, limits and secrets, and threats that move while you're away.")
        PaperCard {
            Text("Your taste (config/plot.md)", style = MaterialTheme.typography.titleSmall)
            Text(taste.ifBlank { "(empty: edit config/plot.md in Prompts and story files)" },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(seed, onSeed, Modifier.fillMaxWidth().heightIn(min = 120.dp), shape = MaterialTheme.shapes.medium,
            label = { Text("What should it be about? (optional)") }, placeholder = { Text("e.g. a fishing town, a debt, a family business") })
        if (busy != null) wait?.invoke(busy) ?: Busy(busy)
        if (error != null) ErrorNote(error)
    }
}

@Composable
fun StyleStepContent(options: List<Pair<StoryEngine.StyleOption, Double>>?, busy: String?, error: String?, onPick: (StoryEngine.StyleOption) -> Unit, onMore: () -> Unit, onBack: () -> Unit,
                     wait: (@Composable (String) -> Unit)? = null) {
    StepFrame(onBack, footer = {}) {
        StepHeader(7, SETUP_STEPS, "Pick the one you like reading",
            "The same short scene in four styles. Your choice becomes the writer's style sample for the whole story.")
        if (busy != null) wait?.invoke(busy) ?: Busy(busy)
        if (error != null) ErrorNote(error, onMore)
        for ((o, rate) in options.orEmpty()) {
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Pill("%.0f%% unknown".format(rate * 100), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(o.passage, style = ReadingText.copy(fontSize = 17.sp, lineHeight = 30.sp))
                PrimaryButton("Read in this style", { onPick(o) })
            }
        }
        if (options != null && busy == null) SecondaryButton("Show me four more", onMore, icon = Icons.Filled.Refresh)
    }
}

/** A setup step: back arrow, scrolling content, buttons pinned below. */
@Composable
fun StepFrame(onBack: () -> Unit, footer: @Composable () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        AppBar("", onBack = onBack)
        Page(Modifier.weight(1f), content = content)
        footer()
    }
}

// ---------------------------------------------------------------- the flow

/** Welcome, then seven steps; progress is saved, so leaving the app resumes where you were. */
@Composable
fun OnboardingFlow(state: AppState, onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(state.onboardingStep) }
    fun go(n: Int) { step = n; state.onboardingStep = n }
    val back = { go((step - 1).coerceAtLeast(0)) }
    androidx.activity.compose.BackHandler(enabled = step > 0) { back() }

    when (step) {
        0 -> WelcomeContent { go(1) }
        1 -> {
            var refresh by remember { mutableIntStateOf(0) }
            var selected by remember { mutableStateOf(state.targetPackage) }
            val installs = remember(refresh) {
                AnkiReader.installed(context).map { i ->
                    InstallUi(i.target.packageName,
                        if (i.target.isReal) "AnkiDroid (your real collection)" else "AnkiDroid parallel build (${i.target.packageName.substringAfterLast('.')})",
                        "Version ${i.versionName}" + if (!i.providerFound) " · its provider wasn't found" else "", i.granted, i.target.isReal)
                }
            }
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
            AnkiStepContent(installs, selected,
                onSelect = { selected = it; state.targetPackage = it },
                onGrant = { ask.launch(AnkiTarget(it).permission) },
                onNext = { go(2) }, onBack = back)
        }
        2 -> {
            var status by remember { mutableStateOf("Checking…") }
            var busy by remember { mutableStateOf(false) }
            var ready by remember { mutableStateOf(false) }
            fun run(block: ((String) -> Unit) -> Unit) = scope.launch {
                busy = true
                status = withContext(Dispatchers.IO) {
                    runCatching { block { status = it }; state.dictionaryRebuilt(); "Dictionary ready" }.getOrElse { "Failed: ${it.message}" }
                }
                ready = state.dictionary.isReady()
                busy = false
            }
            val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) run { p -> state.dictionary.import(uri, p) }
            }
            LaunchedEffect(Unit) {
                ready = withContext(Dispatchers.IO) { state.dictionary.isReady() }
                status = if (ready) "The dictionary is ready." else "Not set up yet."
                // the APK usually carries it prebuilt: unpacking takes seconds; otherwise download and build
                if (!ready) run { p -> if (state.dictionary.hasBundled()) state.dictionary.installBundled(p) else state.dictionary.download(p) }
            }
            DictionaryStepContent(status, busy, ready, onDownload = { run { p -> state.dictionary.download(p) } },
                onImport = { importer.launch(arrayOf("*/*")) }, onNext = { go(3) }, onBack = back)
        }
        3 -> {
            var rows by remember { mutableStateOf(deckRows(state)) }
            var status by remember { mutableStateOf<String?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            var busy by remember { mutableStateOf(false) }
            DeckStepContent(rows, status, busy, error, onRead = {
                scope.launch {
                    busy = true
                    error = withContext(Dispatchers.IO) { runCatching { state.reloadDeck { s -> status = s }; null }.getOrElse { "Couldn't read the decks: ${it.message}" } }
                    rows = deckRows(state)
                    busy = false
                }
            }, onNext = { go(4) }, onBack = back)
        }
        4 -> {
            var running by remember { mutableStateOf(false) }
            if (!running) VocabIntroContent(state.policy.frequencyThreshold, onStart = { running = true }, onSkip = { go(5) }, onBack = back)
            else Column(Modifier.fillMaxSize()) {
                AppBar("Vocabulary check", onBack = { running = false })
                VocabCheckFlow(state) { go(5) }
            }
        }
        5 -> {
            if (state.story.hasKey()) { LaunchedEffect(Unit) { go(6) }; return }
            var key by remember { mutableStateOf("") }
            KeyStepContent(key, { key = it }, onNext = { state.story.saveKey(key.trim()); go(6) }, onBack = back)
        }
        6 -> {
            if (state.story.isSetUp()) { LaunchedEffect(Unit) { go(7) }; return }
            var seed by remember { mutableStateOf("") }
            val taste = remember { state.story.taste() }
            StoryStepContent(seed, { seed = it }, state.story.busy, state.story.error,
                onCreate = { scope.launch { state.story.setup(seed); if (state.story.isSetUp()) go(7) } }, onBack = back,
                taste = taste, wait = { label -> StudyWhileWaiting(state, label) })
        }
        else -> {
            // a new story keeps the style you picked before
            if (state.story.hasStyle()) { LaunchedEffect(Unit) { state.onboardingDone = true; onFinished() }; return }
            var options by remember { mutableStateOf<List<Pair<StoryEngine.StyleOption, Double>>?>(null) }
            LaunchedEffect(Unit) { options = state.story.styleOptions() }
            StyleStepContent(options, state.story.busy, state.story.error,
                onPick = { o -> scope.launch { state.story.pickStyle(o); state.onboardingDone = true; onFinished() } },
                onMore = { scope.launch { options = state.story.styleOptions(fresh = options != null) } },
                onBack = { if (state.story.restoreStyle()) { state.onboardingDone = true; onFinished() } else back() },
                wait = { label -> StudyWhileWaiting(state, label) })
        }
    }
}

fun deckRows(state: AppState): List<DeckRowUi> = state.deck().groupBy { it.deck }.toSortedMap().map { (deck, ws) ->
    DeckRowUi(deck, ws.size, ws.count { it.state == CardState.STUDIED || it.state == CardState.STUDIED_SUSPENDED || it.state == CardState.NEW_SUSPENDED },
        ws.count { it.state == CardState.NEW })
}

/** The wait panel with the story's flashcards (setup and the style samples take a while). */
@Composable
fun StudyWhileWaiting(state: com.cramtale.app.AppState, label: String) {
    val story = state.story
    WaitPanel(label, story.study, { c, good -> story.answerFlashcard(c, good) }, { c, known -> story.triageNew(c, known) })
}
