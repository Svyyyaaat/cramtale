package com.cramtale.app.anki

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import androidx.core.content.ContextCompat
import com.cramtale.core.match.NoteTypeRule
import com.cramtale.core.match.RawCard
import com.cramtale.core.match.RawNote
import com.cramtale.core.review.AnkiSource
import com.cramtale.core.review.CardInfo
import com.cramtale.core.review.CardRef

/**
 * One AnkiDroid install: the real one (com.ichi2.anki) or a parallel build (com.ichi2.anki.a … .e).
 * Each has its own collection, its own provider at "<package>.flashcards" and its own permission.
 */
data class AnkiTarget(val packageName: String) {
    val authority get() = "$packageName.flashcards"
    val permission get() = "$packageName.permission.READ_WRITE_DATABASE"
    val isReal get() = packageName == REAL

    companion object {
        const val REAL = "com.ichi2.anki"
        val ALL = listOf(REAL) + ('a'..'e').map { "$REAL.$it" }
        /** A deck the user creates in the copy only. Writes (from step 3) need it. */
        const val SANDBOX_MARKER_DECK = "CRAMTALE-SANDBOX"
    }
}

data class InstalledAnki(val target: AnkiTarget, val versionName: String?, val providerFound: Boolean, val granted: Boolean)

data class Deck(val id: Long, val name: String, val learn: Int, val review: Int, val new: Int)

data class NoteType(val id: Long, val name: String, val fields: List<String>)

/**
 * Read-only access to an AnkiDroid collection through its public content provider, the same
 * provider and URI scheme Cram Dungeon uses (content://<authority>/notes, /decks, /models, /schedule).
 * Nothing here writes. Call off the main thread.
 */
class AnkiReader(private val context: Context, val target: AnkiTarget) : AnkiSource {
    private val resolver: ContentResolver get() = context.contentResolver
    private val base = Uri.parse("content://${target.authority}")
    private val notesUri = Uri.withAppendedPath(base, "notes")
    private val decksUri = Uri.withAppendedPath(base, "decks")
    private val modelsUri = Uri.withAppendedPath(base, "models")
    val scheduleUri: Uri = Uri.withAppendedPath(base, "schedule")

    fun cardUri(noteId: Long, ord: Int): Uri =
        Uri.withAppendedPath(Uri.withAppendedPath(Uri.withAppendedPath(notesUri, noteId.toString()), "cards"), ord.toString())

    fun hasPermission() = ContextCompat.checkSelfPermission(context, target.permission) == PackageManager.PERMISSION_GRANTED

    fun decks(): List<Deck> = query(decksUri, null, null) { c ->
        val counts = c.string("deck_count")?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
        Deck(c.long("deck_id"), c.string("deck_name") ?: "", counts?.optInt(0) ?: 0, counts?.optInt(1) ?: 0, counts?.optInt(2) ?: 0)
    }.sortedBy { it.name.lowercase() }

    fun noteTypes(): List<NoteType> = query(modelsUri, arrayOf("_id", "name", "field_names"), null) { c ->
        NoteType(c.long("_id"), c.string("name") ?: "", (c.string("field_names") ?: "").split('\u001f'))
    }

    /** Template ord → name for a note type. */
    fun templates(modelId: Long): Map<Int, String> {
        val uri = Uri.withAppendedPath(Uri.withAppendedPath(modelsUri, modelId.toString()), "templates")
        return query(uri, arrayOf("ord", "name"), null) { c -> c.int("ord") to (c.string("name") ?: "") }.toMap()
    }

    /** Note ids matching an Anki search string. */
    fun search(search: String): Set<Long> = query(notesUri, arrayOf("_id"), search) { it.long("_id") }.toSet()

    /**
     * Every note of the rule's type, with the state of its recognition cards. Card states come
     * from a few searches per template (is:new, is:suspended) instead of one query per card,
     * so 7000 notes take seconds rather than minutes.
     */
    fun notesFor(rule: NoteTypeRule, deckNames: Map<Long, String>, progress: (String) -> Unit = {}): List<RawNote> {
        val type = noteTypes().firstOrNull { it.name == rule.noteType } ?: return emptyList()
        val templates = templates(type.id).filterValues { it in rule.recognitionTemplates }
        if (templates.isEmpty()) return emptyList()
        val q = "note:\"${rule.noteType.replace("\"", "\\\"")}\""
        progress("${rule.noteType}: notes")
        val notes = query(notesUri, arrayOf("_id", "flds", "tags"), q) { c ->
            Triple(c.long("_id"), c.string("flds") ?: "", c.string("tags") ?: "")
        }
        val newSets = HashMap<Int, Set<Long>>()
        val suspendedSets = HashMap<Int, Set<Long>>()
        for ((ord, name) in templates) {
            progress("${rule.noteType}: $name")
            val card = "\"card:${name.replace("\"", "\\\"")}\""
            newSets[ord] = search("$q $card is:new")
            suspendedSets[ord] = search("$q $card is:suspended")
        }
        // the deck of each note: one search per deck the type appears in
        val noteDeck = HashMap<Long, String>()
        for ((_, deckName) in deckNames) {
            if (noteDeck.size == notes.size) break
            search("$q \"deck:${deckName.replace("\"", "\\\"")}\" -\"deck:${deckName.replace("\"", "\\\"")}::*\"")
                .forEach { noteDeck.putIfAbsent(it, deckName) }
        }
        return notes.map { (id, flds, tags) ->
            val values = flds.split('\u001f')
            RawNote(
                noteId = id,
                deck = noteDeck[id] ?: "",
                noteType = rule.noteType,
                fields = type.fields.mapIndexed { i, f -> f to values.getOrElse(i) { "" } }.toMap(),
                cards = templates.map { (ord, name) ->
                    RawCard(ord, name, if (id in newSets[ord]!!) 0 else 2, if (id in suspendedSets[ord]!!) -1 else 0)
                },
                tags = tags.trim(),
            )
        }
    }

    /** The scheduler's queue for a deck (what Anki would show next, with its own limits applied). */
    override fun queue(deckId: Long, limit: Int): List<CardRef> =
        query(scheduleUri, null, "limit=?,deckID=?", arrayOf(limit.toString(), deckId.toString())) { c ->
            CardRef(c.long("note_id"), c.int("ord"))
        }

    /** The card's scheduling state; reps and ivl only where this AnkiDroid reports them. */
    override fun card(ref: CardRef): CardInfo? {
        val uri = cardUri(ref.noteId, ref.ord)
        for (cols in listOf(arrayOf("type", "queue", "due", "reps", "ivl"), arrayOf("type", "queue", "due", "reps"), arrayOf("type", "queue", "due"))) {
            val r = runCatching {
                query(uri, cols, null) { c ->
                    CardInfo(c.getInt(0), c.getInt(1), c.getLong(2), if (cols.size > 3) c.getInt(3) else -1, if (cols.size > 4) c.getInt(4) else -1)
                }.firstOrNull()
            }
            if (r.isSuccess) return r.getOrNull()
        }
        return null
    }

    fun <T> query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>? = null, row: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        resolver.query(uri, projection, selection, args, null)?.use { c -> while (c.moveToNext()) out += row(c) }
        return out
    }

    companion object {
        fun installed(context: Context): List<InstalledAnki> = AnkiTarget.ALL.mapNotNull { pkg ->
            val info = runCatching {
                context.packageManager.getPackageInfo(pkg, PackageManager.GET_PROVIDERS)
            }.getOrNull() ?: return@mapNotNull null
            val target = AnkiTarget(pkg)
            InstalledAnki(
                target, info.versionName,
                providerFound = info.providers?.any { it.authority?.split(';')?.contains(target.authority) == true } == true,
                granted = ContextCompat.checkSelfPermission(context, target.permission) == PackageManager.PERMISSION_GRANTED,
            )
        }
    }
}

internal fun Cursor.long(col: String) = getLong(getColumnIndexOrThrow(col))
internal fun Cursor.int(col: String) = getInt(getColumnIndexOrThrow(col))
internal fun Cursor.string(col: String): String? = getColumnIndex(col).takeIf { it >= 0 }?.let { getString(it) }
