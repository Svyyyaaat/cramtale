package com.cramtale.app.ui

import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TouchApp
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
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Page(Modifier.weight(1f)) {
            Spacer(Modifier.height(24.dp))
            AppMark(64.dp)
            Text("Cramtale", style = MaterialTheme.typography.displaySmall, color = cs.onBackground)
            Text("物語で、復習を。", style = ReadingText.copy(fontSize = 22.sp), color = cs.accent)
            Text("A story you play in Japanese. Reading it is your Anki review.", style = MaterialTheme.typography.titleMedium,
                color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Feature(Icons.AutoMirrored.Filled.MenuBook, cs.primaryContainer, cs.onPrimaryContainer,
                "Your due cards, woven in", "Read a word without tapping: Good. Tap it: Again.")
            Feature(Icons.Filled.TouchApp, cs.tertiaryContainer, cs.onTertiaryContainer,
                "Tap any word", "Reading, your card's meaning, the meaning in that sentence.")
            Feature(Icons.Filled.Edit, cs.accentContainer, cs.onAccentContainer,
                "Reply in Japanese", "A game master answers; your mistakes are marked.")
            Feature(Icons.Filled.Lock, cs.surfaceContainerHigh, cs.onSurfaceVariant,
                "Read-only for now", "Nothing in your collection changes.")
        }
        StepFooter("Get started (about 10 minutes)", onStart)
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, container: androidx.compose.ui.graphics.Color,
                    content: androidx.compose.ui.graphics.Color, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, container, content, 44.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

data class InstallUi(val packageName: String, val title: String, val detail: String, val granted: Boolean, val isReal: Boolean)

@Composable
fun AnkiStepContent(installs: List<InstallUi>, selected: String?, onSelect: (String) -> Unit, onGrant: (String) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    val chosen = installs.firstOrNull { it.packageName == selected }
    StepFrame(onBack, footer = { StepFooter("Continue", onNext, enabled = chosen?.granted == true) }) {
        StepHeader(1, SETUP_STEPS, "Connect AnkiDroid",
            "Your decks and what's due come from AnkiDroid. Read-only.")
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
            Text("For later: a copy to write to", style = MaterialTheme.typography.titleSmall)
            Text("Answering cards will need a parallel AnkiDroid with your backup imported, sync off, and an empty ${AnkiTarget.SANDBOX_MARKER_DECK} deck. Reading the real one now is safe.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DictionaryStepContent(status: String, busy: Boolean, ready: Boolean, onDownload: () -> Unit, onImport: () -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    StepFrame(onBack, footer = { StepFooter("Continue", onNext, enabled = ready) }) {
        StepHeader(2, SETUP_STEPS, "Get the dictionary",
            "Readings and meanings for every word you tap in the story.")
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
            "Which words you know, from your recognition cards.")
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
            "About 80 words, common to rare: say whether you know each one. The story then avoids words you'd not know.")
    }
}

@Composable
fun KeyStepContent(key: String, onKey: (String) -> Unit, onNext: () -> Unit, onBack: () -> Unit,
                   claudeCode: Boolean = false, onBackend: (Boolean) -> Unit = {}, claudeCodeReady: Boolean = false,
                   claudeCodeSetup: @Composable () -> Unit = {}) {
    StepFrame(onBack, footer = {
        if (claudeCode) StepFooter("Next", onNext, enabled = claudeCodeReady)
        else StepFooter("Save key", onNext, enabled = key.trim().startsWith("sk-ant-"))
    }) {
        StepHeader(5, SETUP_STEPS, "Connect Claude",
            if (claudeCode) "Claude writes the story on your Claude subscription, through the Claude Code app in Termux."
            else "Claude writes the story. Paid from your Anthropic account: about 5–6¢ a passage.")
        Segmented(listOf("API key", "Claude Code"), if (claudeCode) 1 else 0) { onBackend(it == 1) }
        if (claudeCode) {
            PaperCard { claudeCodeSetup() }
            if (!claudeCodeReady) Text("Next unlocks when the test call works.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@StepFrame
        }
        OutlinedTextField(key, onKey, Modifier.fillMaxWidth(), label = { Text("API key") }, placeholder = { Text("sk-ant-…") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = MaterialTheme.shapes.medium)
        Text("Stored only on this phone, encrypted with a key from the Android Keystore. Every call's cost is logged.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StoryStepContent(seed: String, onSeed: (String) -> Unit, busy: String?, error: String?, onCreate: () -> Unit, onBack: () -> Unit,
                     taste: String = "", wait: (@Composable (String) -> Unit)? = null,
                     /** Text files picked for this story: name and size in characters. */
                     files: List<Pair<String, Int>> = emptyList(), onAddFiles: (() -> Unit)? = null, onRemoveFile: (String) -> Unit = {},
                     fileNote: String? = null,
                     /** The story this one continues (its world and people), or null for a fresh one. */
                     sequelOf: String? = null,
                     /** Who you play, if you want to name them; and this story's style (blank: [styleDefault]). */
                     name: String = "", onName: (String) -> Unit = {},
                     style: String = "", onStyle: (String) -> Unit = {}, styleDefault: String = "") {
    // nothing typed: the story is a surprise (a spark from many genres), and the button says so
    val primary = when {
        sequelOf != null -> "Start the next part"
        seed.isBlank() && files.isEmpty() -> "Surprise me"
        else -> "Create the story"
    }
    StepFrame(onBack, footer = { StepFooter(primary, onCreate, enabled = busy == null) }) {
        if (sequelOf != null) {
            StepHeader(6, SETUP_STEPS, "What happens next",
                "The next part of “$sequelOf”: the same world and people, who remember what happened. Time has passed.")
            OutlinedTextField(seed, onSeed, Modifier.fillMaxWidth().heightIn(min = 120.dp), shape = MaterialTheme.shapes.medium,
                label = { Text("What should this part be about? (optional)") }, placeholder = { Text("A month later, a letter arrives…") })
        } else {
            StepHeader(6, SETUP_STEPS, "Your story",
                "Any genre. A few people with their own goals and secrets, and trouble that moves while you're away.")
            OutlinedTextField(seed, onSeed, Modifier.fillMaxWidth().heightIn(min = 120.dp), shape = MaterialTheme.shapes.medium,
                label = { Text("What should it be about? (optional)") }, placeholder = { Text("A place, a trouble, who you play…") })
            // a few starting points, one tap to fill in (and edit)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (example in PREMISE_EXAMPLES) androidx.compose.material3.SuggestionChip({ onSeed(example) }, { Text(example, maxLines = 1) })
            }
            OutlinedTextField(name, onName, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, singleLine = true,
                label = { Text("Your character's name (optional)") }, placeholder = { Text("Left empty, the story names them") })
            // the default shows greyed out: type to replace it for this story only
            // the title sits above, not inside: a field with a label hides its placeholder until focused, and the
            // default has to be visible (greyed out) while nothing is typed
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Story style (optional)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp))
                OutlinedTextField(style, onStyle, Modifier.fillMaxWidth().heightIn(min = 120.dp), shape = MaterialTheme.shapes.medium,
                    placeholder = { Text(styleDefault, maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    supportingText = { Text(if (style.isBlank()) "Your default style, shown greyed out (change it in Settings). Type to use another for this story." else "For this story only") })
            }
        }
        // your own material: lore, a character sheet, notes, an old chat log
        if (onAddFiles != null) PaperCard {
            Text("Your files", style = MaterialTheme.typography.titleSmall)
            if (files.isEmpty()) Text("Optional: text files with a world, your character, other people or an earlier chat. The story is built from them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((name, size) in files) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(if (size >= 1000) "%,d KB".format((size + 512) / 1024) else "$size characters", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.IconButton({ onRemoveFile(name) }) { Icon(Icons.Filled.Close, "Remove $name") }
            }
            if (fileNote != null) Text(fileNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            QuietButton(if (files.isEmpty()) "Add files" else "Add more", onAddFiles, icon = Icons.Filled.Add)
        }
        if (busy != null) wait?.invoke(busy) ?: Busy(busy)
        if (error != null) ErrorNote(error)
    }
}

/** Example premises on the story step: different genres, so nobody assumes it's always a small-town drama. */
val PREMISE_EXAMPLES = listOf(
    "A dragon egg hatches in a temple kitchen",
    "A ghost at a seaside café",
    "A heist on a flying ship",
    "A detective in a city that sells memories",
)

@Composable
fun StyleStepContent(options: List<Pair<StoryEngine.StyleOption, Double>>?, busy: String?, error: String?, onPick: (StoryEngine.StyleOption) -> Unit, onMore: () -> Unit, onBack: () -> Unit,
                     wait: (@Composable (String) -> Unit)? = null) {
    StepFrame(onBack, footer = {}) {
        StepHeader(7, SETUP_STEPS, "Pick the one you like reading",
            "One scene in different styles. Your pick sets the voice of the story; you can change it any time from the home screen.")
        if (busy != null) wait?.invoke(busy) ?: Busy(busy)
        if (error != null) ErrorNote(error, onMore)
        for ((o, rate) in options.orEmpty()) {
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Pill("%.0f%% unknown".format(rate * 100), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(o.passage, style = ReadingText.copy(fontSize = 17.sp, lineHeight = 30.sp))
                androidx.compose.material3.FilledTonalButton({ onPick(o) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("This one")
                }
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
    val back = {
        // backing out of a new story (from inside the app): cancel it, back to the story you were in
        if (step == 6 && state.story.newStoryAsked) { state.story.cancelNewStory(); state.onboardingDone = true; onFinished() }
        else go((step - 1).coerceAtLeast(0))
    }
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
            if (state.story.canCallClaude()) { LaunchedEffect(Unit) { go(6) }; return }
            var key by remember { mutableStateOf("") }
            var tested by remember { mutableStateOf(false) }
            KeyStepContent(key, { key = it }, onNext = { if (!state.claudeCode) state.story.saveKey(key.trim()); go(6) }, onBack = back,
                claudeCode = state.claudeCode, onBackend = { state.chooseClaudeCode(it) }, claudeCodeReady = tested,
                claudeCodeSetup = { ClaudeCodeSetup(state, onReady = { tested = true }, intro = false) })
        }
        6 -> {
            if (state.story.isSetUp() && !state.story.newStoryAsked) { LaunchedEffect(Unit) { go(7) }; return }
            val taste = remember { state.story.taste() }
            var files by remember { mutableStateOf(state.story.attachments().map { it.name to it.text.length }) }
            var fileNote by remember { mutableStateOf<String?>(null) }
            val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                scope.launch {
                    val notes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { uris.mapNotNull { state.story.attach(it) } }
                    fileNote = notes.joinToString("\n").ifEmpty { null }
                    files = state.story.attachments().map { it.name to it.text.length }
                }
            }
            val sequelOf = remember { state.story.sequelTitle() }
            StoryStepContent(state.story.seedDraft, { state.story.seedDraft = it }, state.story.busy, state.story.error,
                files = files, fileNote = fileNote, sequelOf = sequelOf,
                name = state.story.nameDraft, onName = { state.story.nameDraft = it },
                style = state.story.styleDraft, onStyle = { state.story.styleDraft = it }, styleDefault = remember { state.story.defaultTaste() },
                // files are for a fresh story (a sequel keeps the earlier one's world and files)
                onAddFiles = if (sequelOf == null) ({ pick.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }) else null,
                onRemoveFile = { n -> state.story.detach(n); files = state.story.attachments().map { it.name to it.text.length } },
                onCreate = { scope.launch {
                    // only now does the story you were in go onto the shelf
                    if (state.story.newStoryAsked && !state.story.commitNewStory()) return@launch
                    state.story.setup(state.story.seedDraft)
                    if (state.story.isSetUp()) go(7)
                } }, onBack = back,
                taste = taste, wait = { label -> StudyWhileWaiting(state, label) })
        }
        else -> {
            // a new story keeps the style you picked before
            if (state.story.hasStyle()) { LaunchedEffect(Unit) { state.onboardingDone = true; onFinished() }; return }
            // the built-in styles show at once; "four more" asks Claude for new ones
            var options by remember { mutableStateOf<List<Pair<StoryEngine.StyleOption, Double>>?>(state.story.presets()) }
            StyleStepContent(options, state.story.busy, state.story.error,
                onPick = { o -> scope.launch { state.story.pickStyle(o); state.onboardingDone = true; onFinished() } },
                onMore = { scope.launch { state.story.styleOptions(fresh = true)?.let { options = it } } },
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
