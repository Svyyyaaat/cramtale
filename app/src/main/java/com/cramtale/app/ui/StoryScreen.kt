package com.cramtale.app.ui

import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Style
import com.cramtale.core.review.DueCard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.cramtale.app.story.StoryController
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cramtale.app.AppState
import com.cramtale.app.story.ShownPassage
import com.cramtale.app.story.ShownTurn
import com.cramtale.core.match.GrammarPatterns
import com.cramtale.core.match.Span
import com.cramtale.core.match.Status
import com.cramtale.core.review.MistakeLog
import com.cramtale.core.story.Correction
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- stateless pieces

@Composable
fun PlayerBubble(text: String, corrections: List<Correction>, onNotAMistake: ((Correction) -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // solid indigo by day; at night the deep container (a bright bubble would outshine the story)
        val night = MaterialTheme.colorScheme.background.luminance() < 0.5f
        Surface(color = if (night) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                color = if (night) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary)
        }
        // corrections beside your message
        for (c in corrections) Row(Modifier.widthIn(max = 320.dp).clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Spellcheck, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.accent)
            Column {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.accent)) { append(c.wrong) }
                    append("  →  ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)) { append(c.right) }
                }, style = MaterialTheme.typography.bodyLarge)
                Text(c.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onNotAMistake != null) TextButton({ onNotAMistake(c) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("Not a mistake", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun Ornament() {
    // a short rule with a vermilion dot between passages
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(36.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Box(Modifier.padding(horizontal = 10.dp).size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.accent))
        Box(Modifier.width(36.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** The passage as reading text: every word tappable; tapped words marked; new cards with their meaning inline. */
@Composable
fun PassageText(p: ShownPassage, interactive: Boolean, isTapped: (Span) -> Boolean, statusOf: (Span) -> Status, tapVersion: Int, onTap: (Span) -> Unit) {
    // names are tappable too (for the reading); other ignored tokens (particles, numbers…) aren't
    val tappedBg = LocalAccents.current.tapped
    val glossColor = MaterialTheme.colorScheme.tertiary
    val unknownLine = MaterialTheme.colorScheme.accent
    val text = remember(p, tapVersion, interactive) {
        buildAnnotatedString {
            var pos = 0
            for (s in p.spans) {
                if (s.start > pos) append(p.text.substring(pos, s.start))
                val style = when {
                    interactive && isTapped(s) -> SpanStyle(background = tappedBg)
                    statusOf(s) == Status.UNKNOWN -> SpanStyle(textDecoration = TextDecoration.Underline, color = unknownLine)
                    else -> SpanStyle()
                }
                if (interactive && (s.isWord || statusOf(s) == Status.NAME)) withLink(LinkAnnotation.Clickable("w${s.start}") { onTap(s) }) { withStyle(style) { append(s.surface) } }
                else withStyle(style) { append(s.surface) }
                s.deck.firstOrNull { it.noteId in p.newNotes }?.let { w ->
                    withStyle(SpanStyle(fontSize = 12.sp, color = glossColor)) { append("〔${p.glosses[w.noteId].orEmpty()}〕") }
                }
                pos = s.end
            }
            if (pos < p.text.length) append(p.text.substring(pos))
        }
    }
    Text(text, style = readingStyle(), color = MaterialTheme.colorScheme.onBackground)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PassageTools(p: ShownPassage, enabled: Boolean, onReroll: () -> Unit, onEdit: () -> Unit, onGrammar: (GrammarPatterns.Found) -> Unit,
                  onVersion: (Int) -> Unit = {}, listening: Boolean = false, onListen: (() -> Unit)? = null) {
    var notes by remember { mutableStateOf(false) }
    // a quiet row of round tools; the passage is what matters
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onListen != null) Tool(if (listening) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp, if (listening) "Stop" else "Listen",
            active = listening, onClick = onListen)
        Tool(Icons.Filled.Refresh, "Reroll", enabled = enabled, onClick = onReroll)
        Tool(Icons.Filled.Edit, "Edit", enabled = enabled, onClick = onEdit)
        Tool(Icons.Filled.Casino, "Game master notes", active = notes) { notes = !notes }
        Spacer(Modifier.weight(1f))
        if (p.versions > 1) Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onVersion(p.version - 1) }, enabled = enabled && p.version > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous version")
            }
            Text("${p.version + 1}/${p.versions}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton({ onVersion(p.version + 1) }, enabled = enabled && p.version < p.versions - 1) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next version")
            }
        }
    }
    if (p.grammar.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (g in p.grammar.distinctBy { it.pattern.id }) {
            AssistChip({ onGrammar(g) }, { Text(g.pattern.id) }, border = null,
                leadingIcon = { Icon(Icons.Filled.AutoStories, null, Modifier.size(16.dp)) },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer, leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer))
        }
    }
    if (notes) PaperCard {
        Text("Game master notes", style = MaterialTheme.typography.titleSmall)
        (p.gmNotes + p.checks).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun ProposalCard(p: MistakeLog.Key, onDecide: (Boolean) -> Unit) {
    PaperCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(Icons.Filled.Spellcheck, MaterialTheme.colorScheme.accentContainer, MaterialTheme.colorScheme.onAccentContainer, 32.dp)
            Text("Same mistake twice: make a card?", style = MaterialTheme.typography.titleSmall)
        }
        Text(p.key, style = MaterialTheme.typography.bodyMedium)
        p.examples.lastOrNull()?.let { (_, c) -> Text("${c.wrong} → ${c.right}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ onDecide(true) }) { Text("Make a card") }
            TextButton({ onDecide(false) }) { Text("No") }
        }
    }
}

@Composable
fun ReplyBar(busy: String?, canSend: Boolean, input: String, onInput: (String) -> Unit, onSend: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.navigationBarsPadding()) {
            if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, onInput, Modifier.weight(1f).heightIn(min = 52.dp),
                    placeholder = { Text(busy ?: "日本語で返事…", maxLines = 1) }, shape = RoundedCornerShape(26.dp), maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(8.dp))
                if (input.isBlank()) {
                    // nothing typed: let the scene go on
                    FilledTonalButton({ onSend("") }, Modifier.heightIn(min = 52.dp), enabled = canSend && busy == null,
                        contentPadding = PaddingValues(start = 18.dp, end = 14.dp)) {
                        Text("Continue"); Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp))
                    }
                } else {
                    FilledIconButton({ onSend(input) }, Modifier.size(52.dp), enabled = canSend && busy == null,
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.accent, contentColor = MaterialTheme.colorScheme.onAccent)) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}

/** The whole reading screen, from data: used by the app and by the screenshot tests. */
@Composable
fun StoryContent(
    title: String, cost: Double, history: List<ShownTurn>, current: ShownTurn?, busy: String?, error: String?,
    proposals: List<MistakeLog.Key>, tapVersion: Int,
    isTapped: (Span) -> Boolean, statusOf: (Span) -> Status,
    onBack: () -> Unit, onTap: (Span, ShownPassage) -> Unit, onGrammar: (GrammarPatterns.Found) -> Unit,
    onReroll: () -> Unit, onEdit: () -> Unit, onSend: (String) -> Unit, onDecide: (String, Boolean) -> Unit,
    reply: String = "", onReply: (String) -> Unit = {}, onRetry: (() -> Unit)? = null, onVersion: (Int) -> Unit = {},
    /** A word in an earlier passage: looked up only, never graded. */
    onLookup: (Span, ShownPassage) -> Unit = { _, _ -> },
    /** Who you are in the story, shown above the first passage. */
    intro: String? = null,
    /** Why there are no review words (no AnkiDroid, nothing due…), shown above the reply box. */
    notice: String? = null,
    listening: Boolean = false,
    onListen: ((String) -> Unit)? = null,
    /** The passage streaming in while it's written: (phase, text). */
    draft: Pair<String, String>? = null,
    /** A correction you marked as not a mistake (hidden). */
    isDismissed: (String, Correction) -> Boolean = { _, _ -> false },
    onNotAMistake: (String, Correction) -> Unit = { _, _ -> },
    /** The message just sent, shown as a bubble while its turn is written. */
    sending: String? = null,
    /** Anki cards to answer while waiting, and what to do with an answer. */
    study: List<DueCard> = emptyList(),
    onAnswer: (DueCard, Boolean) -> Unit = { _, _ -> },
    onTriage: (DueCard, Boolean) -> Unit = { _, _ -> },
    onInfo: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(history.size, current) {
        val n = history.size + (if (current != null) 1 else 0) + proposals.size
        if (n > 0) listState.animateScrollToItem(n - 1)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        AppBar(title, onBack = onBack) {
            if (onInfo != null) IconButton(onInfo) { Icon(Icons.Outlined.Info, "The story so far") }
            Text("$%.2f".format(cost), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp))
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (intro != null && history.isEmpty()) item {
                PaperCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconTile(Icons.Filled.Person, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, 32.dp)
                        Text("You are", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(intro, style = MaterialTheme.typography.bodyLarge)
                    Text("Tap words you don't know.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(history) { t ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    t.player?.let { m -> PlayerBubble(m, t.corrections.filter { !isDismissed(m, it) }) { c -> onNotAMistake(m, c) } }
                    PassageText(t.passage, true, { false }, statusOf, tapVersion) { onLookup(it, t.passage) }
                    Ornament()
                }
            }
            if (current != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    current.player?.let { m -> PlayerBubble(m, current.corrections.filter { !isDismissed(m, it) }) { c -> onNotAMistake(m, c) } }
                    // a reroll streams its new version in place of the old one
                    if (busy != null && draft != null) DraftPassage(busy, draft.second)
                    else PassageText(current.passage, true, isTapped, statusOf, tapVersion) { onTap(it, current.passage) }
                    PassageTools(current.passage, busy == null, onReroll, onEdit, onGrammar, onVersion, listening,
                        onListen?.let { l -> { l(current.passage.text) } })
                    // sent while this passage is still being recorded: the bubble shows already
                    if (busy != null && sending != null) PlayerBubble(sending, emptyList())
                }
            } else if (busy != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // your message, at once, above what's being written for it
                    sending?.let { PlayerBubble(it, emptyList()) }
                    if (draft != null) DraftPassage(busy, draft.second)
                    else WaitPanel(busy, study, onAnswer, onTriage)
                }
            }
            if (error != null) item { ErrorNote(error, onRetry) }
            items(proposals) { p -> ProposalCard(p) { onDecide(p.key, it) } }
        }
        if (notice != null && busy == null) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReplyBar(busy, current != null, reply, onReply, onSend)
    }
}

data class WordInfo(val word: String, val reading: String?, val inSentence: String?, val senses: List<String>, val inDeck: Boolean,
                    val isName: Boolean = false,
                    /** Looked up in an earlier passage: not graded, nothing to cancel. */
                    val earlier: Boolean = false,
                    /** The meaning on your own card, for deck words. */
                    val card: String? = null,
                    /** A new card: offer "Already know it" (skip it; would be suspended in Anki). */
                    val newCard: Boolean = false)

/** How a name is read, from the tokenizer, in hiragana; null when it's already all kana. */
fun nameReading(surface: String): String? {
    if (surface.none { it in '\u4E00'..'\u9FFF' || it == '々' }) return null
    val r = com.cramtale.core.text.Tokenizer.tokenize(surface).joinToString("") { it.reading.ifBlank { it.surface } }
    return r.map { if (it in '\u30A1'..'\u30F6') it - 0x60 else it }.joinToString("").takeIf { it != surface && it.isNotBlank() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordSheet(info: WordInfo, onKnow: () -> Unit, onClose: () -> Unit, onSay: (() -> Unit)? = null, onKnownNew: () -> Unit = {}) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        WordSheetBody(info, onKnow, onClose, onSay, onKnownNew)
    }
}

@Composable
fun WordSheetBody(info: WordInfo, onKnow: () -> Unit, onClose: () -> Unit, onSay: (() -> Unit)? = null, onKnownNew: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // what this word is to you, first: it decides what the buttons do
        val (status, colors) = when {
            info.isName -> "Name · not graded" to (cs.surfaceContainerHigh to cs.onSurfaceVariant)
            info.newCard -> "New card" to (cs.accentContainer to cs.onAccentContainer)
            info.earlier && info.inDeck -> "In your deck · lookup only" to (cs.surfaceContainerHigh to cs.onSurfaceVariant)
            info.inDeck -> "In your deck · this tap = Again" to (cs.accentContainer to cs.onAccentContainer)
            else -> "Not in your decks" to (cs.tertiaryContainer to cs.onTertiaryContainer)
        }
        Pill(status, colors.first, colors.second)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                info.reading?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant) }
                Text(info.word, style = WordText.copy(fontSize = 44.sp, lineHeight = 52.sp))
            }
            if (onSay != null) androidx.compose.material3.FilledTonalIconButton(onSay, Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, "Say it")
            }
        }
        Surface(color = cs.primaryContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Here", style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer.copy(alpha = 0.7f))
                Text(info.inSentence ?: "…", style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
            }
        }
        info.card?.let {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Your card", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (info.senses.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Dictionary", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            info.senses.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
        }
        if (info.newCard) Text("Know it already? It's skipped from now on (and would be suspended in Anki once the app may write).",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                info.newCard -> {
                    SecondaryButton("Close", onClose, Modifier.weight(1f))
                    PrimaryButton("Already know it", onKnownNew, Modifier.weight(1f), icon = Icons.Filled.Check)
                }
                info.isName || (info.earlier && info.inDeck) -> PrimaryButton("Close", onClose, Modifier.weight(1f))
                info.inDeck -> {
                    SecondaryButton("Close", onClose, Modifier.weight(1f))
                    // a mis-tap: undoes the Again
                    PrimaryButton("Mis-tap, I know it", onKnow, Modifier.weight(1f))
                }
                else -> {
                    SecondaryButton("Close", onClose, Modifier.weight(1f))
                    PrimaryButton("I know it", onKnow, Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- the screen

@Composable
fun StoryScreen(state: AppState, onBack: () -> Unit) {
    val story = state.story
    // once: the notification shown while a passage is written in the background (Android 13+ asks)
    val context = androidx.compose.ui.platform.LocalContext.current
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotify", false) &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("askedNotify", true).apply()
            askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var picked by remember { mutableStateOf<Pair<Span, ShownPassage>?>(null) }
    var lookupOnly by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<StoryController.StoryInfo?>(null) }
    var role by remember { mutableStateOf<String?>(null) }
    var infoWanted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { role = withContext(Dispatchers.IO) { story.role() } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { state.speaker.stop() } }
    LaunchedEffect(infoWanted) { if (infoWanted) { info = withContext(Dispatchers.IO) { story.storyInfo() }; infoWanted = false } }
    var grammar by remember { mutableStateOf<GrammarPatterns.Found?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var tapVersion by remember { mutableIntStateOf(0) }
    // start() is safe to repeat: it shows the waiting passage, finishes a half-done turn, or answers an
    // unsent reply; an error left over from elsewhere (setup, styles) must not keep the story from opening
    LaunchedEffect(Unit) { if (story.current == null && !story.isWorking) story.launch { start() } }

    StoryContent(
        title = story.title ?: "Story", cost = story.sessionCost(), history = story.history, current = story.current,
        busy = story.busy, error = story.error, proposals = story.proposals, tapVersion = tapVersion,
        isTapped = story::isTapped,
        statusOf = { if (story.isName(it)) Status.NAME else it.status(state.policy, state.lists) },
        onBack = onBack,
        // while the passage is being committed or rewritten, a tap is a lookup (it can't count any more)
        onTap = { s, p -> val counts = !story.isWorking; story.tap(s); tapVersion++; lookupOnly = !counts; picked = s to p },
        onGrammar = { grammar = it },
        onReroll = { story.launch { reroll() } },
        onEdit = { editing = story.current?.passage?.text },
        onSend = { m -> story.submit(m) },
        onDecide = story::decideProposal,
        reply = story.replyDraft, onReply = { story.replyDraft = it }, onRetry = story.retry,
        onVersion = { story.selectVersion(it); tapVersion++ },
        onLookup = { s, p -> lookupOnly = true; picked = s to p },
        intro = role, onInfo = { infoWanted = true },
        notice = state.speaker.problem() ?: story.ankiNote,
        listening = state.speaker.speaking,
        draft = story.draft, study = story.study, sending = story.sending,
        isDismissed = { m, c -> story.correctionId(m, c) in story.dismissed }, onNotAMistake = { m, c -> story.notAMistake(m, c) },
        onAnswer = { c, good -> story.answerFlashcard(c, good) }, onTriage = { c, known -> story.triageNew(c, known) },
        onListen = { text -> if (state.speaker.speaking) state.speaker.stop() else state.speaker.say(text) },
    )
    info?.let { i -> StoryInfoSheet(i) { info = null } }

    picked?.let { (span, p) ->
        val sentence = remember(span, p) { sentenceAround(p.text, span.start, span.end) }
        var meaning by remember(span) { mutableStateOf<String?>(null) }
        LaunchedEffect(span) { meaning = story.meaning(span, sentence) { partial -> meaning = partial } ?: meaning }
        // dictionary lookups off the main thread (the dictionary may be busy right after launch)
        var info by remember(span) { mutableStateOf(WordInfo(span.surface, null, null, emptyList(), span.deck.isNotEmpty(), story.isName(span), lookupOnly)) }
        LaunchedEffect(span, meaning) {
            info = withContext(Dispatchers.IO) {
                val dict = state.jmdict()
                if (story.isName(span)) WordInfo(span.surface, nameReading(span.surface), meaning, emptyList(), false, isName = true)
                else WordInfo(span.lemma, span.entries.firstOrNull()?.let { dict?.forms(it)?.kana?.firstOrNull() }, meaning,
                    span.entries.take(2).flatMap { e -> dict?.senses(e).orEmpty().take(3).map { it.gloss } }, span.deck.isNotEmpty(),
                    earlier = lookupOnly, card = span.deck.firstNotNullOfOrNull { it.meaning },
                    newCard = story.newCardsIn(span).isNotEmpty())
            }
        }
        WordSheet(info, onKnow = { story.iKnowIt(span); tapVersion++; picked = null }, onClose = { picked = null },
            onSay = { state.speaker.say(span.surface, rate = 0.8f) },
            onKnownNew = { story.alreadyKnow(story.newCardsIn(span)); tapVersion++; picked = null })
    }
    grammar?.let { g -> GrammarSheet(g, story.current?.passage?.text.orEmpty()) { grammar = null } }
    editing?.let { text ->
        var t by remember { mutableStateOf(text) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Edit the passage") },
            text = { OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth().heightIn(min = 220.dp), textStyle = ReadingText.copy(fontSize = 16.sp, lineHeight = 26.sp)) },
            confirmButton = { TextButton({ editing = null; story.launch { edit(t) } }) { Text("Save") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } })
    }
}

fun sentenceAround(text: String, start: Int, end: Int): String {
    val s = text.lastIndexOfAny(charArrayOf('。', '！', '？', '\n', '」'), start - 1).let { if (it < 0) 0 else it + 1 }
    val e = text.indexOfAny(charArrayOf('。', '！', '？', '\n'), end).let { if (it < 0) text.length else it + 1 }
    return text.substring(s, e).trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryInfoSheet(info: StoryController.StoryInfo, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        StoryInfoBody(info)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoryInfoBody(info: StoryController.StoryInfo) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("The story so far", style = MaterialTheme.typography.titleLarge)
        info.role?.let { sheet ->
            Text("You", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.accent)
            // the sheet's own headings (What you know, Established in the story) as small labels
            sheet.lines().filter { it.isNotBlank() }.forEach { l ->
                if (l.startsWith("## ")) Text(l.removePrefix("## "), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                else Text(l, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (info.remembers.isNotEmpty()) {
            Text("What you remember", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.accent)
            info.remembers.take(30).forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
        }
        if (info.met.isNotEmpty()) {
            Text("People you've met", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.accent)
            Text(info.met.joinToString("、"), style = readingStyle().copy(fontSize = 18.sp, lineHeight = 28.sp))
        }
        if (info.today.isNotEmpty()) {
            Text("Read today · ${info.today.count { it.second }} Good · ${info.today.count { !it.second }} Again",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.accent)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                info.today.forEach { (w, good) ->
                    Pill(w, if (good) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.accentContainer,
                        if (good) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onAccentContainer)
                }
            }
            Text("Would-be grades: this build doesn't answer cards in Anki yet.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Chapters", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.accent)
        if (info.chapters.isEmpty()) Text("Chapter 1 is still being written. A summary appears here when it closes.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        info.chapters.forEach { (n, text) ->
            Text("Chapter $n", style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The passage while it's being written: readable at once, muted until it's checked and tappable. */
@Composable
fun DraftPassage(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text, style = readingStyle(), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
    }
}

/** A wait with nothing to stream: the step that's running, and Anki cards to answer meanwhile. */
@Composable
fun WaitPanel(label: String, study: List<DueCard>, onAnswer: (DueCard, Boolean) -> Unit, onTriage: (DueCard, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Busy(label)
        study.firstOrNull()?.let { card -> Flashcard(card, study.size, onAnswer, onTriage) }
    }
}

@Composable
fun Flashcard(card: DueCard, left: Int, onAnswer: (DueCard, Boolean) -> Unit, onTriage: (DueCard, Boolean) -> Unit) {
    var shown by remember(card.ref) { mutableStateOf(false) }
    PaperCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Style, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.accent)
            Spacer(Modifier.width(6.dp))
            Text(if (card.isNew) "New card, meanwhile" else "Review, meanwhile", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.accent, modifier = Modifier.weight(1f))
            Text("$left left", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (shown) (card.word.reading ?: nameReading(card.word.word)).orEmpty() else " ",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(card.word.word, style = WordText.copy(fontSize = 44.sp, lineHeight = 52.sp))
            if (shown) Text(card.word.meaning ?: "(no meaning on the card)", style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                !shown -> androidx.compose.material3.FilledTonalButton({ shown = true }, Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Show") }
                card.isNew -> {
                    SecondaryButton("For the story", { onTriage(card, false) }, Modifier.weight(1f))
                    PrimaryButton("Already know it", { onTriage(card, true) }, Modifier.weight(1f))
                }
                else -> {
                    SecondaryButton("Again", { onAnswer(card, false) }, Modifier.weight(1f))
                    PrimaryButton("Good", { onAnswer(card, true) }, Modifier.weight(1f))
                }
            }
        }
    }
}

/** A round tool button under the passage. */
@Composable
private fun Tool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    androidx.compose.material3.FilledTonalIconButton(onClick, enabled = enabled,
        colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)) {
        Icon(icon, label, Modifier.size(20.dp))
    }
}
