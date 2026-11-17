package com.cramtale.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cramtale.app.AppState
import com.cramtale.core.match.DeckIndex
import com.cramtale.core.match.VocabCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Words outside your decks, about 10 per frequency band. Answer honestly: the cut-off is set where
 * you stop knowing 90% of them. The meaning of each word shows after you answer, to keep you honest.
 */
@Composable
fun VocabCheckScreen(state: AppState, done: () -> Unit) {
    var items by remember { mutableStateOf<List<VocabCheck.Item>?>(null) }
    var error by remember { mutableStateOf("") }
    var index by remember { mutableStateOf(0) }
    val answers = remember { LinkedHashMap<VocabCheck.Item, Boolean>() }
    var last by remember { mutableStateOf<Pair<VocabCheck.Item, Boolean>?>(null) }
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

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (error.isNotEmpty()) { Text(error); return@Column }
        val list = items ?: run { Text("Picking words…"); return@Column }

        if (index < list.size) {
            val item = list[index]
            Text("Words not in your decks. Do you know what it means?", textAlign = TextAlign.Center)
            LinearProgressIndicator(progress = { index / list.size.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("${index + 1} / ${list.size}", fontSize = 12.sp)
            Text(item.spelling, fontSize = 44.sp, modifier = Modifier.padding(vertical = 24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = { answers[item] = false; last = item to false; index++ }) { Text("Don't know") }
                Button(onClick = { answers[item] = true; last = item to true; index++ }) { Text("Know it") }
            }
            last?.let { (w, known) ->
                Text("${w.spelling}: ${w.gloss}  (${if (known) "you said known" else "you said not known"})",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            return@Column
        }

        val results = VocabCheck.results(answers)
        val suggested = VocabCheck.suggestedThreshold(results)
        Text("Known share by frequency band", style = MaterialTheme.typography.titleMedium)
        Text(
            results.joinToString("\n") { "%6d–%-6d  %2d / %2d  %3.0f%%".format(it.from, it.to, it.known, it.asked, it.share * 100) },
            fontFamily = FontFamily.Monospace, fontSize = 13.sp,
        )
        Text("Suggested cut-off: $suggested (the last band where you know at least 90%)", textAlign = TextAlign.Center)
        Text("The words you know are also added to your Known list.", fontSize = 12.sp)
        if (!applied) {
            Button(onClick = {
                state.policy = state.policy.copy(frequencyThreshold = suggested)
                state.lists = state.lists.copy(known = state.lists.known + answers.filterValues { it }.keys.map { it.spelling })
                applied = true
            }) { Text("Use $suggested") }
        } else {
            Text("Saved.")
        }
        OutlinedButton(onClick = done) { Text("Back") }
    }
}
