package com.cramtale.app.ui

import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
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
/** What the review log shows (plain data, so screenshots can render it). */
data class LogsData(
    val report: DryRunReport.Summary?,
    val reportNote: String?,
    val total: Double,
    val session: Double,
    val byPurpose: Map<String, Double>,
    val known: List<com.cramtale.core.review.KnownNewCards.Entry>,
    val mistakes: List<com.cramtale.core.review.MistakeLog.Key>,
)

@Composable
fun LogsScreen(state: AppState) {
    val story = state.story
    val context = LocalContext.current
    var report by remember { mutableStateOf<DryRunReport.Summary?>(null) }
    var reportNote by remember { mutableStateOf<String?>(null) }
    var known by remember { mutableStateOf(story.knownNew.all.reversed()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val t = state.target ?: run { reportNote = "No AnkiDroid connected."; return@withContext }
            val reader = AnkiReader(context, t)
            if (!reader.hasPermission()) { reportNote = "No access to AnkiDroid."; return@withContext }
            report = DryRunReport.build(story.gradeLog.history(), reader::card)
        }
    }
    LogsContent(LogsData(report, reportNote, story.totalCost(), story.sessionCost(), story.costs.byPurpose(story.session),
        known, story.mistakes.all.sortedByDescending { it.count }),
        onUndoKnown = { id -> story.undoAlreadyKnow(id); known = story.knownNew.all.reversed() })
}

@Composable
fun LogsContent(d: LogsData, onUndoKnown: (Long) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val amber = cs.outline
    Page {
        SectionTitle("Cramtale's grades vs your AnkiDroid answers")
        val r = d.report
        if (r == null) { if (d.reportNote != null) ErrorNote(d.reportNote) else Busy("Comparing with AnkiDroid…") } else {
            PaperCard {
                val decided = r.agreeGood + r.agreeAgain + r.appGoodRealAgain + r.appAgainRealPassed
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (decided == 0) "–" else "${(100 * (r.agreeGood + r.agreeAgain) / decided)}%",
                        style = MaterialTheme.typography.displaySmall, color = cs.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("matched", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
                Text("${r.rows.size} answers logged · ${r.undecided} not reviewed in AnkiDroid yet", style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Outcome(cs.tertiary, "Both Good", r.agreeGood)
                Outcome(cs.primary, "Both Again", r.agreeAgain)
                Outcome(cs.accent, "Story said Good, you said Again", r.appGoodRealAgain, "the story may have made the word too easy to guess")
                Outcome(amber, "Story said Again, you passed", r.appAgainRealPassed, "a needless tap, or a word you half knew")
            }
            if (r.rows.isNotEmpty()) PaperCard {
                Text("Latest", style = MaterialTheme.typography.titleSmall)
                r.rows.takeLast(30).reversed().forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(row.word, style = ReadingText.copy(fontSize = 18.sp, lineHeight = 26.sp), modifier = Modifier.width(88.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val good = row.appEase == 3
                                Pill("app ${if (good) "Good" else "Again"}", if (good) cs.tertiaryContainer else cs.primaryContainer,
                                    if (good) cs.onTertiaryContainer else cs.onPrimaryContainer)
                                val decided = row.real == DryRunReport.Real.PASSED || row.real == DryRunReport.Real.AGAIN
                                val agree = (good && row.real == DryRunReport.Real.PASSED) || (!good && row.real == DryRunReport.Real.AGAIN)
                                val label = when (row.real) {
                                    DryRunReport.Real.PASSED -> "you passed"; DryRunReport.Real.AGAIN -> "you Again"
                                    DryRunReport.Real.NOT_YET -> "not reviewed yet"; DryRunReport.Real.STILL_LEARNING -> "still learning"
                                    DryRunReport.Real.STUDIED_NEW -> "studied as new"; DryRunReport.Real.UNKNOWN -> "unknown"
                                }
                                // only a real disagreement is in the accent
                                Pill(label, if (decided && !agree) cs.accentContainer else cs.surfaceContainerHigh,
                                    if (decided && !agree) cs.onAccentContainer else cs.onSurfaceVariant)
                            }
                            Text(row.reason, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        SectionTitle("Costs")
        PaperCard {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$%.2f".format(d.total), style = MaterialTheme.typography.displaySmall, color = cs.primary)
                Spacer(Modifier.width(10.dp))
                Text("in all · $%.2f this session".format(d.session), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            val max = d.byPurpose.values.maxOrNull() ?: 0.0
            for ((k, v) in d.byPurpose.entries.sortedByDescending { it.value }) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row {
                    Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("$%.3f".format(v), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceContainerHigh)) {
                    Box(Modifier.fillMaxWidth((if (max > 0) v / max else 0.0).toFloat()).height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.primary))
                }
            }
        }

        SectionTitle("New cards you already know")
        PaperCard {
            if (d.known.isEmpty()) Text("None yet. In the story, tap a new card's word: Already know it.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            else Text("Skipped in the story; suspended in AnkiDroid once the app may write.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            d.known.forEachIndexed { i, k ->
                if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(k.word, Modifier.weight(1f), style = ReadingText.copy(fontSize = 18.sp, lineHeight = 26.sp))
                    androidx.compose.material3.TextButton({ onUndoKnown(k.noteId) }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Undo")
                    }
                }
            }
        }

        SectionTitle("Your mistakes")
        PaperCard {
            if (d.mistakes.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            d.mistakes.forEachIndexed { i, k ->
                if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(k.key, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (k.correctSince >= 3) Pill("fixed", cs.tertiaryContainer, cs.onTertiaryContainer)
                    else Pill("×${k.count}", cs.accentContainer, cs.onAccentContainer)
                }
            }
        }
    }
}

@Composable
private fun Outcome(color: Color, label: String, n: Int, hint: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null && n > 0) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("$n", style = MaterialTheme.typography.titleMedium)
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
            listOf("config" to "Your taste and style", "prompts" to "The game master's prompts", "state" to "Story state", "state/characters" to "Characters", "state/sources" to "Your files")
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
