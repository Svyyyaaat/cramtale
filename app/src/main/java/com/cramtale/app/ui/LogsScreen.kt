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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cramtale.app.AppState
import com.cramtale.app.anki.AnkiReader
import com.cramtale.core.review.DryRunReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Dry-run report, would-be grades, costs, your mistakes, and story settings. */
@Composable
fun LogsScreen(state: AppState) {
    val story = state.story
    val context = androidx.compose.ui.platform.LocalContext.current
    var report by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            val t = state.target ?: return@withContext "No AnkiDroid connected."
            val reader = AnkiReader(context, t)
            if (!reader.hasPermission()) return@withContext "No AnkiDroid access."
            val s = DryRunReport.build(story.gradeLog.entries, reader::card)
            "Would-be answers: ${s.rows.size}\n" +
                "Agree, Good: ${s.agreeGood}   Agree, Again: ${s.agreeAgain}\n" +
                "App Good, you Again: ${s.appGoodRealAgain}   (leaks or over-easy context)\n" +
                "App Again, you passed: ${s.appAgainRealPassed}\n" +
                "Not reviewed in AnkiDroid yet / unclear: ${s.undecided}\n\n" +
                s.rows.takeLast(40).reversed().joinToString("\n") { "${it.word}  app ${if (it.appEase == 3) "Good" else "Again"} · you ${it.real.name.lowercase()} · ${it.reason}" }
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Dry-run report", style = MaterialTheme.typography.titleMedium)
        Text("Read-only mode: what the app would have answered, against what you then pressed in AnkiDroid (inferred from how each card moved).", fontSize = 12.sp)
        Text(report, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

        Text("Costs", style = MaterialTheme.typography.titleMedium)
        Text("This session: $${"%.4f".format(story.sessionCost())}\n" +
            story.costs.byPurpose(story.session).entries.joinToString("\n") { "  ${it.key}: $${"%.4f".format(it.value)}" } +
            "\nAll sessions: $${"%.3f".format(story.costs.entries.sumOf { it.dollars } + oldCosts(story.dir))}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)

        Text("Your mistakes", style = MaterialTheme.typography.titleMedium)
        for (k in story.mistakes.all.sortedByDescending { it.count }) {
            Text("${k.key} ×${k.count}" + (if (k.correctSince >= 3) " · fixed" else "") + (k.decided?.let { " · card $it" } ?: ""), fontSize = 13.sp)
        }

        Text("Settings", style = MaterialTheme.typography.titleMedium)
        var two by remember { mutableStateOf(state.twoReads) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Young cards (interval under 21 days) need two untapped reads", Modifier.weight(1f), fontSize = 13.sp)
            Switch(two, { two = it; state.twoReads = it })
        }
        var firm by remember { mutableStateOf(state.firmStyleRules) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Style rules as firm prohibitions (instead of positive statements)", Modifier.weight(1f), fontSize = 13.sp)
            Switch(firm, { firm = it; state.firmStyleRules = it })
        }
        Text("Settings apply from the next app start.", fontSize = 11.sp)
    }
}

/** Costs logged in earlier sessions (the in-memory log only has this one). */
private fun oldCosts(dir: File): Double = File(dir, "log/costs.jsonl").takeIf { it.exists() }?.readLines()
    ?.mapNotNull { runCatching { org.json.JSONObject(it) }.getOrNull() }?.sumOf { it.optDouble("dollars") } ?: 0.0

/** Plain-text editor for prompts/, config/ and state/ in the story folder. */
@Composable
fun FilesScreen(state: AppState) {
    val root = state.story.dir
    var open by remember { mutableStateOf<File?>(null) }
    val f = open
    if (f == null) {
        val files = remember { listOf("prompts", "config", "state", "state/characters").flatMap { d ->
            File(root, d).listFiles { x -> x.isFile }.orEmpty().sortedBy { it.name }.toList()
        } }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Prompts, taste and story state. Plain files: edit anything.", fontSize = 13.sp)
            if (files.isEmpty()) Text("Nothing yet: start the story first.")
            for (x in files) Text(x.relativeTo(root).path, Modifier.fillMaxWidth().clickable { open = x }.padding(vertical = 6.dp))
        }
    } else {
        var text by remember(f) { mutableStateOf(f.readText()) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(f.relativeTo(root).path, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 300.dp), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { f.writeText(text); open = null }) { Text("Save") }
                OutlinedButton(onClick = { open = null }) { Text("Cancel") }
            }
        }
    }
}
