package com.cramtale.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
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

@Composable
fun CalibrateScreen(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var analysis by remember { mutableStateOf<Analysis?>(null) }
    var message by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf(state.policy) }
    var lists by remember { mutableStateOf(state.lists) }
    var picked by remember { mutableStateOf<Span?>(null) }

    fun updateLists(l: KnownLists) { lists = l; state.lists = l }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((name, path) in SAMPLES) {
                OutlinedButton(onClick = { input = context.assets.open(path).bufferedReader().readText() }) { Text(name, fontSize = 12.sp) }
            }
        }
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text("Japanese text") })
        Button(onClick = {
            scope.launch {
                message = "Analyzing… (the first time loads the tokenizer and your deck)"
                val result = withContext(Dispatchers.IO) {
                    runCatching { state.matcher()?.analyze(input) }
                }
                result.onSuccess { a ->
                    analysis = a
                    message = if (a == null) "Set up the dictionary first (home screen, step 2)." else ""
                }.onFailure { message = "Failed: ${it.message}" }
            }
        }) { Text("Analyze") }
        if (message.isNotEmpty()) Text(message)

        // policy controls: changes apply instantly, nothing is re-tokenized
        Text("Words up to frequency rank ${policy.frequencyThreshold} count as known")
        Slider(
            value = policy.frequencyThreshold.toFloat(), valueRange = 0f..24_000f, steps = 47,
            onValueChange = { policy = policy.copy(frequencyThreshold = (it / 500).toInt() * 500) },
            onValueChangeFinished = { state.policy = policy },
        )
        Toggle("Studied cards you suspended (leeches) count as known", policy.studiedSuspendedKnown) {
            policy = policy.copy(studiedSuspendedKnown = it); state.policy = policy
        }
        Toggle("Suspended never-studied cards (suspend_me, already known) count as known", policy.newSuspendedKnown) {
            policy = policy.copy(newSuspendedKnown = it); state.policy = policy
        }

        val a = analysis ?: return@Column
        val stats = a.stats(policy, lists)
        Text(
            "Unknown: ${stats.unknown} of ${stats.counted} words = ${"%.1f".format(stats.unknownRate * 100)}%  (target: under 3%)",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Red: unknown · orange: in your deck, not studied yet · underlined: your deck. Tap any word.", fontSize = 12.sp)
        AnnotatedResult(a, policy = policy, lists = lists) { picked = it }

        Text("Unknown rate by threshold", style = MaterialTheme.typography.titleSmall)
        Text(
            Calibration.ratesByThreshold(a, policy, lists).joinToString("\n") { (t, s) ->
                "%6d   %5.1f%%   (%d of %d)".format(t, s.unknownRate * 100, s.unknown, s.counted)
            },
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
        Text("Flagged words", style = MaterialTheme.typography.titleSmall)
        for (f in Calibration.flagged(a, policy, lists, state.jmdict())) {
            val span = a.spans.first { it.lemma == f.lemma }
            Text(
                "${f.lemma} ×${f.count} · rank ${f.rank ?: "–"}${if (f.deck.isNotEmpty()) " · in deck, not studied" else ""} · ${f.gloss ?: ""}",
                Modifier.fillMaxWidth().clickable { picked = span }.padding(vertical = 4.dp),
            )
        }
    }

    picked?.let { span ->
        WordDialog(state, span, policy, lists, onLists = ::updateLists) { picked = null }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(value, onChange)
    }
}

@Composable
private fun AnnotatedResult(a: Analysis, policy: com.cramtale.core.match.KnownPolicy, lists: KnownLists, onTap: (Span) -> Unit) {
    val text = buildAnnotatedString {
        var pos = 0
        for (s in a.spans) {
            if (s.start > pos) append(a.text.substring(pos, s.start))
            val style = when (s.status(policy, lists)) {
                Status.UNKNOWN -> SpanStyle(background = Color(0x66E53935))
                Status.DECK_NEW -> SpanStyle(background = Color(0x66FB8C00))
                Status.DECK -> SpanStyle(textDecoration = TextDecoration.Underline)
                else -> SpanStyle()
            }
            withLink(LinkAnnotation.Clickable("w${s.start}") { onTap(s) }) {
                withStyle(style) { append(s.surface) }
            }
            pos = s.end
        }
        if (pos < a.text.length) append(a.text.substring(pos))
    }
    Text(text, fontSize = 20.sp, lineHeight = 32.sp)
}

@Composable
private fun WordDialog(
    state: AppState, span: Span, policy: com.cramtale.core.match.KnownPolicy, lists: KnownLists,
    onLists: (KnownLists) -> Unit, close: () -> Unit,
) {
    val senses = remember(span) {
        val d = state.jmdict()
        span.entries.take(2).flatMap { e -> d?.senses(e).orEmpty().take(3).map { "${it.gloss}  [${it.pos.joinToString(",")}]" } }
    }
    val lemma = span.lemma
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (span.surface == lemma) lemma else "${span.surface} → $lemma") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Status: ${span.status(policy, lists).name.lowercase()} · rank ${span.rank ?: "–"}")
                span.ignoredBecause?.let { Text("Ignored: $it") }
                for (w in span.deck) Text("Deck: ${w.deck} · ${w.word} · ${w.state.name.lowercase()}")
                if (span.parts.isNotEmpty()) Text("Parts: " + span.parts.joinToString(" + ") { it.lemma })
                senses.forEach { Text("• $it", fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onLists(lists.copy(known = lists.known + lemma)); close() }) { Text("Known") }
                TextButton(onClick = { onLists(lists.copy(names = lists.names + lemma)); close() }) { Text("Name") }
                TextButton(onClick = { onLists(lists.copy(ignored = lists.ignored + lemma)); close() }) { Text("Ignore") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onLists(KnownLists(lists.known - lemma, lists.names - lemma, lists.ignored - lemma)); close()
            }) { Text("Clear") }
        },
    )
}
