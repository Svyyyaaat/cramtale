package com.cramtale.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cramtale.app.AppState
import com.cramtale.core.match.Analysis
import com.cramtale.core.match.Calibration
import com.cramtale.core.match.KnownLists
import com.cramtale.core.match.KnownPolicy
import com.cramtale.core.match.Span
import com.cramtale.core.match.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SAMPLES = listOf(
    "Debt" to "samples/modern_debt.txt",
    "Café" to "samples/modern_cafe.txt",
    "Fire" to "samples/modern_fire.txt",
    "蜘蛛の糸" to "samples/aozora_kumono_ito.txt",
)

/** Paste text, see what counts as unknown, mark what you know, move the cut-off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalibrateScreen(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var analysis by remember { mutableStateOf<Analysis?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var policy by remember { mutableStateOf(state.policy) }
    var lists by remember { mutableStateOf(state.lists) }
    var picked by remember { mutableStateOf<Span?>(null) }

    Page {
        Text("Words in red count as unknown, orange ones are in your deck but not studied yet. Tap a word to mark it.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, path) in SAMPLES) AssistChip({ input = context.assets.open(path).bufferedReader().readText() }, { Text(name) })
        }
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), shape = MaterialTheme.shapes.medium,
            label = { Text("Japanese text") }, textStyle = ReadingText.copy(fontSize = 16.sp, lineHeight = 26.sp))
        PrimaryButton("Analyze", {
            scope.launch {
                busy = "Analyzing… (the first time loads the tokenizer and your deck)"
                val r = withContext(Dispatchers.IO) { runCatching { state.matcher()?.analyze(input) } }
                busy = null
                r.onSuccess { analysis = it }
            }
        }, enabled = input.isNotBlank() && busy == null, icon = Icons.Filled.Search)
        busy?.let { Busy(it) }
        if (analysis == null && busy == null && state.jmdict() == null) ErrorNote("Set up the dictionary first (Run setup again).")

        val a = analysis ?: return@Page
        val stats = a.stats(policy, lists)
        PaperCard {
            Text("%.1f%% unknown".format(stats.unknownRate * 100), style = MaterialTheme.typography.headlineSmall,
                color = if (stats.unknownRate <= 0.03) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.accent)
            Text("${stats.unknown} of ${stats.counted} words · the story aims for under 3%", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PaperCard { AnnotatedResult(a, policy, lists) { picked = it } }

        PaperCard {
            Text("Words up to rank %,d count as known".format(policy.frequencyThreshold), style = MaterialTheme.typography.titleSmall)
            Slider(policy.frequencyThreshold.toFloat(), { policy = policy.copy(frequencyThreshold = (it / 500).toInt() * 500) },
                valueRange = 0f..24_000f, steps = 47, onValueChangeFinished = { state.policy = policy })
            Toggle("Studied cards you suspended (leeches) count as known", policy.studiedSuspendedKnown) {
                policy = policy.copy(studiedSuspendedKnown = it); state.policy = policy
            }
            Toggle("Never-studied suspended cards (suspend_me) count as known", policy.newSuspendedKnown) {
                policy = policy.copy(newSuspendedKnown = it); state.policy = policy
            }
        }

        SectionTitle("Unknown rate by cut-off")
        PaperCard {
            for ((t, s) in Calibration.ratesByThreshold(a, policy, lists)) Row {
                Text("%,6d".format(t), Modifier.width(72.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Text("%5.1f%%".format(s.unknownRate * 100), Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Text("${s.unknown} of ${s.counted}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val flagged = Calibration.flagged(a, policy, lists, state.jmdict())
        if (flagged.isNotEmpty()) {
            SectionTitle("Flagged words")
            PaperCard {
                flagged.forEachIndexed { i, f ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().clickable { picked = a.spans.first { it.lemma == f.lemma } }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(f.lemma, style = ReadingText.copy(fontSize = 18.sp, lineHeight = 24.sp), modifier = Modifier.width(110.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.gloss ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Text("×${f.count} · rank ${f.rank?.let { "%,d".format(it) } ?: "–"}" + if (f.deck.isNotEmpty()) " · in deck, not studied" else "",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    picked?.let { span -> MarkWordDialog(state, span, policy, lists, onLists = { lists = it; state.lists = it }) { picked = null } }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Switch(value, onChange)
    }
}

@Composable
private fun AnnotatedResult(a: Analysis, policy: KnownPolicy, lists: KnownLists, onTap: (Span) -> Unit) {
    val unknownBg = MaterialTheme.colorScheme.accentContainer
    val newBg = MaterialTheme.colorScheme.tertiaryContainer
    val text = buildAnnotatedString {
        var pos = 0
        for (s in a.spans) {
            if (s.start > pos) append(a.text.substring(pos, s.start))
            val style = when (s.status(policy, lists)) {
                Status.UNKNOWN -> SpanStyle(background = unknownBg)
                Status.DECK_NEW -> SpanStyle(background = newBg)
                Status.DECK -> SpanStyle(textDecoration = TextDecoration.Underline)
                else -> SpanStyle()
            }
            withLink(LinkAnnotation.Clickable("w${s.start}") { onTap(s) }) { withStyle(style) { append(s.surface) } }
            pos = s.end
        }
        if (pos < a.text.length) append(a.text.substring(pos))
    }
    Text(text, style = ReadingText.copy(fontSize = 18.sp, lineHeight = 32.sp))
}

@Composable
private fun MarkWordDialog(state: AppState, span: Span, policy: KnownPolicy, lists: KnownLists, onLists: (KnownLists) -> Unit, close: () -> Unit) {
    val dict = state.jmdict()
    val senses = remember(span) { span.entries.take(2).flatMap { e -> dict?.senses(e).orEmpty().take(3).map { it.gloss } } }
    val lemma = span.lemma
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (span.surface == lemma) lemma else "${span.surface} → $lemma", style = WordText.copy(fontSize = 30.sp)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${span.status(policy, lists).name.lowercase().replace('_', ' ')} · rank ${span.rank?.let { "%,d".format(it) } ?: "–"}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.accent)
                span.ignoredBecause?.let { Text("Not counted: $it", style = MaterialTheme.typography.bodySmall) }
                for (w in span.deck) Text("${w.deck.substringAfterLast("::")}: ${w.word} (${w.state.name.lowercase().replace('_', ' ')})", style = MaterialTheme.typography.bodySmall)
                if (span.parts.isNotEmpty()) Text("Parts: " + span.parts.joinToString(" + ") { it.lemma }, style = MaterialTheme.typography.bodySmall)
                senses.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            Row {
                TextButton({ onLists(lists.copy(known = lists.known + lemma)); close() }) { Text("Known") }
                TextButton({ onLists(lists.copy(names = lists.names + lemma)); close() }) { Text("Name") }
                TextButton({ onLists(lists.copy(ignored = lists.ignored + lemma)); close() }) { Text("Ignore") }
            }
        },
        dismissButton = { TextButton({ onLists(KnownLists(lists.known - lemma, lists.names - lemma, lists.ignored - lemma)); close() }) { Text("Clear") } },
    )
}
