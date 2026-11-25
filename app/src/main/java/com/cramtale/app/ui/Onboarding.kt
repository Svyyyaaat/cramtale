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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalUriHandler
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
            Text("A story you play in Japanese, written around the Anki words you have due.", style = MaterialTheme.typography.titleMedium,
                color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Feature(Icons.AutoMirrored.Filled.MenuBook, cs.primaryContainer, cs.onPrimaryContainer,
                "Your due words, woven in", "Read past a word: you knew it. Tap it: you didn't.")
            Feature(Icons.Filled.TouchApp, cs.tertiaryContainer, cs.onTertiaryContainer,
                "Tap any word", "Reading, your card's meaning, the meaning in that sentence.")
            Feature(Icons.Filled.Edit, cs.accentContainer, cs.onAccentContainer,
                "Reply in Japanese", "A game master answers; your mistakes are marked.")
            Feature(Icons.Filled.Lock, cs.surfaceContainerHigh, cs.onSurfaceVariant,
                "Practice only, for now", "Cramtale reads Anki but doesn't answer your cards yet. Keep reviewing in AnkiDroid as usual.")
            Feature(Icons.Filled.Key, cs.surfaceContainerHigh, cs.onSurfaceVariant,
                "You'll need", "AnkiDroid on this phone, and Claude: an Anthropic API key (about 5¢ a passage) or your Claude subscription.")
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
fun AnkiStepContent(installs: List<InstallUi>, selected: String?, onSelect: (String) -> Unit, onGrant: (String) -> Unit, onNext: () -> Unit, onBack: () -> Unit,
                    /** Asked before and refused for good: Android won't show the prompt again. */
                    denied: Boolean = false, onOpenSettings: () -> Unit = {}, onGetAnki: () -> Unit = {}) {
    val chosen = installs.firstOrNull { it.packageName == selected }
    StepFrame(onBack, footer = { StepFooter("Continue", onNext, enabled = chosen?.granted == true) }) {
        StepHeader(1, SETUP_STEPS, "Connect AnkiDroid",
            "Your decks and what's due come from AnkiDroid. Cramtale only reads them.")
        if (installs.isEmpty()) PaperCard {
            Text("AnkiDroid isn't on this phone", style = MaterialTheme.typography.titleSmall)
            Text("Install it and sync or import your decks. This screen picks it up when you come back.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton("Get AnkiDroid", onGetAnki, icon = Icons.Filled.Download)
        }
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
        if (chosen != null && !chosen.granted) {
            if (denied) {
                Text("Android won't ask again. Allow \"Read and write to the AnkiDroid database\" in Cramtale's permissions, then come back.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryButton("Open Cramtale's settings", onOpenSettings)
            } else PrimaryButton("Allow access to ${chosen.title}", { onGrant(chosen.packageName) })
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
        else StepFooter("Save key", onNext, enabled = looksLikeKey(key))
    }) {
        StepHeader(5, SETUP_STEPS, "Connect Claude",
            if (claudeCode) "Claude writes the story on your Claude subscription, through the Claude Code app in Termux."
            else "Claude writes the story. Paid from your Anthropic account: about 5¢ a passage.")
        Segmented(listOf("API key", "Claude subscription"), if (claudeCode) 1 else 0) { onBackend(it == 1) }
        if (claudeCode) {
            PaperCard { claudeCodeSetup() }
            if (!claudeCodeReady) Text("Next unlocks when the test call works.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@StepFrame
        }
        var shown by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        OutlinedTextField(key, onKey, Modifier.fillMaxWidth(), label = { Text("API key") }, placeholder = { Text("sk-ant-…") },
            singleLine = true, visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(), shape = MaterialTheme.shapes.medium,
            trailingIcon = { androidx.compose.material3.IconButton({ shown = !shown }) {
                Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (shown) "Hide key" else "Show key") } },
            isError = notAKey(key),
            supportingText = when {
                notAKey(key) -> ({ Text("Anthropic keys start with sk-ant-") })
                key.isNotBlank() && !looksLikeKey(key) -> ({ Text("Keep going: paste the whole key") })
                else -> null
            })
        Text("Stored only on this phone, encrypted with a key from the Android Keystore. Every call's cost is logged.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val uri = LocalUriHandler.current
        QuietButton("Get a key at console.anthropic.com", { uri.openUri("https://console.anthropic.com/settings/keys") })
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
                     style: String = "", onStyle: (String) -> Unit = {}, styleDefault: String = "",
                     /** Started from inside the app (not first-run setup): no setup progress. */
                     inApp: Boolean = false,
                     /** Back to the Claude step (a refused key, no credit): setup's Settings isn't reachable from here. */
                     onFixClaude: (() -> Unit)? = null) {
    // nothing typed: the story is a surprise (a spark from many genres), and the button says so
    val primary = when {
        sequelOf != null -> "Start the next part"
        seed.isBlank() && files.isEmpty() && name.isBlank() && style.isBlank() -> "Surprise me"
        else -> "Create the story"
    }
    // while it's written: the form is out of the way and the wait (with flashcards) is in view; an error sits by the button
    StepFrame(onBack, footer = {
        if (busy == null) Column {
            if (error != null) Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp)) { ErrorNote(error) }
            if (error != null && onFixClaude != null) StepFooter("Try again", onCreate, secondary = "Change how Claude connects", onSecondary = onFixClaude)
            else StepFooter(if (error != null) "Try again" else primary, onCreate)
        }
    }) {
        if (busy != null) {
            Text(if (sequelOf != null) "Writing the next part" else "Writing your story", style = MaterialTheme.typography.headlineSmall)
            Text("This takes a minute or two. You can leave the app; it carries on.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            wait?.invoke(busy) ?: Busy(busy)
            return@StepFrame
        }
        if (sequelOf != null) {
            StepHeader(6, if (inApp) 0 else SETUP_STEPS, "What happens next",
                "The next part of “$sequelOf”: the same world and people, who remember what happened. Time has passed.")
            OutlinedTextField(seed, onSeed, Modifier.fillMaxWidth().heightIn(min = 120.dp), shape = MaterialTheme.shapes.medium,
                label = { Text("What should this part be about? (optional)") }, placeholder = { Text("A month later, a letter arrives…") })
        } else {
            StepHeader(6, if (inApp) 0 else SETUP_STEPS, if (inApp) "New story" else "Your story",
                "Any genre. A few people with their own goals and secrets, and trouble that moves while you're away.")
            OutlinedTextField(seed, onSeed, Modifier.fillMaxWidth().heightIn(min = 120.dp), shape = MaterialTheme.shapes.medium,
                label = { Text("What should it be about? (optional)") }, placeholder = { Text("A place, a trouble, who you play…") })
            // a few starting points, one tap to fill in (and edit)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (example in PREMISE_EXAMPLES) androidx.compose.material3.FilterChip(seed.trim() == example, { onSeed(example) }, { Text(example) })
            }
            OutlinedTextField(name, onName, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, singleLine = true,
                label = { Text("Your character's name (optional)") }, placeholder = { Text("Left empty, the story names them") })
            // the default shows greyed out: type to replace it for this story only
            // the title sits above, not inside: a field with a label hides its placeholder until focused, and the
            // default has to be visible (greyed out) while nothing is typed
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("How it should feel (optional)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp))
                OutlinedTextField(style, onStyle, Modifier.fillMaxWidth().heightIn(min = 96.dp), shape = MaterialTheme.shapes.medium,
                    placeholder = { Text(styleDefault, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    supportingText = { Text(if (style.isBlank()) "Leave empty for your usual feel. Change that in Settings." else "For this story only") })
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
        StepHeader(7, SETUP_STEPS, "Pick a writing style",
            "The same scene, written different ways. Pick the one you'd like to read; you can change it any time from the home screen.")
        if (busy != null) wait?.invoke(busy) ?: Busy(busy)
        if (error != null) ErrorNote(error, onMore)
        for ((o, rate) in options.orEmpty()) {
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Pill("%.0f%% new words".format(rate * 100), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(o.passage, style = ReadingText.copy(fontSize = 17.sp, lineHeight = 30.sp))
                androidx.compose.material3.FilledTonalButton({ onPick(o) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Use this style")
                }
            }
        }
        if (options != null && busy == null) SecondaryButton("Write four new styles", onMore, icon = Icons.Filled.Refresh)
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
    // asked from the story step after Claude refused: show the Claude step even though a key is saved
    var reconnect by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    fun go(n: Int) { step = n; state.onboardingStep = n }
    val back = {
        // backing out of a new story (from inside the app): cancel it, back to the story you were in
        if (step == 6 && state.story.newStoryAsked) { state.story.cancelNewStory(); state.onboardingDone = true; onFinished() }
        else if (step == 5 && reconnect) { reconnect = false; go(6) }
        else go(previousShownStep(step, state))
    }
    androidx.activity.compose.BackHandler(enabled = step > 0) { back() }

    when (step) {
        0 -> WelcomeContent { go(1) }
        1 -> {
            var refresh by remember { mutableIntStateOf(0) }
            var selected by remember { mutableStateOf(state.targetPackage) }
            var denied by remember { mutableStateOf(false) }
            // back from installing AnkiDroid or from the permission settings: look again
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(lifecycle) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
                lifecycle.lifecycle.addObserver(obs); onDispose { lifecycle.lifecycle.removeObserver(obs) }
            }
            val installs = remember(refresh) {
                AnkiReader.installed(context).map { i ->
                    InstallUi(i.target.packageName,
                        if (i.target.isReal) "AnkiDroid" else "Second AnkiDroid (a copy, ${i.target.packageName.substringAfterLast('.')})",
                        "Version ${i.versionName}" + if (!i.providerFound) " · its provider wasn't found" else "", i.granted, i.target.isReal)
                }
            }
            val activity = context as? android.app.Activity
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                // refused, and Android won't show the prompt again: point to the settings instead
                denied = !ok && selected != null && activity?.shouldShowRequestPermissionRationale(AnkiTarget(selected!!).permission) == false
                refresh++
            }
            if (installs.size == 1 && selected != installs[0].packageName) LaunchedEffect(Unit) { selected = installs[0].packageName; state.targetPackage = selected }
            AnkiStepContent(installs, selected,
                onSelect = { selected = it; state.targetPackage = it; denied = false },
                onGrant = { ask.launch(AnkiTarget(it).permission) },
                onNext = { go(2) }, onBack = back, denied = denied,
                onOpenSettings = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null))) },
                onGetAnki = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=com.ichi2.anki"))) }.onFailure {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.ichi2.anki"))) } })
        }
        2 -> {
            var status by remember { mutableStateOf("Checking…") }
            var busy by remember { mutableStateOf(false) }
            var ready by remember { mutableStateOf(false) }
            fun run(block: ((String) -> Unit) -> Unit) = scope.launch {
                busy = true
                status = withContext(Dispatchers.IO) {
                    runCatching { block { status = it }; state.dictionaryRebuilt(); "Dictionary ready" }.getOrElse { "Couldn't get the dictionary (${it.message?.lineSequence()?.firstOrNull() ?: "unknown error"}). Check your connection and download again, or pick a JMdict_e.gz file." }
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
            var running by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            if (!running) VocabIntroContent(state.policy.frequencyThreshold, onStart = { running = true }, onSkip = { go(5) }, onBack = back)
            else Column(Modifier.fillMaxSize()) {
                VocabCheckFlow(state, onLeave = { running = false }) { go(5) }
            }
        }
        5 -> {
            if (state.story.canCallClaude() && !reconnect) { LaunchedEffect(Unit) { go(6) }; return }
            var key by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
            var tested by remember { mutableStateOf(false) }
            KeyStepContent(key, { key = it }, onNext = { if (!state.claudeCode) state.story.saveKey(key.trim()); reconnect = false; go(6) }, onBack = back,
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
                taste = taste, wait = { label -> StudyWhileWaiting(state, label) }, inApp = state.story.newStoryAsked,
                onFixClaude = { reconnect = true; go(5) })
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

/** An Anthropic API key, by its shape (one rule for setup and Settings). */
fun looksLikeKey(key: String) = key.trim().let { it.startsWith("sk-ant-") && it.length > 20 && it.none(Char::isWhitespace) }

/** Typed far enough to tell it isn't an Anthropic key (no red while a real one is still being typed or pasted). */
fun notAKey(key: String) = key.trim().let { k -> k.isNotEmpty() && !(k.startsWith("sk-ant-") || "sk-ant-".startsWith(k)) }

/** The step Back goes to: the nearest earlier one that would show, not one that forwards you straight back. */
fun previousShownStep(step: Int, state: AppState): Int {
    var n = step - 1
    while (n > 0 && ((n == 5 && state.story.canCallClaude()) || (n == 6 && state.story.isSetUp() && !state.story.newStoryAsked))) n--
    return n.coerceAtLeast(0)
}
