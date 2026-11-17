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
import androidx.compose.material.icons.filled.Settings
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

/** What AnkiDroid has due now in the vocabulary decks (its own limits applied). */
data class DueCounts(val review: Int, val learn: Int, val new: Int) {
    val total get() = review + learn + new
}

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
    val due: DueCounts? = null,
    /** Review words per passage: measured, or estimated from the passage length. */
    val perPassage: Double = 6.0,
    /** Cards read in the story today (Anki's day), and their would-be grades. */
    val readToday: Int = 0,
    val goodToday: Int = 0,
    val againToday: Int = 0,
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

        TodayCard(info)

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
            ToolRow(Icons.Filled.Settings, "Settings", "Text size, passage length, API key, new story") { go(Screen.SETTINGS) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToolRow(Icons.Filled.Tune, "Run setup again", "Welcome, dictionary, decks, vocabulary, key, story") { onRestartSetup() }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Today in numbers: what Anki has due, what the story has covered (read-only: would-be grades). */
@Composable
fun TodayCard(info: HomeInfo) {
    if (info.due == null && info.readToday == 0) return
    PaperCard {
        Text("Today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            info.due?.let { d ->
                Figure(Modifier.weight(1f), d.total.toString(), "due in Anki",
                    listOfNotNull(d.review.takeIf { it > 0 }?.let { "$it review" }, d.learn.takeIf { it > 0 }?.let { "$it learning" },
                        d.new.takeIf { it > 0 }?.let { "$it new" }).joinToString(" · ").ifEmpty { "all done" })
            }
            Figure(Modifier.weight(1f), info.readToday.toString(), "read in the story",
                if (info.readToday == 0) "none yet" else "${info.goodToday} Good · ${info.againToday} Again")
        }
        if (info.due != null && info.due.total > 0) {
            val n = kotlin.math.ceil(info.due.total / info.perPassage.coerceAtLeast(1.0)).toInt()
            Text("About $n passages to cover what's due (about ${"%.0f".format(info.perPassage)} review words each). " +
                "Read-only for now: Anki's count only drops once the app answers cards.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Figure(modifier: Modifier, number: String, label: String, detail: String) {
    Column(modifier) {
        Text(number, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif), color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
