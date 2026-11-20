package com.cramtale.core.story

import com.cramtale.core.writeAtomic
import java.io.File

/**
 * Text files the player gave a story (lore, character sheets, notes, an earlier chat log): kept in
 * state/sources/, used when the world is created and kept in the game master's prompt for the whole story.
 */
class Sources(dir: File) {
    private val folder = File(dir, "state/sources")

    data class Source(val name: String, val text: String)

    fun all(): List<Source> = folder.listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }
        .map { Source(it.name, it.readText()) }

    /** Adds [text] under [name] (a name already taken gets a number). Returns the name it was kept under. */
    fun add(name: String, text: String): String {
        folder.mkdirs()
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "notes.txt" }.take(80)
        var n = clean
        var i = 2
        while (File(folder, n).exists()) { n = clean.substringBeforeLast('.') + " ($i)" + clean.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }; i++ }
        File(folder, n).writeAtomic(text.take(MAX_FILE))
        return n
    }

    fun remove(name: String) { File(folder, name).takeIf { it.parentFile == folder }?.delete() }

    /** Everything, as one block for a prompt, cut at [max] characters; "" when there is nothing. */
    fun material(max: Int): String {
        val all = all().filter { it.text.isNotBlank() }
        if (all.isEmpty()) return ""
        val sb = StringBuilder()
        for (s in all) {
            if (sb.length >= max) break
            sb.append("### ").append(s.name).append('\n').append(s.text.trim().take(max - sb.length)).append("\n\n")
        }
        return sb.toString().trimEnd() + if (all.sumOf { it.text.length } > max) "\n(…cut for length)" else ""
    }

    companion object {
        /** One file's limit: about 25 pages. */
        const val MAX_FILE = 100_000

        /** Text, not a binary file (a PDF, an image): no NUL bytes, and almost every character readable. */
        fun looksLikeText(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return false
            if (bytes.any { it == 0.toByte() }) return false
            val s = bytes.decodeToString()
            val bad = s.count { it == '�' || (it.isISOControl() && it !in "\n\r\t") }
            return bad <= s.length / 100
        }
    }
}
