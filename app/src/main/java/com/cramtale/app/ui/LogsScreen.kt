package com.cramtale.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cramtale.app.AppState
import com.cramtale.app.anki.AnkiReader
import com.cramtale.core.review.DryRunReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Dry-run report, would-be answers, costs, your mistakes, and story settings. */
@Composable
fun LogsScreen(state: AppState) {
    val story = state.story
    val context = LocalContext.current
    var report by remember { mutableStateOf<DryRunReport.Summary?>(null) }
    var reportNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val t = state.target ?: run { reportNote = "No AnkiDroid connected."; return@withContext }
            val reader = AnkiReader(context, t)
            if (!reader.hasPermission()) { reportNote = "No access to AnkiDroid."; return@withContext }
            report = DryRunReport.build(story.gradeLog.snapshot(), reader::card)
        }
    }
    Page {
        SectionTitle("Would-be answers vs what you pressed")
        val r = report
        if (r == null) { if (reportNote != null) ErrorNote(reportNote!!) else Busy("Comparing with AnkiDroid…") } else {
            PaperCard {
                Text("${r.rows.size} answers logged", style = MaterialTheme.typography.titleMedium)
                Stat("Agree, Good", r.agreeGood)
                Stat("Agree, Again", r.agreeAgain)
                Stat("App Good, you Again (leaks, over-easy context)", r.appGoodRealAgain, warn = true)
                Stat("App Again, you passed", r.appAgainRealPassed, warn = true)
                Stat("Not reviewed in AnkiDroid yet", r.undecided)
            }
            if (r.rows.isNotEmpty()) PaperCard {
                r.rows.takeLast(30).reversed().forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.word, style = ReadingText.copy(fontSize = 17.sp, lineHeight = 26.sp), modifier = Modifier.width(96.dp))
                        Column(Modifier.weight(1f)) {
                            Text("app ${if (row.appEase == 3) "Good" else "Again"} · you ${row.real.name.lowercase().replace('_', ' ')}",
                                style = MaterialTheme.typography.labelMedium)
                            Text(row.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        SectionTitle("Costs")
        PaperCard {
            Text("$%.3f".format(story.totalCost()), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text("all sessions · this session $%.3f".format(story.sessionCost()), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((k, v) in story.costs.byPurpose(story.session).entries.sortedByDescending { it.value }) Row {
                Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("$%.4f".format(v), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionTitle("New cards you already know")
        PaperCard {
            var known by remember { mutableStateOf(story.knownNew.all.reversed()) }
            if (known.isEmpty()) Text("None. Tap a new card's word in the story and choose Already know it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text("Skipped in the story. They'd be suspended in AnkiDroid once the app may write to it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            known.forEachIndexed { i, k ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(k.word, Modifier.weight(1f), style = ReadingText.copy(fontSize = 18.sp, lineHeight = 26.sp))
                    androidx.compose.material3.TextButton({ story.undoAlreadyKnow(k.noteId); known = story.knownNew.all.reversed() }) { Text("Undo") }
                }
            }
        }

        SectionTitle("Your mistakes")
        PaperCard {
            val keys = story.mistakes.all.sortedByDescending { it.count }
            if (keys.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            keys.forEachIndexed { i, k ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(k.key, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("×${k.count}", style = MaterialTheme.typography.labelLarge)
                    if (k.correctSince >= 3) { Spacer(Modifier.width(8.dp)); Pill("fixed", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) }
                }
            }
        }

    }
}

@Composable
private fun Stat(label: String, n: Int, warn: Boolean = false) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("$n", style = MaterialTheme.typography.labelLarge,
            color = if (warn && n > 0) MaterialTheme.colorScheme.accent else MaterialTheme.colorScheme.onSurface)
    }
}

/** Plain-text editor for prompts/, config/ and state/ in the story folder. */
@Composable
fun FilesScreen(state: AppState) {
    val root = state.story.dir
    var open by remember { mutableStateOf<File?>(null) }
    val f = open
    if (f == null) {
        val groups = remember {
            listOf("config" to "Your taste and style", "prompts" to "The game master's prompts", "state" to "Story state", "state/characters" to "Characters")
                .map { (d, title) -> title to File(root, d).listFiles { x -> x.isFile }.orEmpty().sortedBy { it.name } }
                .filter { it.second.isNotEmpty() }
        }
        Page {
            Text("Plain text files. Edit anything; the game master reads them every turn.", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (groups.isEmpty()) Text("Nothing yet: create the story first.")
            for ((title, files) in groups) {
                SectionTitle(title)
                Column {
                    files.forEachIndexed { i, x ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val label = if (x.parentFile?.name == "characters") x.useLines { it.firstOrNull() }.orEmpty().removePrefix("#").trim() else x.name
                        ToolRow(if (x.parentFile?.name == "characters") Icons.Filled.Person else if (title.startsWith("Story")) Icons.Filled.Folder else Icons.Filled.Description,
                            label, "%,d characters".format(x.length())) { open = x }
                    }
                }
            }
        }
    } else {
        var text by remember(f) { mutableStateOf(f.readText()) }
        Column(Modifier.fillMaxSize()) {
            Page(Modifier.weight(1f)) {
                Text(f.relativeTo(root).path, style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 360.dp), shape = MaterialTheme.shapes.medium,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
            }
            StepFooter("Save", { f.writeText(text); open = null }, secondary = "Cancel", onSecondary = { open = null })
        }
    }
}
