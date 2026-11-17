package com.cramtale.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class HomeInfo(
    val place: String?,
    val turn: Int,
    val chapter: Int,
    val lastLines: String?,
    val totalCost: Double,
    val collection: String,
    val collectionIsReal: Boolean,
    val connected: Boolean,
    val deckWords: Int,
    val cutoff: Int,
)

@Composable
fun HomeContent(info: HomeInfo, go: (Screen) -> Unit, onRestartSetup: () -> Unit) {
    Page {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cramtale", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif),
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            when {
                !info.connected -> Pill("Anki not connected", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                info.collectionIsReal -> Pill("Real collection · read-only", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                else -> Pill("Copy · read-only", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }

        // the story, first
        PaperCard(onClick = { go(Screen.STORY) }) {
            Text(if (info.turn == 0) "Your story is ready" else "Chapter ${info.chapter} · turn ${info.turn}",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Text(info.place ?: "A new story", style = MaterialTheme.typography.titleLarge)
            info.lastLines?.let {
                Text(it, style = ReadingText.copy(fontSize = 16.sp, lineHeight = 28.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            PrimaryButton(if (info.turn == 0) "Begin" else "Continue the story", { go(Screen.STORY) }, icon = Icons.AutoMirrored.Filled.MenuBook)
        }

        SectionTitle("Your Japanese")
        Column {
            ToolRow(Icons.Filled.Quiz, "Vocabulary check", "Cut-off now %,d. About 80 words, two minutes.".format(info.cutoff)) { go(Screen.VOCAB) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToolRow(Icons.Filled.Spellcheck, "Calibrate on real text", "Paste text, see what counts as unknown, mark what you know") { go(Screen.CALIBRATE) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToolRow(Icons.Filled.Style, "Your decks", "%,d words read from AnkiDroid".format(info.deckWords)) { go(Screen.DECK) }
        }

        SectionTitle("Behind the scenes")
        Column {
            ToolRow(Icons.Filled.BarChart, "Review log and costs", "What would have been answered, vs what you pressed in AnkiDroid · $%.2f so far".format(info.totalCost)) { go(Screen.LOGS) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToolRow(Icons.Filled.Description, "Prompts and story files", "Your taste, the game master's prompts, characters, secrets") { go(Screen.FILES) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToolRow(Icons.Filled.Link, "AnkiDroid connection", info.collection) { go(Screen.TARGET) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToolRow(Icons.Filled.Tune, "Run setup again", "Welcome, dictionary, decks, vocabulary, key, story") { onRestartSetup() }
        }
        Spacer(Modifier.height(16.dp))
    }
}
