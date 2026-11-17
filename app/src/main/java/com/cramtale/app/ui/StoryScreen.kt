package com.cramtale.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.cramtale.core.story.StoryEngine
import kotlinx.coroutines.launch

@Composable
fun StoryScreen(state: AppState) {
    val story = state.story
    var stage by remember { mutableIntStateOf(0) } // bumped to re-check key / setup / style
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        story.busy?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        story.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        key(stage) {
            when {
                !story.hasKey() -> KeyEntry { story.saveKey(it); stage++ }
                !story.isSetUp() -> SetupStory { seed -> scope.launch { story.setup(seed); stage++ } }
                !story.hasStyle() -> StylePicker(state) { stage++ }
                else -> StoryView(state)
            }
        }
    }
}

@Composable
private fun key(k: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@Composable
private fun KeyEntry(save: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Claude API key", style = MaterialTheme.typography.titleMedium)
        Text("Stored only on this phone, encrypted with a key from the Android Keystore. The story uses " +
            "claude-opus-5-5 to write and claude-haiku-5-5 for checks; every call's cost is logged.")
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("sk-ant-…") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Button(enabled = key.startsWith("sk-"), onClick = { save(key) }) { Text("Save") }
    }
}

@Composable
private fun SetupStory(create: (String) -> Unit) {
    var seed by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("New story", style = MaterialTheme.typography.titleMedium)
        Text("The game master builds a small world from your taste in stories (config/plot.md): a place, three or four " +
            "people with their own goals and secrets, off-screen threats, and an opening. Optionally say what it should be about.")
        OutlinedTextField(seed, { seed = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp), label = { Text("Seed (optional)") })
        Button(onClick = { create(seed) }) { Text("Create") }
    }
}

@Composable
private fun StylePicker(state: AppState, done: () -> Unit) {
    val story = state.story
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<List<Pair<StoryEngine.StyleOption, Double>>?>(null) }
    LaunchedEffect(Unit) { options = story.styleOptions() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pick the one you like reading", style = MaterialTheme.typography.titleMedium)
        Text("The same scene in four styles. Your pick becomes the writer's style sample.")
        for ((o, rate) in options.orEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${o.name} · ${"%.1f".format(rate * 100)}% unknown", fontWeight = FontWeight.Bold)
                    Text(o.passage, fontSize = 18.sp, lineHeight = 28.sp)
                    Button(onClick = { scope.launch { story.pickStyle(o); done() } }) { Text("This one") }
                }
            }
        }
        if (options != null) OutlinedButton(onClick = { scope.launch { options = story.styleOptions() } }) { Text("Four more") }
    }
}

@Composable
private fun StoryView(state: AppState) {
    val story = state.story
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Pair<Span, ShownPassage>?>(null) }
    var pickedGrammar by remember { mutableStateOf<GrammarPatterns.Found?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var tapVersion by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { if (story.current == null) story.start() }
    LaunchedEffect(story.history.size, story.current) {
        val n = story.history.size + (if (story.current != null) 1 else 0)
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Text("Session cost: $${"%.3f".format(story.sessionCost())} · read-only: grades are logged, nothing is sent to Anki",
            fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(story.history) { t -> TurnView(state, t, interactive = false, tapVersion) { _, _ -> } }
            story.current?.let { t ->
                item {
                    TurnView(state, t, interactive = true, tapVersion) { span, p ->
                        story.tap(span); tapVersion++; picked = span to p
                    }
                    GrammarRow(t.passage) { pickedGrammar = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = story.busy == null, onClick = { scope.launch { story.reroll() } }) { Text("Reroll") }
                        OutlinedButton(enabled = story.busy == null, onClick = { editing = t.passage.text }) { Text("Edit") }
                    }
                    GmNotes(t.passage)
                }
            }
        }
        for (p in story.proposals.toList()) {
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Second time: ${p.key}", fontWeight = FontWeight.Bold)
                    Text("Make a card from your corrected sentence? (Cards are added from step 3 on; for now this records your choice.)", fontSize = 12.sp)
                    Row { TextButton(onClick = { story.decideProposal(p.key, true) }) { Text("Approve") }
                        TextButton(onClick = { story.decideProposal(p.key, false) }) { Text("Reject") } }
                }
            }
        }
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("日本語で返事…") })
            Button(enabled = story.busy == null && story.current != null, onClick = {
                val msg = input
                input = ""
                scope.launch { story.send(msg) }
            }) { Text(if (input.isBlank()) "Continue" else "Send") }
        }
    }

    picked?.let { (span, p) -> WordPopup(state, span, p) { picked = null; tapVersion++ } }
    pickedGrammar?.let { g -> AlertDialog(onDismissRequest = { pickedGrammar = null },
        title = { Text(g.pattern.id) }, text = { Text(g.pattern.meaning) },
        confirmButton = { TextButton(onClick = { pickedGrammar = null }) { Text("Close") } }) }
    editing?.let { text ->
        var t by remember { mutableStateOf(text) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Edit passage") },
            text = { OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp)) },
            confirmButton = { TextButton(onClick = { editing = null; scope.launch { story.edit(t) } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun TurnView(state: AppState, t: ShownTurn, interactive: Boolean, tapVersion: Int, onTap: (Span, ShownPassage) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        t.player?.let { msg ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(10.dp)) {
                Text(msg, fontSize = 16.sp)
                // corrections beside your message
                for (c in t.corrections) Text("✎ ${c.wrong} → ${c.right}  ${c.reason}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        PassageText(state, t.passage, interactive, tapVersion, onTap)
    }
}

@Composable
private fun PassageText(state: AppState, p: ShownPassage, interactive: Boolean, tapVersion: Int, onTap: (Span, ShownPassage) -> Unit) {
    val story = state.story
    val text = remember(p, tapVersion) {
        buildAnnotatedString {
            var pos = 0
            for (s in p.spans) {
                if (s.start > pos) append(p.text.substring(pos, s.start))
                val tapped = interactive && story.isTapped(s)
                val style = when {
                    tapped -> SpanStyle(background = Color(0x55FFB300))
                    s.status(state.policy, state.lists) == Status.UNKNOWN -> SpanStyle(textDecoration = TextDecoration.Underline)
                    else -> SpanStyle()
                }
                if (interactive && s.isWord) withLink(LinkAnnotation.Clickable("w${s.start}") { onTap(s, p) }) { withStyle(style) { append(s.surface) } }
                else withStyle(style) { append(s.surface) }
                // a new card's first appearance shows its meaning inline
                s.deck.firstOrNull { it.noteId in p.newNotes }?.let { w ->
                    withStyle(SpanStyle(fontSize = 12.sp, color = Color(0xFF2E7D32))) { append("〔${p.glosses[w.noteId].orEmpty()}〕") }
                }
                pos = s.end
            }
            if (pos < p.text.length) append(p.text.substring(pos))
        }
    }
    Text(text, fontSize = 20.sp, lineHeight = 34.sp)
}

@Composable
private fun GrammarRow(p: ShownPassage, onTap: (GrammarPatterns.Found) -> Unit) {
    if (p.grammar.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Grammar:", fontSize = 12.sp)
        for (g in p.grammar.distinctBy { it.pattern.id }) TextButton(onClick = { onTap(g) }) { Text(g.pattern.id, fontSize = 12.sp) }
    }
}

@Composable
private fun GmNotes(p: ShownPassage) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide game master notes" else "Game master notes", fontSize = 12.sp) }
    if (open) (p.gmNotes + p.checks).forEach { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun WordPopup(state: AppState, span: Span, p: ShownPassage, close: () -> Unit) {
    val story = state.story
    val sentence = remember(span, p) {
        val start = p.text.lastIndexOfAny(charArrayOf('。', '！', '？', '\n', '」'), span.start - 1).let { if (it < 0) 0 else it + 1 }
        val end = p.text.indexOfAny(charArrayOf('。', '！', '？', '\n'), span.end).let { if (it < 0) p.text.length else it + 1 }
        p.text.substring(start, end).trim()
    }
    var inSentence by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(span) { inSentence = story.meaning(span, sentence) }
    val dict = state.jmdict()
    val forms = remember(span) { span.entries.firstOrNull()?.let { dict?.forms(it) } }
    val senses = remember(span) { span.entries.take(2).flatMap { e -> dict?.senses(e).orEmpty().take(3).map { it.gloss } } }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(span.lemma + (forms?.kana?.firstOrNull()?.let { "  【$it】" } ?: "")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(inSentence?.let { "Here: $it" } ?: "…", fontWeight = FontWeight.Bold)
                senses.forEach { Text("• $it", fontSize = 13.sp) }
                if (span.deck.isNotEmpty()) Text("In your deck (counts as a tap)", fontSize = 12.sp)
                else Text("Not in your deck: from step 3 it goes to the RP deck", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { story.iKnowIt(span); close() }) { Text("I know it") } },
        dismissButton = { TextButton(onClick = close) { Text("Close") } },
    )
}
