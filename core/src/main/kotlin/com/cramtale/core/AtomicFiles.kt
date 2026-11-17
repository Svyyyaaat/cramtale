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
