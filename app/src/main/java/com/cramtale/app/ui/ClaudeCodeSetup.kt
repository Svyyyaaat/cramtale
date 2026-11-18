package com.cramtale.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.cramtale.app.AppState
import com.cramtale.app.story.Termux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setting up Claude Code in Termux, the same steps as Auto Anki Miner: Termux, its home folder, the
 * scripts and one command pasted into Termux, the permission to run commands, then a test call.
 */
@Composable
fun ClaudeCodeSetup(state: AppState, onReady: (() -> Unit)? = null, intro: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // the checks are read again whenever the app comes back from Termux or a system screen
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            Termux.setTree(context, uri)
            tick++
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    var testing by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<String?>(null) }
    var passed by remember { mutableStateOf(false) }

    val installed = remember(tick) { Termux.installed(context) }
    val hasFolder = remember(tick) { Termux.hasFolderAccess(context) }
    val scripts = remember(tick) { runCatching { Termux.scriptsInstalled(context) }.getOrDefault(false) }
    val allowed = remember(tick) { Termux.hasPermission(context) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (intro) SetupHint("Uses your Claude subscription through Claude Code in Termux instead of an API key. Calls are slower to start; nothing is billed per turn.")
        SetupStep("Install Termux (F-Droid or GitHub build) and open it once", installed) {
            if (!installed) TextButton({ open(context, "https://f-droid.org/packages/com.termux/") }) { Text("Get") }
        }
        SetupStep("Give Cramtale Termux's home folder (in the picker: ☰ → Termux → Use this folder)", hasFolder, enabled = installed) {
            TextButton({ folder.launch(DocumentsContract.buildDocumentUri(Termux.DOCUMENTS_AUTHORITY, Termux.HOME)) }) { Text("Pick") }
        }
        SetupStep("Install the scripts, paste the copied command into Termux, then run `claude` there once to log in", scripts, enabled = hasFolder) {
            TextButton({
                runCatching { Termux.installScripts(context) }.onFailure { toast(context, "Couldn't write into Termux: ${it.message}") }.onSuccess {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Cramtale setup", Termux.SETUP_COMMAND))
                    toast(context, "Copied. In Termux: long-press → Paste, then Enter")
                    tick++
                }
            }) { Text(if (scripts) "Again" else "Install") }
        }
        SetupStep("Allow Cramtale to run commands in Termux", allowed, enabled = installed) {
            TextButton({ permission.launch(Termux.PERMISSION) }) { Text("Allow") }
        }
        if (installed && !allowed) SetupHint(remember(tick) { Termux.diagnose(context) })
        SetupStep("Optional: turn off battery optimisation for Termux, so Android doesn't stop it mid-turn", null, enabled = installed) {
            TextButton({
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${Termux.PACKAGE}"))) }
            }) { Text("Open") }
        }
        SetupStep("Test a call (the first one can take a minute)", if (testing) null else passed, enabled = installed && hasFolder && scripts && allowed) {
            TextButton({
                testing = true; test = null
                scope.launch {
                    val problem = withContext(Dispatchers.IO) { state.story.testClaudeCode() }
                    testing = false; passed = problem == null
                    test = problem ?: "Claude Code answered."
                    if (passed) onReady?.invoke()
                }
            }, enabled = !testing) { Text(if (testing) "Testing…" else "Test") }
        }
        test?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = if (passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SetupStep(text: String, done: Boolean?, enabled: Boolean = true, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (done != null) Icon(if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, Modifier.size(20.dp),
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        else Spacer(Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        if (enabled) action()
    }
}

private fun open(context: Context, url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
@Composable
private fun SetupHint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
