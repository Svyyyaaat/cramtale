package com.cramtale.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.clickable
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
    val styleLine: String? = null,
    val theme: com.cramtale.app.AppState.ThemeMode = com.cramtale.app.AppState.ThemeMode.SYSTEM,
    val materialYou: Boolean = true,
    /** Claude Code in Termux instead of the API. */
    val claudeCode: Boolean = false,
    /** 0 normal, 1 strict, 2 annoying. */
    val corrections: Int = 0,
    /** New stories' default story style (how it should feel), and whether it's yours or the app's. */
    val defaultStyle: String = "",
    val ownDefaultStyle: Boolean = false,
)

val TEXT_SCALES = listOf(0.85f to "Small", 1f to "Normal", 1.15f to "Large", 1.3f to "Larger")

@Composable
fun SettingsScreen(state: AppState, onNewStory: () -> Unit, onScale: (Float) -> Unit, onPickStyle: () -> Unit,
                   go: (Screen) -> Unit = {}, onRestartSetup: () -> Unit = {}) {
    var v by remember {
        mutableStateOf(SettingsValues(state.textScale, state.passageLength, state.twoReads, state.firmStyleRules, state.story.keyHint(),
            state.story.styleLine(), state.themeMode, state.materialYou, state.claudeCode, state.correctionLevel,
            state.story.defaultTaste(), state.story.hasOwnDefaultTaste()))
    }
    SettingsContent(v,
        onScale = { state.textScale = it; onScale(it); v = v.copy(textScale = it) },
        onLength = { state.passageLength = it; state.story.settingsChanged(); v = v.copy(length = it) },
        onTwoReads = { state.twoReads = it; v = v.copy(twoReads = it) },
        onFirm = { state.firmStyleRules = it; state.story.settingsChanged(); v = v.copy(firmStyle = it) },
        onKey = { state.story.saveKey(it.trim()); v = v.copy(keyHint = state.story.keyHint()) },
        onNewStory = onNewStory,
        onPickStyle = onPickStyle,
        onTheme = { state.setTheme(it); v = v.copy(theme = it) },
        onColors = { state.chooseMaterialYou(it); v = v.copy(materialYou = it) },
        onBackend = { state.chooseClaudeCode(it); v = v.copy(claudeCode = it) },
        onCorrections = { state.correctionLevel = it; v = v.copy(corrections = it) },
        onDefaultStyle = { t -> state.story.saveDefaultTaste(t); v = v.copy(defaultStyle = state.story.defaultTaste(), ownDefaultStyle = state.story.hasOwnDefaultTaste()) },
        shippedStyle = state.story.shippedTaste(),
        claudeCodeSetup = { ClaudeCodeSetup(state) },
        advanced = listOf(
            Triple("Calibrate on real text", "Paste Japanese you read; it marks what you know") { go(Screen.CALIBRATE) },
            Triple("AnkiDroid connection", "Which collection is read") { go(Screen.TARGET) },
            Triple("Prompts and story files", "Everything Claude is told, editable") { go(Screen.FILES) },
            Triple("Run setup again", "The first-run walk-through") { onRestartSetup() },
        ),
    )
}

@Composable
fun SettingsContent(
    v: SettingsValues, onScale: (Float) -> Unit, onLength: (PassageLength) -> Unit, onTwoReads: (Boolean) -> Unit,
    onFirm: (Boolean) -> Unit, onKey: (String) -> Unit, onNewStory: () -> Unit, onPickStyle: () -> Unit = {},
    onTheme: (com.cramtale.app.AppState.ThemeMode) -> Unit = {},
    onColors: (Boolean) -> Unit = {},
    onBackend: (Boolean) -> Unit = {},
    onCorrections: (Int) -> Unit = {},
    /** Saves the default story style ("" or the shipped text: back to the app's). */
    onDefaultStyle: (String) -> Unit = {}, shippedStyle: String = "",
    claudeCodeSetup: @Composable () -> Unit = {},
    /** Rarely used screens: (name, what it is, open). */
    advanced: List<Triple<String, String, () -> Unit>> = emptyList(),
) {
    var keyInput by remember { mutableStateOf<String?>(null) }
    var styleInput by remember { mutableStateOf<String?>(null) }
    Page {
        SectionTitle("Look and reading")
        PaperCard {
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            val modes = com.cramtale.app.AppState.ThemeMode.entries
            Segmented(modes.map { it.label }, v.theme.ordinal) { onTheme(modes[it]) }
            Hint("System follows your phone's light or dark setting.")
            Text("Colours", style = MaterialTheme.typography.titleSmall)
            Segmented(listOf("From wallpaper", "Cramtale"), if (v.materialYou) 0 else 1) { onColors(it == 0) }
            Hint(if (android.os.Build.VERSION.SDK_INT >= 31) "Colours taken from your wallpaper (Material You)." else "Wallpaper colours need Android 12; Cramtale's colours are used.")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Writing style", style = MaterialTheme.typography.titleSmall)
                    Hint(v.styleLine ?: "Not picked yet")
                }
                TextButton(onPickStyle) { Text("Change") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Toggle("Stricter style", "Claude gets the style as rules (\"never…\") instead of a description. Try it if passages drift from the style.", v.firmStyle, onFirm)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Default story style", style = MaterialTheme.typography.titleSmall)
                    Hint((if (v.ownDefaultStyle) "Yours: " else "The app's: ") + v.defaultStyle.take(90) + if (v.defaultStyle.length > 90) "…" else "")
                }
                TextButton({ styleInput = v.defaultStyle }) { Text("Edit") }
            }
        }

        SectionTitle("Reviews")
        PaperCard {
            Text("Corrections to your Japanese", style = MaterialTheme.typography.titleSmall)
            Segmented(listOf("Normal", "Strict", "Annoying"), v.corrections) { onCorrections(it) }
            Hint(when (v.corrections) {
                0 -> "Only real mistakes."
                1 -> "Also phrasing that's correct but not how people say it."
                else -> "Everything a teacher would mention, small polish included."
            } + " Ones not shown are kept, and shown if you make the same one again.")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Toggle("Young cards need two untapped reads", "Cards with an interval under 21 days count as Good only after two reads in separate passages.", v.twoReads, onTwoReads)
        }

        SectionTitle("Claude")
        PaperCard {
            Segmented(listOf("API key", "Claude Code"), if (v.claudeCode) 1 else 0) { onBackend(it == 1) }
            if (v.claudeCode) claudeCodeSetup()
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("API key", style = MaterialTheme.typography.titleSmall)
                    Hint(v.keyHint?.let { "Saved on this phone, encrypted: $it" } ?: "No key yet")
                }
                TextButton({ keyInput = "" }) { Text(if (v.keyHint == null) "Add" else "Replace") }
            }
        }

        if (advanced.isNotEmpty()) {
            SectionTitle("Advanced")
            PaperCard {
                advanced.forEachIndexed { i, (name, about, open) ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Hint(about)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    styleInput?.let { t ->
        AlertDialog(onDismissRequest = { styleInput = null }, title = { Text("Default story style") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How new stories should feel, whatever they're about. A story's own style, typed when you create it, replaces this for that story.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(t, { styleInput = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp))
                    if (t.trim() != shippedStyle) TextButton({ styleInput = shippedStyle }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Use the app's original") }
                }
            },
            confirmButton = { TextButton({ onDefaultStyle(t); styleInput = null }) { Text("Save") } },
            dismissButton = { TextButton({ styleInput = null }) { Text("Cancel") } })
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
}

@Composable
internal fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
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
