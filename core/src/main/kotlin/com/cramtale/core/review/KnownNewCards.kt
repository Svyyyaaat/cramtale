package com.cramtale.core.review

import com.cramtale.core.writeAtomic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * New cards the reader already knows ("Already know it"): what they'd suspend in AnkiDroid. The story
 * stops offering and grading them and the matcher counts the word as known. While the app may not write
 * to Anki, this list is the record of what would be suspended once it may.
 */
class KnownNewCards(private val file: File?) {
    /** [addedWord]: this entry put [word] on the known list (so Undo takes it off; one known before stays). */
    data class Entry(val noteId: Long, val word: String, val at: Long, val suspended: Boolean = false, val addedWord: Boolean = false)

    private val entries = LinkedHashMap<Long, Entry>()

    init {
        runCatching {
            file?.takeIf { it.exists() }?.readText()?.let { JSONArray(it) }?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                    entries[o.getLong("noteId")] = Entry(o.getLong("noteId"), o.getString("word"), o.optLong("at"), o.optBoolean("suspended"),
                        o.optBoolean("addedWord"))
                }
            }
        }
    }

    @get:Synchronized val all: List<Entry> get() = entries.values.toList()
    @get:Synchronized val noteIds: Set<Long> get() = entries.keys.toSet()

    @Synchronized operator fun contains(noteId: Long) = noteId in entries

    @Synchronized fun add(noteId: Long, word: String, addedWord: Boolean = false) {
        if (noteId in entries) return
        entries[noteId] = Entry(noteId, word, System.currentTimeMillis(), addedWord = addedWord)
        save()
    }

    @Synchronized fun remove(noteId: Long): Entry? = entries.remove(noteId)?.also { save() }

    private fun save() {
        file?.writeAtomic(JSONArray(entries.values.map {
            JSONObject().put("noteId", it.noteId).put("word", it.word).put("at", it.at).put("suspended", it.suspended).put("addedWord", it.addedWord)
        }).toString())
    }
}
