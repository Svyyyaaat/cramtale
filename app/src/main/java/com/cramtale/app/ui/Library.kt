package com.cramtale.app.ui

import androidx.compose.foundation.layout.Arrangement
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
fun StoriesScreen(state: AppState, onContinue: () -> Unit, onNewStory: () -> Unit, onSwitched: () -> Unit) {
    var stories by remember { mutableStateOf<List<StorySummary>?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) { stories = withContext(Dispatchers.IO) { state.story.stories() } }
    val list = stories ?: return Busy("Opening…")
    StoriesContent(list, onContinue, onNewStory, onSwitch = { s ->
        if (state.story.switchTo(s.dir)) { tick++; onSwitched() }
    })
}

@Composable
fun StoriesContent(stories: List<StorySummary>, onContinue: () -> Unit, onNewStory: () -> Unit, onSwitch: (StorySummary) -> Unit) {
    var confirmNew by remember { mutableStateOf(false) }
    var confirmSwitch by remember { mutableStateOf<StorySummary?>(null) }
    val current = stories.firstOrNull { it.current }
    val others = stories.filter { !it.current }
    Page {
        current?.let { s ->
            SectionTitle("Playing now")
            StoryCard(s) {
                PrimaryButton(if (s.turn == 0) "Begin" else "Continue", onContinue, icon = Icons.AutoMirrored.Filled.MenuBook)
            }
        }
        SecondaryButton("Start a new story", { confirmNew = true }, icon = Icons.Filled.Add)
        if (others.isNotEmpty()) {
            SectionTitle("Earlier stories")
            for (s in others) StoryCard(s, onClick = { confirmSwitch = s }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ago(s.lastActive), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    TextButton({ confirmSwitch = s }) { Text("Pick up") }
                }
            }
        } else Text("Stories you start are kept here; you can pick any of them up again.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmNew) AlertDialog(onDismissRequest = { confirmNew = false }, title = { Text("Start a new story?") },
        text = { Text("This one stays in your stories; you can pick it up again any time. You'll describe the new one next.") },
        confirmButton = { TextButton({ confirmNew = false; onNewStory() }) { Text("Start new story") } },
        dismissButton = { TextButton({ confirmNew = false }) { Text("Cancel") } })
    confirmSwitch?.let { s ->
        AlertDialog(onDismissRequest = { confirmSwitch = null }, title = { Text("Pick up “${s.title}”?") },
            text = { Text("You'll continue it where you left off. " + (current?.let { "“${it.title}” stays in your stories." } ?: "")) },
            confirmButton = { TextButton({ confirmSwitch = null; onSwitch(s) }) { Text("Pick up") } },
            dismissButton = { TextButton({ confirmSwitch = null }) { Text("Cancel") } })
    }
}

@Composable
private fun StoryCard(s: StorySummary, onClick: (() -> Unit)? = null, actions: @Composable () -> Unit) {
    PaperCard(onClick = onClick) {
        Text(s.title, style = MaterialTheme.typography.titleMedium)
        Text(listOfNotNull(s.place, if (s.turn == 0) "not started" else "chapter ${s.chapter} · turn ${s.turn}", s.style).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.lastLines?.let {
            Text(it, style = ReadingText.copy(fontSize = 15.sp, lineHeight = 26.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

private fun ago(at: Long): String {
    val d = (System.currentTimeMillis() - at) / 86_400_000L
    return when {
        at <= 0 -> ""
        d < 1 -> "played today"
        d < 2 -> "played yesterday"
        d < 30 -> "played $d days ago"
        else -> "played " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(at))
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
        Text("Your style shapes every passage from the next one on. Same scene in each sample, so you can compare.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        currentName?.let { Pill("Now: $it", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) }
        for ((o, rate) in presets) StyleCard(o, rate, o.passage.trim() == currentSample?.trim(), onPick)
        SectionTitle("More options")
        if (generated != null) for ((o, rate) in generated) StyleCard(o, rate, o.passage.trim() == currentSample?.trim(), onPick)
        if (busy != null) Busy(busy)
        else SecondaryButton(if (generated == null) "Write four new ones" else "Write four more", onGenerate, icon = Icons.Filled.AutoAwesome)
        if (error != null) ErrorNote(error, onGenerate)
    }
}

@Composable
private fun StyleCard(o: StyleOption, unknownRate: Double, inUse: Boolean, onPick: (StyleOption) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PaperCard(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (unknownRate > 0) Pill("%.0f%% unknown".format(unknownRate * 100), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(com.cramtale.core.story.StylePresets.BLURBS[o.name] ?: o.line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(o.passage, style = ReadingText.copy(fontSize = 17.sp, lineHeight = 30.sp),
            maxLines = if (open) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
        if (!open) Text("Tap to read the whole sample", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (inUse) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text("In use", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else FilledTonalButton({ onPick(o) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Use this style")
        }
    }
}
