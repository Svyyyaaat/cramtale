package com.cramtale.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cramtale.core.match.GrammarPatterns

/** A grammar point found in the passage: what it means, and the sentence it's in with the pattern marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarSheet(found: GrammarPatterns.Found, passage: String, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        GrammarSheetBody(found.pattern.id, found.pattern.meaning, passage, found.start, found.end, onClose)
    }
}

@Composable
fun GrammarSheetBody(id: String, meaning: String, passage: String, start: Int, end: Int, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Filled.AutoStories, cs.tertiaryContainer, cs.onTertiaryContainer, 32.dp)
            Spacer(Modifier.width(10.dp))
            Text("Grammar", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
        }
        Text(id, style = WordText.copy(fontSize = 36.sp, lineHeight = 44.sp))
        Text(meaning, style = MaterialTheme.typography.titleMedium)
        // the sentence it's in, the pattern marked
        val ok = start in 0..end && end <= passage.length
        if (ok) {
            val s = passage.lastIndexOfAny(charArrayOf('。', '！', '？', '\n'), (start - 1).coerceAtLeast(0)).let { if (it < 0 || it >= start) 0 else it + 1 }
            val e = passage.indexOfAny(charArrayOf('。', '！', '？', '\n'), end).let { if (it < 0) passage.length else it + 1 }
            Surface(color = cs.tertiaryContainer.copy(alpha = 0.35f), shape = MaterialTheme.shapes.medium) {
                Text(buildAnnotatedString {
                    append(passage.substring(s, start))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = cs.tertiary)) { append(passage.substring(start, end)) }
                    append(passage.substring(end, e))
                }, Modifier.fillMaxWidth().padding(16.dp), style = ReadingText.copy(fontSize = 18.sp, lineHeight = 30.sp))
            }
        }
        PrimaryButton("Close", onClose)
    }
}
