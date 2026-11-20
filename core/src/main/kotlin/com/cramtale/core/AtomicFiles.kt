package com.cramtale.core

import java.io.File

/**
 * Writes through a temporary file and a rename, so a process killed mid-write (Android does that to
 * background apps) leaves the old file or the new one, never half of one.
 */
fun File.writeAtomic(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, ".$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) {
        delete()
        if (!tmp.renameTo(this)) { writeText(text); tmp.delete() }
    }
}

/** JSON lines, skipping a torn last line (an append cut short by a kill). */
fun File.jsonLines(): List<org.json.JSONObject> = if (!exists()) emptyList() else
    readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { org.json.JSONObject(it) }.getOrNull() }

/** The last [n] lines, read from the end: a long game's log is too big to read whole for a few lines. */
fun File.tailLines(n: Int): List<String> {
    if (!exists() || n <= 0) return emptyList()
    java.io.RandomAccessFile(this, "r").use { r ->
        var pos = r.length()
        val chunk = 64 * 1024
        var bytes = ByteArray(0)
        while (pos > 0) {
            val size = minOf(chunk.toLong(), pos).toInt()
            pos -= size
            val b = ByteArray(size); r.seek(pos); r.readFully(b)
            bytes = b + bytes
            if (bytes.count { it == '\n'.code.toByte() } > n) break
        }
        return bytes.decodeToString().lines().filter { it.isNotBlank() }.takeLast(n)
    }
}

/** [tailLines] as JSON objects, skipping a torn line. */
fun File.tailJson(n: Int): List<org.json.JSONObject> = tailLines(n).mapNotNull { runCatching { org.json.JSONObject(it) }.getOrNull() }
