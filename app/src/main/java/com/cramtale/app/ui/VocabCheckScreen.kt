package com.cramtale.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cramtale.app.AppState
import com.cramtale.core.match.DeckIndex
import com.cramtale.core.match.VocabCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One question: a word outside your decks, know it or not, with Back to undo the last answer. */
@Composable
fun VocabQuestion(
    item: VocabCheck.Item, index: Int, total: Int,
    previous: Pair<VocabCheck.Item, Boolean>?,
    onAnswer: (Boolean) -> Unit, onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LinearProgressIndicator(progress = { index / total.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {})
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1} of $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("frequency ${item.band / 1000}k+", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.spelling, style = WordText, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                Text("Do you know what it means?", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // the previous answer, with its meaning, so a wrong tap is noticed and can be undone
        if (previous != null) PaperCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${previous.first.spelling}  ·  ${if (previous.second) "you said known" else "you said unknown"}",
                        style = MaterialTheme.typography.labelLarge)
                    Text(previous.first.gloss, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                QuietButton("Back", onBack, icon = Icons.AutoMirrored.Filled.Undo)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ onAnswer(false) }, Modifier.weight(1f).heightIn(min = 60.dp), shape = RoundedCornerShape(30.dp)) {
                Icon(Icons.Filled.Close, null); Spacer(Modifier.width(8.dp)); Text("Don't know", style = MaterialTheme.typography.labelLarge)
            }
            Button({ onAnswer(true) }, Modifier.weight(1f).heightIn(min = 60.dp), shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) {
                Icon(Icons.Filled.Check, null); Spacer(Modifier.width(8.dp)); Text("Know it", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun VocabResults(results: List<VocabCheck.BandResult>, suggested: Int, applied: Boolean, onApply: () -> Unit, onBack: () -> Unit, onRedo: () -> Unit) {
    Page {
        Text("Your result", style = MaterialTheme.typography.headlineSmall)
        Text("How many words you knew in each frequency band. The cut-off goes where you stop knowing 90%.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PaperCard {
            for (r in results) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${r.from / 1000}k–${r.to / 1000}k", Modifier.width(76.dp), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                LinearProgressIndicator(progress = { r.share.toFloat() }, modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)),
                    color = if (r.share >= VocabCheck.TARGET) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary, drawStopIndicator = {},
                    trackColor = MaterialTheme.colorScheme.surfaceVariant)
                Text("${r.known}/${r.asked}", Modifier.width(52.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
            }
        }
        PaperCard {
            Text("Suggested cut-off", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%,d".format(suggested), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text("Words more common than this count as known. The words you said you know are added to your Known list either way.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (applied) Pill("Saved", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        PrimaryButton(if (applied) "Done" else "Use %,d".format(suggested), if (applied) onBack else onApply)
        SecondaryButton("Change my last answer", onRedo)
    }
}

/**
 * The vocabulary check with its state: about 80 words outside your decks, Back undoes the last
 * answer, and the result sets the frequency cut-off.
 */
@Composable
fun VocabCheckFlow(state: AppState, onDone: () -> Unit) {
    var items by remember { mutableStateOf<List<VocabCheck.Item>?>(null) }
    var error by remember { mutableStateOf("") }
    var index by remember { mutableIntStateOf(0) }
    val answers = remember { mutableStateListOf<Boolean>() }
    var applied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dict = state.jmdict()
            val freq = state.frequency
            when {
                dict == null -> error = "Set up the dictionary first."
                freq == null -> error = "This build has no frequency list."
                else -> items = VocabCheck.items(dict, freq, DeckIndex(state.deck(), dict))
            }
        }
    }
    if (error.isNotEmpty()) { Page { ErrorNote(error) }; return }
    val list = items ?: run { Page { Busy("Picking words outside your decks…") }; return }

    if (index < list.size) {
        VocabQuestion(
            item = list[index], index = index, total = list.size,
            previous = if (index > 0) list[index - 1] to answers[index - 1] else null,
            onAnswer = { known -> answers.add(known); index++ },
            onBack = { if (index > 0) { index--; answers.removeAt(answers.size - 1) } },
        )
        return
    }
    val map = list.zip(answers).toMap()
    val results = VocabCheck.results(map)
    val suggested = VocabCheck.suggestedThreshold(results)
    VocabResults(results, suggested, applied,
        onApply = {
            state.policy = state.policy.copy(frequencyThreshold = suggested)
            state.lists = state.lists.copy(known = state.lists.known + map.filterValues { it }.keys.map { it.spelling })
            applied = true
            onDone()
        },
        onBack = onDone,
        onRedo = { index--; answers.removeAt(answers.size - 1); applied = false },
    )
}
