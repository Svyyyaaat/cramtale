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

    fun isReady(): Boolean = dbFile.exists() && runCatching {
        AndroidSql(dbFile.path).let { sql -> Jmdict(sql).entryCount().also { sql.close() } } > 100_000
    }.getOrDefault(false)

    fun open(): Jmdict = Jmdict(AndroidSql(dbFile.path))

    /** A prebuilt database shipped in the APK (tools/build_dict_asset.sh); absent in CI builds. */
    fun hasBundled(): Boolean = runCatching { context.assets.list("")?.contains(BUNDLED) == true }.getOrDefault(false)

    /** Unpacks the shipped database: a few seconds instead of downloading and building. */
    fun installBundled(progress: (String) -> Unit) {
        progress("Unpacking the dictionary…")
        val partial = File(dbFile.path + ".part")
        partial.parentFile?.mkdirs()
        context.assets.open(BUNDLED).use { raw ->
            java.util.zip.GZIPInputStream(raw, 1 shl 16).use { input -> partial.outputStream().use { input.copyTo(it, 1 shl 16) } }
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
