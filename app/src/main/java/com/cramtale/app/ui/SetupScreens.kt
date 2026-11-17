package com.cramtale.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.cramtale.app.AppState
import com.cramtale.app.anki.AnkiReader
import com.cramtale.app.anki.Probe
import com.cramtale.core.match.DeckIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which AnkiDroid to read, access, and the probe report. */
@Composable
fun TargetScreen(state: AppState, onProbe: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(state.targetPackage) }
    val installs = remember(refresh) {
        AnkiReader.installed(context).map { i ->
            InstallUi(i.target.packageName,
                if (i.target.isReal) "AnkiDroid (your real collection)" else "AnkiDroid parallel build (${i.target.packageName.substringAfterLast('.')})",
                "Version ${i.versionName}" + if (!i.providerFound) " · its provider wasn't found" else "", i.granted, i.target.isReal)
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    Page {
        Text("Cramtale reads your decks and Anki's due queue from the AnkiDroid you pick here. In this version it never writes.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (i in installs) PaperCard(onClick = { selected = i.packageName; state.targetPackage = i.packageName }) {
            Row { Text(i.title, style = MaterialTheme.typography.titleSmall) }
            Text(i.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (i.packageName == selected) Pill("Selected", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            if (!i.granted) SecondaryButton("Allow access", { ask.launch(com.cramtale.app.anki.AnkiTarget(i.packageName).permission) })
        }
        if (installs.isEmpty()) ErrorNote("AnkiDroid isn't installed on this phone.")
        SecondaryButton("Run the probe", onProbe)
    }
}

@Composable
fun ProbeScreen(state: AppState) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            val t = state.target ?: return@withContext "Pick an AnkiDroid first."
            runCatching { Probe.run(context, t) }.getOrElse { "Probe failed: $it" }
        }
    }
    Page {
        Text("What this AnkiDroid's interface offers. Copy it and send it back if something doesn't work.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val r = report
        if (r == null) Busy("Asking AnkiDroid…") else {
            PrimaryButton("Copy report", { copy(context, "Cramtale probe", r) }, icon = Icons.Filled.ContentCopy)
            PaperCard { SelectionContainer { Text(r, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }
        }
    }
}

@Composable
fun DeckScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(deckRows(state)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var unresolved by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(rows) {
        unresolved = withContext(Dispatchers.IO) { state.jmdict()?.let { d -> DeckIndex(state.deck(), d).unresolved.map { it.word } }.orEmpty() }
    }
    Page {
        if (busy != null) Busy(busy!!)
        if (error != null) ErrorNote(error!!)
        if (rows.isNotEmpty()) {
            PaperCard {
                Text("%,d words".format(rows.sumOf { it.total }), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("%,d known · %,d not studied yet".format(rows.sumOf { it.known }, rows.sumOf { it.new }),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PaperCard {
                for (r in rows) Row {
                    Text(r.name.substringAfterLast("::"), androidx.compose.ui.Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("%,d · %,d new".format(r.total, r.new), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SecondaryButton("Read again from AnkiDroid", {
            scope.launch {
                busy = "Reading…"
                error = withContext(Dispatchers.IO) { runCatching { state.reloadDeck { s -> busy = s }; null }.getOrElse { it.message } }
                rows = deckRows(state)
                busy = null
            }
        }, icon = Icons.Filled.Refresh)
        if (unresolved.isNotEmpty()) PaperCard {
            Text("Not in the dictionary (${unresolved.size})", style = MaterialTheme.typography.titleSmall)
            Text("Still matched by spelling: " + unresolved.joinToString("、"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun copy(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
