package com.cramtale.app.story

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.cramtale.core.llm.LlmException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Claude Code in Termux, the way Auto Anki Miner runs it: the app starts claude_call.py through Termux's
 * RUN_COMMAND intent (needs `allow-external-apps = true`, set by setup.sh) and gets its printed line back
 * through a PendingIntent. Requests and the growing reply go through files in ~/.cramtale/calls, which
 * the app reaches through Termux's documents provider (the folder the user granted in setup).
 */
object Termux {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val HOME = "/data/data/com.termux/files/home"
    const val DOCUMENTS_AUTHORITY = "com.termux.documents"
    private const val PYTHON = "/data/data/com.termux/files/usr/bin/python"
    const val BASE = "$HOME/.cramtale"
    private const val CALLS = "$BASE/calls"
    const val SETUP_COMMAND = "bash ~/.cramtale/setup.sh"
    private val SCRIPTS = listOf("claude_call.py", "setup.sh")
    private const val EXTRA_CALL = "call"

    private fun prefs(c: Context) = c.getSharedPreferences("termux", Context.MODE_PRIVATE)
    fun tree(c: Context): Uri? = prefs(c).getString("tree", null)?.let(Uri::parse)
    fun setTree(c: Context, uri: Uri) = prefs(c).edit().putString("tree", uri.toString()).apply()

    fun installed(c: Context) = runCatching { c.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess
    fun hasPermission(c: Context) = ContextCompat.checkSelfPermission(c, PERMISSION) == PackageManager.PERMISSION_GRANTED
    fun hasFolderAccess(c: Context): Boolean {
        val tree = tree(c) ?: return false
        return c.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
    }
    fun scriptsInstalled(c: Context) = hasFolderAccess(c) && exists(c, doc(c, "$BASE/claude_call.py"))
    /** Termux, the folder and the permission: everything the app can check (not the login). */
    fun ready(c: Context) = installed(c) && hasFolderAccess(c) && hasPermission(c) && scriptsInstalled(c)

    /** What Android knows about Termux, for when the permission can't be granted. */
    fun diagnose(c: Context): String {
        val pm = c.packageManager
        val info = runCatching { pm.getPackageInfo(PACKAGE, 0) }.getOrNull() ?: return "Termux not installed"
        val permission = runCatching { pm.getPermissionInfo(PERMISSION, 0) }.getOrNull()
        val service = runCatching { pm.getServiceInfo(ComponentName(PACKAGE, "com.termux.app.RunCommandService"), 0) }.getOrNull()
        return listOf(
            "Termux ${info.versionName}",
            "RUN_COMMAND permission " + if (permission == null) "not defined by this Termux (use the F-Droid or GitHub build)" else "defined",
            "RunCommandService " + if (service == null) "missing" else "present",
        ).joinToString("\n")
    }

    private fun doc(c: Context, path: String) = DocumentsContract.buildDocumentUriUsingTree(tree(c) ?: error("Pick the Termux home folder first"), path)
    private fun exists(c: Context, uri: Uri) = runCatching {
        c.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    /** Termux's provider opens only files that exist: create the folder and file first. */
    private fun ensure(c: Context, dirPath: String, name: String?): Uri {
        val r = c.contentResolver
        var dir = HOME
        for (part in dirPath.removePrefix("$HOME/").split('/').filter { it.isNotEmpty() }) {
            val next = "$dir/$part"
            if (!exists(c, doc(c, next))) DocumentsContract.createDocument(r, doc(c, dir), DocumentsContract.Document.MIME_TYPE_DIR, part)
                ?: error("couldn't create $next")
            dir = next
        }
        if (name == null) return doc(c, dir)
        val file = doc(c, "$dir/$name")
        return if (exists(c, file)) file else DocumentsContract.createDocument(r, doc(c, dir), "application/octet-stream", name)
            ?: error("couldn't create $name")
    }

    /** Copies claude_call.py and setup.sh into ~/.cramtale, so nothing big has to be pasted into Termux. */
    fun installScripts(c: Context) {
        for (name in SCRIPTS) {
            c.contentResolver.openOutputStream(ensure(c, BASE, name), "wt")!!.use { out -> c.assets.open("termux/$name").use { it.copyTo(out) } }
        }
        ensure(c, CALLS, null)
    }

    private val waiting = ConcurrentHashMap<String, CompletableFuture<String>>()
    /** Each call is a Node process on the phone: a few at once, not every intent in parallel. */
    private val slots = Semaphore(3)

    /** One call: the request in, claude_call.py's printed line out; [onText] gets the reply as it grows. */
    fun run(c: Context, request: JSONObject, onText: ((String) -> Unit)?): String {
        if (!installed(c)) throw LlmException("Termux isn't installed (Settings → Claude)")
        if (!hasPermission(c)) throw LlmException("Allow Cramtale to run commands in Termux (Settings → Claude)")
        if (!hasFolderAccess(c)) throw LlmException("Give Cramtale the Termux folder (Settings → Claude)")
        slots.acquire()
        val id = UUID.randomUUID().toString().take(12)
        val reqDoc = ensure(c, CALLS, "$id.json")
        val streamPath = "$CALLS/$id.txt"
        val streamDoc = ensure(c, CALLS, "$id.txt")
        val future = CompletableFuture<String>().also { waiting[id] = it }
        try {
            c.contentResolver.openOutputStream(reqDoc, "wt")!!.use { it.write(request.toString().toByteArray()) }
            val pending = PendingIntent.getBroadcast(c, id.hashCode(),
                Intent(c, TermuxResultReceiver::class.java).putExtra(EXTRA_CALL, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val intent = Intent("com.termux.RUN_COMMAND")
                .setClassName(PACKAGE, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", PYTHON)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("$BASE/claude_call.py", "$CALLS/$id.json", streamPath))
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Cramtale: ${request.optString("model")}")
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
            try { c.startService(intent) } catch (e: Exception) {
                throw LlmException("Android didn't let Cramtale start Termux (${e.javaClass.simpleName}). Open the app and retry.", retryable = true, cause = e)
            }
            val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10)
            var shown = 0
            while (true) {
                val line = runCatching { future.get(if (onText == null) 1000 else 250, TimeUnit.MILLISECONDS) }.getOrNull()
                if (onText != null) {
                    val text = runCatching { c.contentResolver.openInputStream(streamDoc)!!.use { String(it.readBytes()) } }.getOrNull().orEmpty()
                    if (text.length > shown) { onText(text.substring(shown)); shown = text.length }
                }
                if (line != null) return line
                if (System.currentTimeMillis() > deadline) throw LlmException("Claude Code took longer than 10 minutes", retryable = true)
            }
        } finally {
            waiting.remove(id)
            slots.release()
            runCatching { DocumentsContract.deleteDocument(c.contentResolver, reqDoc) }
            runCatching { DocumentsContract.deleteDocument(c.contentResolver, streamDoc) }
        }
    }

    internal fun finished(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_CALL) ?: return
        val result = intent.getBundleExtra("result")
        val stdout = result?.getString("stdout").orEmpty()
        val line = stdout.ifBlank {
            val problem = listOfNotNull(result?.getString("errmsg"), result?.getString("stderr")?.takeLast(600))
                .filter { it.isNotBlank() }.joinToString("\n").ifBlank { "no output" }
            JSONObject().put("ok", false).put("error", "Termux: $problem").put("retryable", true).toString()
        }
        waiting[id]?.complete(line)
    }
}

/** claude_call.py's printed line, back from Termux. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Termux.finished(intent)
}
