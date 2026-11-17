package com.cramtale.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cramtale.app.AppState
import com.cramtale.core.story.PassageLength

/** What the settings screen shows and changes; plain values so screenshots can render it. */
data class SettingsValues(
    val textScale: Float,
    val length: PassageLength,
    val twoReads: Boolean,
    val firmStyle: Boolean,
    val keyHint: String?,
)

val TEXT_SCALES = listOf(0.85f to "Small", 1f to "Normal", 1.15f to "Large", 1.3f to "Larger")

@Composable
fun SettingsScreen(state: AppState, onNewStory: () -> Unit, onScale: (Float) -> Unit) {
    var v by remember {
        mutableStateOf(SettingsValues(state.textScale, state.passageLength, state.twoReads, state.firmStyleRules, state.story.keyHint()))
    }
    SettingsContent(v,
        onScale = { state.textScale = it; onScale(it); v = v.copy(textScale = it) },
        onLength = { state.passageLength = it; state.story.settingsChanged(); v = v.copy(length = it) },
        onTwoReads = { state.twoReads = it; v = v.copy(twoReads = it) },
        onFirm = { state.firmStyleRules = it; state.story.settingsChanged(); v = v.copy(firmStyle = it) },
        onKey = { state.story.saveKey(it.trim()); v = v.copy(keyHint = state.story.keyHint()) },
        onNewStory = onNewStory,
    )
}

@Composable
fun SettingsContent(
    v: SettingsValues, onScale: (Float) -> Unit, onLength: (PassageLength) -> Unit, onTwoReads: (Boolean) -> Unit,
    onFirm: (Boolean) -> Unit, onKey: (String) -> Unit, onNewStory: () -> Unit,
) {
    var keyInput by remember { mutableStateOf<String?>(null) }
    var confirmNew by remember { mutableStateOf(false) }
    Page {
        SectionTitle("Reading")
        PaperCard {
            Text("Text size", style = MaterialTheme.typography.titleSmall)
            Segmented(TEXT_SCALES.map { it.second }, TEXT_SCALES.indexOfFirst { it.first == v.textScale }.coerceAtLeast(1)) { onScale(TEXT_SCALES[it].first) }
            androidx.compose.runtime.CompositionLocalProvider(LocalReadingScale provides v.textScale) {
                Text("店の戸を半分開けたところで、榊さんが入ってきた。", style = readingStyle())
            }
        }

        SectionTitle("The story")
        PaperCard {
            Text("Passage length", style = MaterialTheme.typography.titleSmall)
            Segmented(PassageLength.entries.map { it.label }, v.length.ordinal) { onLength(PassageLength.entries[it]) }
            Hint(when (v.length) {
                PassageLength.SHORT -> "More back-and-forth, up to ${v.length.maxWords} review words a passage. A big review pile takes more turns."
                PassageLength.NORMAL -> "Up to ${v.length.maxWords} review words a passage."
                PassageLength.LONG -> "Up to ${v.length.maxWords} review words a passage: clears a big pile in fewer, cheaper turns."
            })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Toggle("Style rules as firm prohibitions", "Off: the style is described positively (\"short sentences\"). On: as rules (\"never…\").", v.firmStyle, onFirm)
        }

        SectionTitle("Reviews")
        PaperCard {
            Toggle("Young cards need two untapped reads", "Cards with an interval under 21 days count as Good only after two reads in separate passages.", v.twoReads, onTwoReads)
        }

        SectionTitle("Claude")
        PaperCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("API key", style = MaterialTheme.typography.titleSmall)
                    Hint(v.keyHint?.let { "Saved on this phone, encrypted: $it" } ?: "No key yet")
                }
                TextButton({ keyInput = "" }) { Text(if (v.keyHint == null) "Add" else "Replace") }
            }
        }

        SectionTitle("Start over")
        PaperCard {
            Text("New story", style = MaterialTheme.typography.titleSmall)
            Hint("This story is archived on the phone (nothing is deleted) and a new one is created. Your mistakes, review log and costs carry over.")
            SecondaryButton("Start a new story", { confirmNew = true })
        }
    }

    keyInput?.let { k ->
        AlertDialog(onDismissRequest = { keyInput = null }, title = { Text("Claude API key") },
            text = {
                OutlinedTextField(k, { keyInput = it }, Modifier.fillMaxWidth(), placeholder = { Text("sk-ant-…") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
            },
            confirmButton = { TextButton({ onKey(k); keyInput = null }, enabled = k.trim().startsWith("sk-")) { Text("Save") } },
            dismissButton = { TextButton({ keyInput = null }) { Text("Cancel") } })
    }
    if (confirmNew) AlertDialog(onDismissRequest = { confirmNew = false }, title = { Text("Start a new story?") },
        text = { Text("The current story is moved to the archive on this phone. You'll describe the new one next.") },
        confirmButton = { TextButton({ confirmNew = false; onNewStory() }) { Text("Start new story") } },
        dismissButton = { TextButton({ confirmNew = false }) { Text("Keep this one") } })
}

@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            SegmentedButton(i == selected, { onSelect(i) }, SegmentedButtonDefaults.itemShape(i, labels.size)) { Text(l) }
        }
    }
}

@Composable
private fun Toggle(title: String, hint: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Hint(hint)
        }
        Spacer(Modifier.width(12.dp))
        Switch(value, onChange)
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
