package com.cramtale.app.dict

import android.content.Context
import android.net.Uri
import com.cramtale.core.dict.Jmdict
import com.cramtale.core.dict.JmdictBuilder
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * JMdict on the phone: downloaded once (about 10 MB) and built into SQLite (about 50 MB),
 * or imported from a JMdict_e(.gz) file the user picks. Call off the main thread.
 */
class DictionarySetup(private val context: Context) {
    val dbFile: File get() = context.getDatabasePath("jmdict.sqlite")

    // counting 200,000 entries takes a moment: done once, then remembered while the file is the same
    @Volatile private var readyFor: Long = -1

    fun isReady(): Boolean {
        if (!dbFile.exists()) return false
        val stamp = dbFile.lastModified() xor dbFile.length()
        if (readyFor == stamp) return true
        val ok = runCatching { AndroidSql(dbFile.path).let { sql -> Jmdict(sql).entryCount().also { sql.close() } } > 100_000 }.getOrDefault(false)
        if (ok) readyFor = stamp
        return ok
    }

    fun open(): Jmdict = Jmdict(AndroidSql(dbFile.path))

    /** A prebuilt database shipped in the APK (tools/build_dict_asset.sh); absent in CI builds. */
    // the build tools ungzip a .gz asset and store it compressed in the APK under the plain name
    private fun bundledName(): String? = runCatching { context.assets.list("").orEmpty() }.getOrDefault(emptyArray())
        .firstOrNull { it == BUNDLED || it == BUNDLED.removeSuffix(".gz") }

    fun hasBundled(): Boolean = bundledName() != null

    /** Unpacks the shipped database: a few seconds instead of downloading and building. */
    fun installBundled(progress: (String) -> Unit) {
        progress("Unpacking the dictionary…")
        val partial = File(dbFile.path + ".part")
        partial.parentFile?.mkdirs()
        val name = bundledName() ?: error("no dictionary in this build")
        context.assets.open(name).use { raw ->
            val input = if (name.endsWith(".gz")) java.util.zip.GZIPInputStream(raw, 1 shl 16) else raw
            input.use { i -> partial.outputStream().use { i.copyTo(it, 1 shl 16) } }
        }
        dbFile.delete()
        if (!partial.renameTo(dbFile)) error("couldn't move the dictionary into place")
        progress("Dictionary ready")
    }

    fun download(progress: (String) -> Unit) {
        val conn = URL(URL_JMDICT).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200) error("download failed: HTTP ${conn.responseCode}")
            val tmp = File(context.cacheDir, "JMdict_e.gz")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            progress("Downloaded ${tmp.length() / 1_000_000} MB, building…")
            tmp.inputStream().use { build(it, gzipped = true, progress) }
            tmp.delete()
        } finally {
            conn.disconnect()
        }
    }

    fun import(uri: Uri, progress: (String) -> Unit) {
        val head = ByteArray(2)
        val n = context.contentResolver.openInputStream(uri)!!.use { it.read(head) }
        val gz = n == 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
        context.contentResolver.openInputStream(uri)!!.use { build(it, gz, progress) }
    }

    private fun build(input: InputStream, gzipped: Boolean, progress: (String) -> Unit) {
        val partial = File(dbFile.path + ".part")
        partial.parentFile?.mkdirs()
        partial.delete()
        File(partial.path + "-journal").delete()
        val sql = AndroidSql(partial.path)
        try {
            JmdictBuilder.build(JmdictBuilder.open(input, gzipped), sql) { progress("Building dictionary: $it entries") }
        } finally {
            sql.close()
        }
        dbFile.delete()
        if (!partial.renameTo(dbFile)) error("couldn't move the dictionary into place")
        progress("Dictionary ready")
    }

    companion object {
        const val URL_JMDICT = "https://www.edrdg.org/pub/Nihongo/JMdict_e.gz"
        const val BUNDLED = "jmdict.sqlite.gz"
    }
}
