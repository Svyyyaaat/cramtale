package com.cramtale.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cramtale.app.AppState
import com.cramtale.app.anki.AnkiReader
import com.cramtale.app.anki.AnkiTarget
import com.cramtale.app.anki.Probe
import com.cramtale.core.match.CardState
import com.cramtale.core.match.DeckIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun HomeScreen(state: AppState, go: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    var dictStatus by remember { mutableStateOf("Checking…") }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            dictStatus = withContext(Dispatchers.IO) {
                runCatching { state.dictionary.import(uri) { s -> dictStatus = s }; state.dictionaryRebuilt(); "Dictionary ready" }
                    .getOrElse { "Import failed: ${it.message}" }
            }
            busy = false
            refresh++
        }
    }
    LaunchedEffect(refresh) {
        dictStatus = withContext(Dispatchers.IO) {
            state.jmdict()?.let { "JMdict ready (${it.entryCount()} entries)" } ?: "Not set up yet"
        }
    }
    Page {
        Section("Story") {
            Text("Read-only: due words from Anki are woven in and graded in a log; nothing is sent to AnkiDroid yet.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { go(Screen.STORY) }) { Text("Play") }
                OutlinedButton(onClick = { go(Screen.LOGS) }) { Text("Logs") }
                OutlinedButton(onClick = { go(Screen.FILES) }) { Text("Files") }
            }
        }
        Section("1. AnkiDroid") {
            Text(state.target?.let { "Connected to ${it.packageName}" } ?: "Pick which AnkiDroid to read: your copy (parallel build) or the real one.")
            Button(onClick = { go(Screen.TARGET) }) { Text("Choose AnkiDroid") }
            OutlinedButton(onClick = { go(Screen.PROBE) }, enabled = state.target != null) { Text("Run probe") }
        }
        Section("2. Dictionary (JMdict)") {
            Text(dictStatus)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        dictStatus = withContext(Dispatchers.IO) {
                            runCatching { state.dictionary.download { s -> dictStatus = s }; state.dictionaryRebuilt(); "Dictionary ready" }
                                .getOrElse { "Download failed: ${it.message}" }
                        }
                        busy = false
                        refresh++
                    }
                }) { Text("Download (10 MB)") }
                OutlinedButton(enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) }) { Text("From file") }
            }
        }
        Section("3. Your deck") {
            val t = state.deckSnapshotTime
            Text(if (t == null) "Not read yet" else "Snapshot from ${DateFormat.getDateTimeInstance().format(Date(t))}")
            Button(onClick = { go(Screen.DECK) }) { Text("Deck words") }
        }
        Section("4. Vocabulary check (2 minutes)") {
            Text("About 80 words outside your decks, from common to rare. Know it or not? Sets the frequency cut-off " +
                "where you stop knowing 90% of words. Currently: ${state.policy.frequencyThreshold}.")
            Button(onClick = { go(Screen.VOCAB) }) { Text("Start") }
        }
        Section("5. Calibrate on real text") {
            Text("Paste Japanese text and see which words count as unknown. Mark what you actually know.")
            Button(onClick = { go(Screen.CALIBRATE) }) { Text("Calibrate") }
        }
    }
}

@Composable
fun TargetScreen(state: AppState, changed: () -> Unit) {
    val context = LocalContext.current
    var installed by remember { mutableStateOf(AnkiReader.installed(context)) }
    var selected by remember { mutableStateOf(state.targetPackage) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        installed = AnkiReader.installed(context)
    }
    Page {
        Text(
            "Cramtale only reads in this step. For development use a parallel AnkiDroid build " +
                "(com.ichi2.anki.a–e) with a backup of your collection imported. Never log the copy into sync, " +
                "and in the copy create an empty deck named ${AnkiTarget.SANDBOX_MARKER_DECK}. " +
                "Later steps refuse to write anywhere that deck is missing.",
        )
        if (installed.isEmpty()) Text("No AnkiDroid installed.", color = MaterialTheme.colorScheme.error)
        for (i in installed) {
            Section(i.target.packageName + if (i.target.isReal) "  (your real AnkiDroid)" else "  (parallel build)") {
                Text("Version ${i.versionName} · provider ${if (i.providerFound) "found" else "MISSING"} · access ${if (i.granted) "granted" else "not granted"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!i.granted) OutlinedButton(onClick = { ask.launch(i.target.permission) }) { Text("Grant access") }
                    Button(enabled = selected != i.target.packageName, onClick = {
                        state.targetPackage = i.target.packageName
                        selected = i.target.packageName
                        changed()
                    }) { Text(if (selected == i.target.packageName) "Selected" else "Use this one") }
                }
            }
        }
    }
}

@Composable
fun ProbeScreen(state: AppState) {
    val context = LocalContext.current
    var report by remember { mutableStateOf("Running…") }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            val t = state.target ?: return@withContext "Pick an AnkiDroid first."
            runCatching { Probe.run(context, t) }.getOrElse { "Probe failed: $it" }
        }
    }
    Page {
        Button(onClick = { copy(context, "Cramtale probe", report) }) { Text("Copy report") }
        SelectionContainer { Text(report, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}

@Composable
fun DeckScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    suspend fun summarize() {
        summary = withContext(Dispatchers.IO) {
            val words = state.deck()
            if (words.isEmpty()) return@withContext "No snapshot yet: read it from AnkiDroid."
            val sb = StringBuilder("Deck words: ${words.size}\n")
            words.groupBy { it.deck }.toSortedMap().forEach { (deck, ws) ->
                val s = ws.groupingBy { it.state }.eachCount()
                sb.append("\n$deck: ${ws.size}\n  studied ${s[CardState.STUDIED] ?: 0} · studied+suspended ${s[CardState.STUDIED_SUSPENDED] ?: 0} · " +
                    "new ${s[CardState.NEW] ?: 0} · new+suspended ${s[CardState.NEW_SUSPENDED] ?: 0}\n")
            }
            state.jmdict()?.let { d ->
                val unresolved = DeckIndex(words, d).unresolved
                sb.append("\nNot in JMdict (${unresolved.size}, still matched by spelling):\n")
                sb.append(unresolved.joinToString("  ") { it.word })
            }
            sb.toString()
        }
    }
    LaunchedEffect(Unit) { summarize() }
    Page {
        Text("Reads every note of your four note types and the state of their recognition cards. Nothing is written.")
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                status = withContext(Dispatchers.IO) {
                    runCatching { state.reloadDeck { s -> status = s }; "Done" }.getOrElse { "Failed: ${it.message}" }
                }
                summarize()
                busy = false
            }
        }) { Text("Read from AnkiDroid") }
        if (status.isNotEmpty()) Text(status)
        Spacer(Modifier.height(4.dp))
        SelectionContainer { Text(summary) }
    }
}

internal fun copy(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
