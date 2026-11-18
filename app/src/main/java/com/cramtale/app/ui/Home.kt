package com.cramtale.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

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
        // wordmark and the collection's state
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppMark(36.dp)
            Spacer(Modifier.width(10.dp))
            Text("Cramtale", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f))
            when {
                !info.connected -> StatusPill(Icons.Filled.LinkOff, "No Anki", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
                info.collectionIsReal -> StatusPill(Icons.Filled.Lock, "Read-only", MaterialTheme.colorScheme.accentContainer, MaterialTheme.colorScheme.onAccentContainer)
                else -> StatusPill(Icons.Filled.Lock, "Copy · read-only", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }

        StoryHero(info) { go(Screen.STORY) }
        TodayCard(info)

        SectionTitle("Tools")
        TileGrid(listOf(
            Tile(Icons.Filled.Quiz, "Vocab check", "cut-off %,d".format(info.cutoff)) { go(Screen.VOCAB) },
            Tile(Icons.Filled.Spellcheck, "Calibrate", "on real text", Tint.JADE) { go(Screen.CALIBRATE) },
            Tile(Icons.Filled.Style, "Your decks", "%,d words".format(info.deckWords), Tint.VERMILION) { go(Screen.DECK) },
            Tile(Icons.Filled.BarChart, "Review log", "$%.2f spent".format(info.totalCost)) { go(Screen.LOGS) },
            Tile(Icons.Filled.Description, "Story files", "prompts, cast", Tint.JADE) { go(Screen.FILES) },
            Tile(Icons.Filled.Link, "AnkiDroid", if (info.collectionIsReal) "real collection" else if (info.connected) "copy" else "connect", Tint.VERMILION) { go(Screen.TARGET) },
            Tile(Icons.Filled.Settings, "Settings", "text, length, key") { go(Screen.SETTINGS) },
            Tile(Icons.Filled.Tune, "Setup again", "the walk-through", Tint.NEUTRAL) { onRestartSetup() },
        ))
        Spacer(Modifier.height(12.dp))
    }
}

/** The app's mark: the launcher icon's page on its indigo tile, drawn small. */
@Composable
fun AppMark(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.cramtale.app.R.drawable.ic_launcher_background), null, Modifier.size(size))
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.cramtale.app.R.drawable.ic_launcher_foreground), null,
            Modifier.size(size * 1.15f).offset(x = -size * 0.075f, y = -size * 0.075f))
    }
}

@Composable
fun StatusPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, container: Color, content: Color) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(container).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = content)
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/** The story, first and loudest: where you are, the last lines, Continue. */
@Composable
fun StoryHero(info: HomeInfo, onOpen: () -> Unit) {
    val a = LocalAccents.current
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(a.heroStart, a.heroEnd)))
            .clickable(onClick = onOpen).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (info.turn == 0) "A new story" else "Chapter ${info.chapter} · turn ${info.turn}",
            style = MaterialTheme.typography.labelLarge, color = a.onHero.copy(alpha = 0.7f))
        Text(info.place ?: "Your story", style = MaterialTheme.typography.titleLarge, color = a.onHero)
        info.lastLines?.let {
            Text(it, style = ReadingText.copy(fontSize = 16.sp, lineHeight = 28.sp), color = a.onHero.copy(alpha = 0.85f),
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Button(onOpen, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp),
            // white on the dark card, the card's colour for the text
            colors = ButtonDefaults.buttonColors(containerColor = a.heroButton, contentColor = a.onHeroButton)) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (info.turn == 0) "Begin" else "Continue", style = MaterialTheme.typography.labelLarge)
        }
    }
}

enum class Tint { INDIGO, JADE, VERMILION, NEUTRAL }

data class Tile(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val value: String?,
                val tint: Tint = Tint.INDIGO, val onClick: () -> Unit)

/** Tools as a two-column grid of tiles: an icon, a name, one number or word. */
@Composable
fun TileGrid(tiles: List<Tile>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t -> ToolTile(t, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ToolTile(t: Tile, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = when (t.tint) {
        Tint.INDIGO -> cs.primaryContainer to cs.onPrimaryContainer
        Tint.JADE -> cs.tertiaryContainer to cs.onTertiaryContainer
        Tint.VERMILION -> cs.accentContainer to cs.onAccentContainer
        Tint.NEUTRAL -> cs.surfaceContainerHigh to cs.onSurfaceVariant
    }
    Surface(modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = t.onClick), shape = MaterialTheme.shapes.medium,
        color = cs.surfaceContainerLowest, shadowElevation = 1.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(t.icon, container, content, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                t.value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** Today in numbers: what Anki has due, what the story read (would-be grades while read-only). */
@Composable
fun TodayCard(info: HomeInfo) {
    if (info.due == null && info.readToday == 0) return
    val cs = MaterialTheme.colorScheme
    // both cards as tall as the taller one
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        info.due?.let { d ->
            StatCard(Modifier.weight(1f), Icons.Filled.Inbox, d.total.toString(), "due in Anki", cs.primary) {
                if (d.total == 0) Text("all done", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                else Breakdown(listOf(d.review to "review", d.learn to "learning", d.new to "new"))
            }
        }
        StatCard(Modifier.weight(1f), Icons.AutoMirrored.Filled.MenuBook, info.readToday.toString(), "read in the story", cs.tertiary) {
            if (info.readToday == 0) Text("none yet", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dot(cs.tertiary, "${info.goodToday} good")
                Dot(cs.accent, "${info.againToday} again")
            }
        }
    }
    if (info.due != null && info.due.total > 0) {
        val n = kotlin.math.ceil(info.due.total / info.perPassage.coerceAtLeast(1.0)).toInt()
        Text("≈ $n passages to get through what's due", style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun StatCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, number: String, label: String, accent: Color,
                     detail: @Composable () -> Unit) {
    Surface(modifier.fillMaxHeight(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(16.dp), tint = accent)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(number, style = MaterialTheme.typography.displaySmall, color = accent)
            detail()
        }
    }
}

@Composable
private fun Breakdown(parts: List<Pair<Int, String>>) {
    Text(parts.filter { it.first > 0 }.joinToString(" · ") { "${it.first} ${it.second}" }, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
}

@Composable
private fun Dot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
