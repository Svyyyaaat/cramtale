package com.cramtale.app.ui

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.cramtale.app.story.StoryController
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
fun PlayerBubble(text: String, corrections: List<Correction>) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.widthIn(max = 320.dp)) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        // corrections beside your message
        for (c in corrections) Column(Modifier.widthIn(max = 320.dp), horizontalAlignment = Alignment.End) {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.error)) { append(c.wrong) }
                append("  →  ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)) { append(c.right) }
            }, style = MaterialTheme.typography.bodyLarge)
            Text(c.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
    }
}

@Composable
fun Ornament() {
    Text("◇", Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.labelMedium)
}

/** The passage as reading text: every word tappable; tapped words marked; new cards with their meaning inline. */
@Composable
fun PassageText(p: ShownPassage, interactive: Boolean, isTapped: (Span) -> Boolean, statusOf: (Span) -> Status, tapVersion: Int, onTap: (Span) -> Unit) {
    // names are tappable too (for the reading); other ignored tokens (particles, numbers…) aren't
    val tappedBg = MaterialTheme.colorScheme.secondaryContainer
    val glossColor = MaterialTheme.colorScheme.tertiary
    val unknownLine = MaterialTheme.colorScheme.secondary
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
                  onVersion: (Int) -> Unit = {}) {
    var notes by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
        if (p.versions > 1) Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onVersion(p.version - 1) }, enabled = enabled && p.version > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous version")
            }
            Text("${p.version + 1} / ${p.versions}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton({ onVersion(p.version + 1) }, enabled = enabled && p.version < p.versions - 1) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next version")
            }
        }
        AssistChip(onReroll, { Text("Reroll") }, enabled = enabled, leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) })
        AssistChip(onEdit, { Text("Edit") }, enabled = enabled, leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)) })
        AssistChip({ notes = !notes }, { Text("Notes") }, leadingIcon = { Icon(Icons.Filled.Casino, null, Modifier.size(18.dp)) })
        for (g in p.grammar.distinctBy { it.pattern.id }) {
            AssistChip({ onGrammar(g) }, { Text(g.pattern.id) },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, labelColor = MaterialTheme.colorScheme.onTertiaryContainer))
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
        Text("You've made this mistake twice", style = MaterialTheme.typography.titleSmall)
        Text(p.key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        p.examples.lastOrNull()?.let { (_, c) -> Text("${c.wrong} → ${c.right}", style = MaterialTheme.typography.bodyMedium) }
        Text("Make a card from your corrected sentence? (Cards are created from the next version on; this records your choice.)",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    placeholder = { Text(busy ?: "日本語で返事…") }, shape = RoundedCornerShape(26.dp), maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest))
                Spacer(Modifier.width(8.dp))
                if (input.isBlank()) {
                    FilledTonalButton({ onSend("") }, enabled = canSend && busy == null, contentPadding = PaddingValues(horizontal = 18.dp)) { Text("Continue") }
                } else {
                    FilledIconButton({ onSend(input) }, Modifier.size(52.dp), enabled = canSend && busy == null) {
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
                    Text("You are", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Text(intro, style = MaterialTheme.typography.bodyLarge)
                    Text("Tap any word you don't know. Reply in Japanese, or press Continue to let the scene go on.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(history) { t ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    t.player?.let { PlayerBubble(it, t.corrections) }
                    PassageText(t.passage, true, { false }, statusOf, tapVersion) { onLookup(it, t.passage) }
                    Ornament()
                }
            }
            if (current != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    current.player?.let { PlayerBubble(it, current.corrections) }
                    PassageText(current.passage, true, isTapped, statusOf, tapVersion) { onTap(it, current.passage) }
                    PassageTools(current.passage, busy == null, onReroll, onEdit, onGrammar, onVersion)
                }
            } else if (busy != null) item { Busy(busy) }
            if (error != null) item { ErrorNote(error, onRetry) }
            items(proposals) { p -> ProposalCard(p) { onDecide(p.key, it) } }
        }
        if (notice != null && busy == null) Text(notice, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReplyBar(busy, current != null, reply, onReply, onSend)
    }
}

data class WordInfo(val word: String, val reading: String?, val inSentence: String?, val senses: List<String>, val inDeck: Boolean,
                    val isName: Boolean = false,
                    /** Looked up in an earlier passage: not graded, nothing to cancel. */
                    val earlier: Boolean = false,
                    /** The meaning on your own card, for deck words. */
                    val card: String? = null)

/** How a name is read, from the tokenizer, in hiragana; null when it's already all kana. */
fun nameReading(surface: String): String? {
    if (surface.none { it in '\u4E00'..'\u9FFF' || it == '々' }) return null
    val r = com.cramtale.core.text.Tokenizer.tokenize(surface).joinToString("") { it.reading.ifBlank { it.surface } }
    return r.map { if (it in '\u30A1'..'\u30F6') it - 0x60 else it }.joinToString("").takeIf { it != surface && it.isNotBlank() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordSheet(info: WordInfo, onKnow: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        WordSheetBody(info, onKnow, onClose)
    }
}

@Composable
fun WordSheetBody(info: WordInfo, onKnow: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(info.word, style = WordText.copy(fontSize = 40.sp))
            info.reading?.let { Text("  $it", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)) }
        }
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("In this sentence", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(info.inSentence ?: "…", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        info.card?.let {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Your card", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (info.senses.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Dictionary", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            info.senses.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(when {
                info.isName -> "A name: not graded, and no card is made."
                info.earlier && info.inDeck -> "In your deck. Looking words up in earlier passages doesn't count against them."
                info.inDeck -> "In your deck: this tap counts as Again. If it was a mis-tap, say you know it."
                else -> "Not in your deck. From the next version, unknown words go to your RP deck."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (info.isName || (info.earlier && info.inDeck)) PrimaryButton("Close", onClose, Modifier.weight(1f))
            else {
                SecondaryButton("Close", onClose, Modifier.weight(1f))
                PrimaryButton("I know it", onKnow, Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------- the screen

@Composable
fun StoryScreen(state: AppState, onBack: () -> Unit) {
    val story = state.story
    var picked by remember { mutableStateOf<Pair<Span, ShownPassage>?>(null) }
    var lookupOnly by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<StoryController.StoryInfo?>(null) }
    var role by remember { mutableStateOf<String?>(null) }
    var infoWanted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { role = withContext(Dispatchers.IO) { story.role() } }
    LaunchedEffect(infoWanted) { if (infoWanted) { info = withContext(Dispatchers.IO) { story.storyInfo() }; infoWanted = false } }
    var grammar by remember { mutableStateOf<GrammarPatterns.Found?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var tapVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { if (story.current == null && story.busy == null && story.error == null) story.launch { start() } }

    StoryContent(
        title = story.title ?: "Story", cost = story.sessionCost(), history = story.history, current = story.current,
        busy = story.busy, error = story.error, proposals = story.proposals, tapVersion = tapVersion,
        isTapped = story::isTapped,
        statusOf = { if (story.isName(it)) Status.NAME else it.status(state.policy, state.lists) },
        onBack = onBack,
        onTap = { s, p -> story.tap(s); tapVersion++; lookupOnly = false; picked = s to p },
        onGrammar = { grammar = it },
        onReroll = { story.launch { reroll() } },
        onEdit = { editing = story.current?.passage?.text },
        onSend = { m -> story.launch { send(m) } },
        onDecide = story::decideProposal,
        reply = story.replyDraft, onReply = { story.replyDraft = it }, onRetry = story.retry,
        onVersion = { story.selectVersion(it); tapVersion++ },
        onLookup = { s, p -> lookupOnly = true; picked = s to p },
        intro = role, onInfo = { infoWanted = true }, notice = story.ankiNote,
    )
    info?.let { i -> StoryInfoSheet(i) { info = null } }

    picked?.let { (span, p) ->
        val sentence = remember(span, p) { sentenceAround(p.text, span.start, span.end) }
        var meaning by remember(span) { mutableStateOf<String?>(null) }
        LaunchedEffect(span) { meaning = story.meaning(span, sentence) }
        val dict = state.jmdict()
        val info = remember(span, meaning) {
            if (story.isName(span)) WordInfo(span.surface, nameReading(span.surface), meaning, emptyList(), false, isName = true)
            else WordInfo(span.lemma, span.entries.firstOrNull()?.let { dict?.forms(it)?.kana?.firstOrNull() }, meaning,
                span.entries.take(2).flatMap { e -> dict?.senses(e).orEmpty().take(3).map { it.gloss } }, span.deck.isNotEmpty(),
                earlier = lookupOnly, card = span.deck.firstNotNullOfOrNull { it.meaning })
        }
        WordSheet(info, onKnow = { story.iKnowIt(span); tapVersion++; picked = null }, onClose = { picked = null })
    }
    grammar?.let { g ->
        AlertDialog(onDismissRequest = { grammar = null }, title = { Text(g.pattern.id, style = WordText.copy(fontSize = 28.sp)) },
            text = { Text(g.pattern.meaning, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton({ grammar = null }) { Text("Close") } })
    }
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

@Composable
fun StoryInfoBody(info: StoryController.StoryInfo) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("The story so far", style = MaterialTheme.typography.titleLarge)
        info.role?.let {
            Text("You", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }
        if (info.met.isNotEmpty()) {
            Text("People you've met", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Text(info.met.joinToString("、"), style = readingStyle().copy(fontSize = 18.sp, lineHeight = 28.sp))
        }
        Text("Chapters", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        if (info.chapters.isEmpty()) Text("Chapter 1 is still being written. A summary appears here when it closes.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        info.chapters.forEach { (n, text) ->
            Text("Chapter $n", style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
