package com.cramtale.app.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** A scrolling page with the app's margins. */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp))
}

/** A card: white on the cool paper, lifted by a soft shadow (no outline). */
@Composable
fun PaperCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().let { if (onClick != null) it.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick) else it },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 1.dp,
    ) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

/** An icon on a tinted rounded square (the app's icon language). */
@Composable
fun IconTile(icon: ImageVector, container: Color = MaterialTheme.colorScheme.primaryContainer,
             content: Color = MaterialTheme.colorScheme.onPrimaryContainer, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = content, modifier = Modifier.size(size * 0.55f))
    }
}

/** A row in a list of tools: icon, title, an optional short line, chevron. */
@Composable
fun ToolRow(icon: ImageVector, title: String, subtitle: String?,
            container: Color = MaterialTheme.colorScheme.primaryContainer, content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, container, content)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(26.dp),
        contentPadding = PaddingValues(horizontal = 24.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(26.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, icon: ImageVector? = null, enabled: Boolean = true) {
    TextButton(onClick, enabled = enabled) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text)
    }
}

/** A small rounded label: status of the collection, a deck, a check. */
@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(50)).background(container).padding(horizontal = 12.dp, vertical = 5.dp),
        color = content, style = MaterialTheme.typography.labelMedium)
}

/** Header of one setup step: segmented progress, step number, title, explanation. */
@Composable
fun StepHeader(step: Int, of: Int, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(of) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(
                    if (i < step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ))
            }
        }
        Text("Step $step of $of", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.accent)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A step's buttons, pinned to the bottom. */
@Composable
fun StepFooter(primary: String, onPrimary: () -> Unit, enabled: Boolean = true, secondary: String? = null, onSecondary: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(primary, onPrimary, enabled = enabled)
        if (secondary != null && onSecondary != null) TextButton(onSecondary, Modifier.fillMaxWidth()) { Text(secondary) }
    }
}

/** A busy line with a slim progress bar. */
@Composable
fun Busy(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)), trackColor = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
fun ErrorNote(text: String, onRetry: (() -> Unit)? = null) {
    // the first paragraph is what to do; the rest is the technical chain, smaller, for reports
    val headline = text.substringBefore("\n\n")
    val details = text.substringAfter("\n\n", "")
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.errorContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            Text(headline, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        }
        // the technical chain is for bug reports: there on request, not in the way
        if (details.isNotBlank()) {
            val openState = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            val open = openState.value
            if (open) Text(details, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            Text(if (open) "Hide details" else "Details", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { openState.value = !open }.padding(vertical = 4.dp))
        }
        if (onRetry != null) Button(onRetry, colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.accent, contentColor = MaterialTheme.colorScheme.onAccent)) {
            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Try again")
        }
    }
}

@Composable
fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 9.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.accent))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, content = content)
}

@Composable
fun CenterText(text: String, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, Modifier.fillMaxWidth(), style = style, color = color, textAlign = TextAlign.Center)
