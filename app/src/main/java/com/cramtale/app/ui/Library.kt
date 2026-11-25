package com.cramtale.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cramtale.app.AppState
import com.cramtale.app.story.StoryController.StorySummary
import com.cramtale.core.story.StoryEngine.StyleOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- stories

@Composable
fun StoriesScreen(state: AppState, onContinue: () -> Unit, onNewStory: () -> Unit, onSwitched: () -> Unit,
                  onSequel: (StorySummary) -> Unit = {}) {
    var stories by remember { mutableStateOf<List<StorySummary>?>(null) }
    var tick by remember { mutableStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf<StorySummary?>(null) }
    val saveTo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val s = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { state.story.exportStory(s.dir, uri) }
            if (ok) { note = "Saved a backup of “${s.title}”."; problem = null } else problem = "Couldn't save the backup there. Try another place."
        }
    }
    val openZip = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) { state.story.importStory(uri) }
            if (r.ok) { note = r.message; problem = null } else problem = r.message
            tick++
        }
    }
    LaunchedEffect(tick) { stories = withContext(Dispatchers.IO) { state.story.stories() } }
    val list = stories ?: return Busy("Opening your stories…")
    StoriesContent(list, onContinue, onNewStory, problem, writing = state.story.isWorking, note = note, onSwitch = { s ->
        if (state.story.switchTo(s.dir)) { problem = null; tick++; onSwitched() }
        else problem = if (state.story.isWorking) "A passage is still being written. Switch once it's done." else "Couldn't open “${s.title}”. It's still in the list; try again."
    }, onDelete = { s ->
        if (state.story.deleteStory(s.dir)) { note = "“${s.title}” is deleted."; problem = null; tick++ }
        else problem = if (state.story.isWorking) "A passage is still being written. Delete once it's done." else "Couldn't delete “${s.title}”."
    }, onExport = { s -> exporting = s; saveTo.launch(state.story.exportName(s)) },
        onImport = { openZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, onSequel = onSequel)
}

/** Your stories: the one being played as an open book with Continue, the earlier ones as a quiet shelf. */
@Composable
fun StoriesContent(stories: List<StorySummary>, onContinue: () -> Unit, onNewStory: () -> Unit, problem: String? = null,
                   writing: Boolean = false, note: String? = null, onSwitch: (StorySummary) -> Unit,
                   /** A story to a zip (a backup), and a zip back onto the shelf. */
                   onExport: (StorySummary) -> Unit = {}, onImport: () -> Unit = {},
                   /** A new story that keeps this one's world and people (and their memories). */
                   onSequel: (StorySummary) -> Unit = {},
                   /** Removes a story from the phone for good (after asking). */
                   onDelete: (StorySummary) -> Unit = {}) {
    var confirmNew by remember { mutableStateOf(false) }
    var confirmSwitch by remember { mutableStateOf<StorySummary?>(null) }
    var confirmDelete by remember { mutableStateOf<StorySummary?>(null) }
    val current = stories.firstOrNull { it.current }
    val others = stories.filter { !it.current }
    val cs = MaterialTheme.colorScheme
    Page {
        if (problem != null) ErrorNote(problem)
        if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, color = cs.primary)
        if (writing) Text("A passage is being written. You can switch stories once it's done.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        if (current != null) PaperCard {
            Text(current.title, style = ReadingText.copy(fontSize = 24.sp, lineHeight = 32.sp), color = cs.onSurface)
            Text(progress(current), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            current.lastLines?.let {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(cs.primary.copy(alpha = 0.35f)))
                    Spacer(Modifier.width(12.dp))
                    Text(it, style = ReadingText.copy(fontSize = 16.sp, lineHeight = 28.sp), color = cs.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            PrimaryButton(if (current.turn == 0) "Begin" else "Continue", onContinue, icon = Icons.AutoMirrored.Filled.MenuBook)
            Row {
                QuietButton("Save a backup", { onExport(current) }, icon = Icons.Filled.SaveAlt, enabled = !writing)
                Spacer(Modifier.weight(1f))
                TextButton({ confirmDelete = current }, enabled = !writing) { Text("Delete", color = if (writing) cs.onSurfaceVariant else cs.error) }
            }
        } else {
            // nothing set up: the only thing to do here is start one
            Text("No story yet.", style = ReadingText.copy(fontSize = 22.sp, lineHeight = 30.sp))
            PrimaryButton("Start a story", onNewStory, icon = Icons.Filled.Add)
        }
        if (current != null) SecondaryButton("Start a new story", { confirmNew = true }, icon = Icons.Filled.Add, enabled = !writing)
        if (others.isNotEmpty()) {
            SectionTitle("Earlier stories")
            Column {
                others.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                    ShelfRow(s, enabled = !writing, onExport = { onExport(s) }, onDelete = { confirmDelete = s }) { confirmSwitch = s }
                }
            }
        } else if (current != null) {
            Text("Start as many stories as you like. Each one waits here where you left it.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        SectionTitle("Backups")
        Text("A backup is one .zip file with the story and your mistakes and review history. Keep it anywhere; open it here, on this phone or another.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        QuietButton("Open a backup", onImport, icon = Icons.Filled.FileOpen, enabled = !writing)
    }
    if (confirmNew) AlertDialog(onDismissRequest = { confirmNew = false }, title = { Text("Start a new story?") },
        text = { Text("“${current?.title}” stays here; you can pick it up again any time. A new story can start fresh, " +
            "or keep this one's world and people, who remember what happened.") },
        confirmButton = { TextButton({ confirmNew = false; onNewStory() }) { Text("Start fresh") } },
        dismissButton = { Row {
            TextButton({ confirmNew = false }) { Text("Cancel") }
            current?.let { c -> TextButton({ confirmNew = false; onSequel(c) }) { Text("Keep its people") } }
        } })
    confirmDelete?.let { s ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete “${s.title}”?") },
            text = { Text("It's removed from this phone for good. Your mistakes and review history stay. " +
                "If you might want it again, save a backup first.") },
            confirmButton = { TextButton({ confirmDelete = null; onDelete(s) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { Row {
                TextButton({ confirmDelete = null; onExport(s) }) { Text("Save a backup") }
                TextButton({ confirmDelete = null }) { Text("Cancel") }
            } })
    }
    confirmSwitch?.let { s ->
        AlertDialog(onDismissRequest = { confirmSwitch = null }, title = { Text("Continue “${s.title}”?") },
            text = { Text("It opens where you left off." + (current?.let { " “${it.title}” stays here." } ?: "")) },
            confirmButton = { TextButton({ confirmSwitch = null; onSwitch(s) }) { Text("Continue it") } },
            dismissButton = { TextButton({ confirmSwitch = null }) { Text("Cancel") } })
    }
}

/** An earlier story on the shelf: its title in the reading face, where it stands, when you last played it. */
@Composable
private fun ShelfRow(s: StorySummary, enabled: Boolean, onExport: () -> Unit = {}, onDelete: () -> Unit = {}, onPick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onPick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.title, style = ReadingText.copy(fontSize = 18.sp, lineHeight = 26.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(progress(s), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ago(s.lastActive), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        TextButton(onPick, enabled = enabled) { Text("Continue") }
        // the less common things, named, behind one button
        Box {
            androidx.compose.material3.IconButton({ menu = true }, enabled = enabled) {
                Icon(Icons.Filled.MoreVert, "More for ${s.title}", tint = cs.onSurfaceVariant)
            }
            androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                androidx.compose.material3.DropdownMenuItem({ Text("Save a backup") }, { menu = false; onExport() },
                    leadingIcon = { Icon(Icons.Filled.SaveAlt, null) })
                androidx.compose.material3.DropdownMenuItem({ Text("Delete", color = cs.error) }, { menu = false; onDelete() },
                    leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = cs.error) })
            }
        }
    }
}

private fun progress(s: StorySummary): String {
    val where = if (s.turn == 0) "Not started" else "Chapter ${s.chapter}, turn ${s.turn}"
    return listOfNotNull(where, s.place?.let { "in $it" }).joinToString(" ") + (s.style?.let { ". $it style" } ?: "")
}

private fun ago(at: Long): String {
    val d = (System.currentTimeMillis() - at) / 86_400_000L
    return when {
        at <= 0 -> ""
        d < 1 -> "Played today"
        d < 2 -> "Played yesterday"
        d < 30 -> "Played $d days ago"
        else -> "Played " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(at))
    }
}

// ---------------------------------------------------------------- style

@Composable
fun StyleScreen(state: AppState) {
    val story = state.story
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(story.styleName()) }
    var sample by remember { mutableStateOf(story.styleSample()) }
    var generated by remember { mutableStateOf<List<Pair<StyleOption, Double>>?>(null) }
    val presets = remember { story.presets() }
    StyleContent(current, sample, presets, generated, story.busy, story.error,
        onPick = { o -> story.pickStyle(o); current = story.styleName(); sample = story.styleSample() },
        onGenerate = { scope.launch { generated = story.styleOptions(fresh = generated != null) } })
}

@Composable
fun StyleContent(currentName: String?, currentSample: String?, presets: List<Pair<StyleOption, Double>>, generated: List<Pair<StyleOption, Double>>?,
                 busy: String?, error: String?, onPick: (StyleOption) -> Unit, onGenerate: () -> Unit) {
    Page {
        Text("The same scene in each style. Your pick applies from the next passage.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // the one in use first: you see what you have, then the alternatives
        for ((o, rate) in presets.sortedByDescending { it.first.passage.trim() == currentSample?.trim() })
            StylePage(o, rate, o.passage.trim() == currentSample?.trim(), onPick)
        // a style picked earlier from generated ones, not in the list: still shown, so you can see what you have
        if (currentSample != null && (presets + generated.orEmpty()).none { it.first.passage.trim() == currentSample.trim() })
            StylePage(StyleOption(currentName ?: "Your current style", "", currentSample), 0.0, true, onPick)
        SectionTitle("Other styles")
        if (generated != null) for ((o, rate) in generated) StylePage(o, rate, o.passage.trim() == currentSample?.trim(), onPick)
        if (busy != null) Busy(busy)
        else SecondaryButton(if (generated == null) "Write four new styles" else "Write four more", onGenerate, icon = Icons.Filled.AutoAwesome)
        if (error != null) ErrorNote(error, onGenerate)
    }
}

/** One style as a page of its sample; the one in use carries a bar down its edge. */
@Composable
private fun StylePage(o: StyleOption, unknownRate: Double, inUse: Boolean, onPick: (StyleOption) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerLowest,
        border = if (inUse) BorderStroke(2.dp, cs.primary) else null) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.name, style = MaterialTheme.typography.titleMedium)
                    val about = com.cramtale.core.story.StylePresets.BLURBS[o.name] ?: o.line
                    if (about.isNotBlank()) Text(about, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (inUse) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = cs.primary)
                    Text("In use", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                }
            }
            var open by remember { mutableStateOf(false) }
            Text(o.passage, style = ReadingText.copy(fontSize = 17.sp, lineHeight = 30.sp), maxLines = if (open) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis)
            if (!open && o.passage.lines().size > 6) TextButton({ open = true }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Read the whole sample") }
            if (unknownRate >= 0.005) Text("%.0f%% of these words may be new to you".format(unknownRate * 100),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (!inUse) FilledTonalButton({ onPick(o) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Use this style")
            }
        }
    }
}
