package com.cramtale.app.ui

import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lightbulb
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
import androidx.compose.material.icons.filled.MoreHoriz
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
        // tonal, not filled: your line frames the passage, the passage is what you read
        Surface(color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer)
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
                if (c.repeat) Text(if (c.level == Correction.LEVEL_ERROR) "You've made this mistake before." else "You've written this before; shown because it came up again.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.accent)
                else if (c.level != Correction.LEVEL_ERROR) Text(if (c.level == "unnatural") "Correct, but not how people say it" else "A small polish",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onNotAMistake != null) TextButton({ onNotAMistake(c) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("Not a mistake", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** One die, drawn: its pips on a rounded face. */
@Composable
fun DieFace(value: Int, size: androidx.compose.ui.unit.Dp, face: Color, pip: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(face, cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.22f))
        drawRoundRect(pip.copy(alpha = 0.35f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.22f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = s * 0.04f))
        val r = s * 0.085f
        val a = s * 0.27f; val m = s * 0.5f; val b = s * 0.73f
        val spots = when (value) {
            1 -> listOf(m to m)
            2 -> listOf(a to a, b to b)
            3 -> listOf(a to a, m to m, b to b)
            4 -> listOf(a to a, b to a, a to b, b to b)
            5 -> listOf(a to a, b to a, m to m, a to b, b to b)
            else -> listOf(a to a, b to a, a to m, b to m, a to b, b to b)
        }
        for ((x, y) in spots) drawCircle(pip, r, androidx.compose.ui.geometry.Offset(x, y))
    }
}

/**
 * A roll as it lands in the story: the two dice, what they mean (in Japanese, with the English), and what was
 * tried. Tapping it explains how rolls work.
 */
@Composable
fun RollLine(roll: com.cramtale.app.story.RollUi) {
    val cs = MaterialTheme.colorScheme
    val (tone, ja, en) = when (roll.outcome) {
        com.cramtale.core.story.Dice.Outcome.SUCCESS -> Triple(cs.tertiary, "成功", "success")
        com.cramtale.core.story.Dice.Outcome.COST -> Triple(cs.accent, "代償つきの成功", "success, at a cost")
        com.cramtale.core.story.Dice.Outcome.FAILURE -> Triple(cs.error, "失敗", "failure")
    }
    var explain by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { explain = true }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            roll.dice.forEach { DieFace(it, 28.dp, cs.onBackground.copy(alpha = 0.9f), cs.background) }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ja, style = ReadingText.copy(fontSize = 17.sp, lineHeight = 22.sp), color = tone)
                Text(en, style = MaterialTheme.typography.labelMedium, color = tone)
            }
            roll.action?.let { Text("You tried to: $it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
        // tap for how rolls work
        Icon(Icons.Outlined.Info, "How rolls work", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
    }
    if (explain) AlertDialog(onDismissRequest = { explain = false }, title = { Text("How rolls work") },
        text = { Text("When what you try is risky, two dice decide, not the narrator. Harder moves take points off" +
            (if (roll.modifier != 0) " (this one: %+d, total %d)".format(roll.modifier, roll.total) else "") + ".\n\n" +
            "10 or more: it works (成功).\n7 to 9: it works, at a cost (代償つきの成功).\n6 or less: it fails (失敗), and that costs something real.") },
        confirmButton = { TextButton({ explain = false }) { Text("Got it") } })
}

/** How reading counts, and what the marks in the text are: shown until the first tap. */
@Composable
fun TeachTaps(onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your Anki words are in the story", style = MaterialTheme.typography.titleSmall, color = cs.onSecondaryContainer)
            Text("Read past a word you know: it counts as Good. Don't know one? Tap it: you see its meaning, and it counts as Again.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
            Text("Your due words look like the rest of the text. Underlines mark only words that are new to you.",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = cs.onSecondaryContainer)
            MarkKey(SpanStyle(textDecoration = TextDecoration.Underline, color = cs.primary), "箇所", "a new card: tap it for its meaning")
            MarkKey(SpanStyle(textDecoration = TextDecoration.Underline, color = cs.accent), "返済", "probably new to you, not in your decks")
            MarkKey(SpanStyle(background = LocalAccents.current.tapped), "布", "a word you tapped")
            TextButton(onDone, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Got it") }
        }
    }
}

@Composable
private fun MarkKey(style: SpanStyle, sample: String, meaning: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(buildAnnotatedString { withStyle(style) { append(sample) } }, style = ReadingText.copy(fontSize = 17.sp, lineHeight = 24.sp),
            color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.width(96.dp))
        Text(meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
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
    // new cards in the theme's own colour: green means "Good" elsewhere in the app
    val glossColor = MaterialTheme.colorScheme.primary
    val unknownLine = MaterialTheme.colorScheme.accent
    val text = remember(p, tapVersion, interactive) {
        buildAnnotatedString {
            var pos = 0
            for (s in p.spans) {
                if (s.start > pos) append(p.text.substring(pos, s.start))
                // a new card is marked, not glossed: the story text stays Japanese; its meaning is a tap away
                val isNew = s.deck.any { it.noteId in p.newNotes }
                val style = when {
                    interactive && isTapped(s) -> SpanStyle(background = tappedBg)
                    isNew -> SpanStyle(textDecoration = TextDecoration.Underline, color = glossColor)
                    statusOf(s) == Status.UNKNOWN -> SpanStyle(textDecoration = TextDecoration.Underline, color = unknownLine)
                    else -> SpanStyle()
                }
                if (interactive && (s.isWord || statusOf(s) == Status.NAME)) withLink(LinkAnnotation.Clickable("w${s.start}") { onTap(s) }) { withStyle(style) { append(s.surface) } }
                else withStyle(style) { append(s.surface) }
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
    var more by remember { mutableStateOf(false) }
    // a quiet row of round tools; the passage is what matters. Listen and Rewrite are the everyday ones;
    // editing the text and the game master's notes (spoilers) wait under More
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onListen != null) Tool(if (listening) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp, if (listening) "Stop" else "Listen",
            active = listening, onClick = onListen)
        Tool(Icons.Filled.Refresh, "Rewrite", enabled = enabled, onClick = onReroll)
        Box {
            Tool(Icons.Filled.MoreHoriz, "More", active = notes) { more = true }
            androidx.compose.material3.DropdownMenu(more, { more = false }) {
                androidx.compose.material3.DropdownMenuItem({ Text("Edit the text") }, { more = false; onEdit() }, enabled = enabled,
                    leadingIcon = { Icon(Icons.Filled.Edit, null) })
                androidx.compose.material3.DropdownMenuItem({ Text(if (notes) "Hide the game master's notes" else "Game master's notes (spoilers)") },
                    { more = false; notes = !notes }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, null) })
            }
        }
    }
    // versions on their own line: the row above stays the same wherever you are
    if (p.versions > 1) Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onVersion(p.version - 1) }, enabled = enabled && p.version > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous version")
            }
            Text("Version ${p.version + 1} of ${p.versions}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton({ onVersion(p.version + 1) }, enabled = enabled && p.version < p.versions - 1) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next version")
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
        Text("Game master's notes: dice, plans and checks", style = MaterialTheme.typography.titleSmall)
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
fun ReplyBar(busy: String?, canSend: Boolean, input: String, onInput: (String) -> Unit, onSend: (String) -> Unit,
             ideas: IdeasUi? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MAX_CONTENT_WIDTH + 24.dp).navigationBarsPadding()) {
            if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            // ideas sit on the reply box: lines to start from when writing Japanese is the hard part
            if (ideas != null && canSend && busy == null && input.isBlank()) IdeasArea(ideas) { onInput(it) }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, onInput, Modifier.weight(1f).heightIn(min = 52.dp),
                    placeholder = { Text(when { busy != null -> "Wait a moment…"; !canSend -> "Write after the passage appears"; else -> "Say or do something…" }, maxLines = 1) }, shape = RoundedCornerShape(26.dp), maxLines = 5,
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
                        Text("Go on"); Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp))
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
}

/** What the reply bar needs to show ideas: the three (or none yet), whether they're coming, why they didn't. */
data class IdeasUi(
    val ideas: List<com.cramtale.core.story.StoryEngine.Idea>,
    val loading: Boolean,
    /** null: nothing to say; "": came back empty; else why it failed. */
    val problem: String?,
    val onAsk: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * The Ideas button, or the three ideas: each a line to say or do in the story's reading face with its English
 * meaning under it. Tapping one puts the Japanese in the reply box, to edit before sending.
 */
@Composable
fun IdeasArea(ui: IdeasUi, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (ui.ideas.isEmpty()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // full contrast while loading too: busy, not unavailable
            androidx.compose.material3.FilledTonalButton({ if (!ui.loading) ui.onAsk() }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.heightIn(min = 36.dp)) {
                if (ui.loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(if (ui.problem != null) Icons.Filled.Refresh else Icons.Outlined.Lightbulb, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(when { ui.loading -> "Finding ideas…"; ui.problem != null -> "Try again"; else -> "Ideas" }, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(10.dp))
            when {
                ui.problem == "" -> Text("No ideas came back. Try again.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                ui.problem != null -> Text(ui.problem, style = MaterialTheme.typography.bodySmall, color = cs.error, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                !ui.loading -> Text("Stuck? Three things you could say or do.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp).clip(MaterialTheme.shapes.medium).background(cs.secondaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tap one, then edit it", style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f))
            TextButton(ui.onAsk, enabled = !ui.loading) { Text(if (ui.loading) "Finding…" else "New ideas") }
            IconButton(ui.onDismiss) { Icon(Icons.Filled.Close, "Close ideas", tint = cs.onSecondaryContainer) }
        }
        ui.ideas.forEachIndexed { i, idea ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.onSecondaryContainer.copy(alpha = 0.12f))
            Column(Modifier.fillMaxWidth().clickable { onPick(idea.ja) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(idea.ja, style = ReadingText.copy(fontSize = 18.sp, lineHeight = 28.sp), color = cs.onSecondaryContainer)
                if (idea.en.isNotBlank()) Text(idea.en, style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer.copy(alpha = 0.75f))
            }
        }
        Spacer(Modifier.height(6.dp))
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
    /** Show how tapping counts (until the first tap or "Got it"). */
    teach: Boolean = false,
    onTaught: () -> Unit = {},
    ideas: IdeasUi? = null,
) {
    val listState = rememberLazyListState()
    // the ideas take room at the bottom: the passage's last lines stay in view above them
    LaunchedEffect(ideas?.ideas?.size) {
        val n = history.size + (if (current != null) 1 else 0) + proposals.size
        if ((ideas?.ideas?.size ?: 0) > 0 && n > 0) listState.animateScrollToItem(n - 1, 100_000)
    }
    LaunchedEffect(history.size, current) {
        val n = history.size + (if (current != null) 1 else 0) + proposals.size
        if (n > 0) listState.animateScrollToItem(n - 1)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        AppBar(title, onBack = onBack) {
            if (onInfo != null) IconButton(onInfo) { Icon(Icons.Outlined.Info, "The story so far") }
        }
        // a readable measure on wide screens: the side padding grows instead of the lines
        val side = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.let { w -> maxOf(22.dp, (w - MAX_CONTENT_WIDTH) / 2) }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = side, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (intro != null && history.isEmpty()) item {
                PaperCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconTile(Icons.Filled.Person, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, 32.dp)
                        Text("You are", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(intro, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (teach && current != null) item { TeachTaps(onTaught) }
            items(history) { t ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    t.player?.let { m -> PlayerBubble(m, t.corrections.filter { !isDismissed(m, it) }) { c -> onNotAMistake(m, c) } }
                    t.passage.roll?.let { RollLine(it) }
                    PassageText(t.passage, true, { false }, statusOf, tapVersion) { onLookup(it, t.passage) }
                    Ornament()
                }
            }
            if (current != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    current.player?.let { m -> PlayerBubble(m, current.corrections.filter { !isDismissed(m, it) }) { c -> onNotAMistake(m, c) } }
                    current.passage.roll?.let { RollLine(it) }
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
        ReplyBar(busy, current != null, reply, onReply, onSend, ideas)
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
            info.inDeck -> "In your deck · counts as Again" to (cs.accentContainer to cs.onAccentContainer)
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
        if (info.newCard) Text("Already know it? The story stops teaching it to you.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                info.newCard -> {
                    SecondaryButton("Already know it", onKnownNew, Modifier.weight(1f))
                    PrimaryButton("Close", onClose, Modifier.weight(1f))
                }
                info.isName || (info.earlier && info.inDeck) -> PrimaryButton("Close", onClose, Modifier.weight(1f))
                info.inDeck -> {
                    // closing is the normal end of a lookup; "I knew it" undoes a mis-tap's Again
                    SecondaryButton("I knew it", onKnow, Modifier.weight(1f))
                    PrimaryButton("Close", onClose, Modifier.weight(1f))
                }
                else -> {
                    SecondaryButton("I know it", onKnow, Modifier.weight(1f))
                    PrimaryButton("Close", onClose, Modifier.weight(1f))
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
    var editing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
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
        onTap = { s, p -> val counts = !story.isWorking; story.tap(s); tapVersion++; lookupOnly = !counts; picked = s to p; state.markTaught() },
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
        teach = !state.taughtTaps, onTaught = { state.markTaught() },
        ideas = IdeasUi(story.ideas, story.ideasLoading, story.ideasProblem, onAsk = { story.suggest() }, onDismiss = { story.dismissIdeas() }),
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
        var t by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(text) }
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
                    Pill(w, if (good) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.accentContainer,
                        if (good) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onAccentContainer)
                }
            }
            Text("Practice only: these aren't sent to Anki yet. Review your cards in AnkiDroid as usual.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (info.spent > 0) Text("Claude so far: $%.2f this session, $%.2f in all".format(info.spentSession, info.spent),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(text, style = readingStyle(), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f))
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
            Text(if (card.isNew) "New card while you wait" else "Quick review while you wait", style = MaterialTheme.typography.labelMedium,
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
                    SecondaryButton("Learn it in the story", { onTriage(card, false) }, Modifier.weight(1f))
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
    // icon and its name: nobody has to guess what a die or a pencil does here
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.FilledTonalIconButton(onClick, enabled = enabled,
            colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)) {
            Icon(icon, null, Modifier.size(20.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f))
    }
}
