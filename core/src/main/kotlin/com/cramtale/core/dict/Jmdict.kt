package com.cramtale.core.dict

/** One spelling of a JMdict entry, with what matching needs to know about the entry. */
data class FormHit(
    val entry: Long,
    val kana: Boolean,
    /** Frequency rank of this spelling (null: no priority tag). */
    val formRank: Int?,
    /** Best rank over the whole entry. */
    val entryRank: Int?,
    /** Some sense is marked "usually written in kana". */
    val uk: Boolean,
    val hasKanji: Boolean,
)

data class Sense(val n: Int, val pos: List<String>, val misc: List<String>, val gloss: String)

data class EntryForms(val kanji: List<String>, val kana: List<String>)

class Jmdict(private val db: Sql) {
    private val formCache = object : LinkedHashMap<String, List<FormHit>>(4096, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FormHit>>) = size > 20_000
    }

    fun entryCount(): Int = db.query("SELECT value FROM meta WHERE key = 'entries'") { it.string(0) }
        .firstOrNull()?.toIntOrNull() ?: 0

    /** Every entry that has [text] as a kanji or kana spelling. */
    fun lookup(text: String): List<FormHit> = synchronized(formCache) {
        formCache.getOrPut(text) {
            db.query(
                "SELECT f.entry, f.kana, f.rank, e.rank, e.uk, e.has_kanji FROM form f JOIN entry e ON e.id = f.entry WHERE f.text = ?",
                listOf(text),
            ) { r ->
                FormHit(
                    entry = r.long(0), kana = r.int(1) == 1,
                    formRank = if (r.isNull(2)) null else r.int(2),
                    entryRank = if (r.isNull(3)) null else r.int(3),
                    uk = r.int(4) == 1, hasKanji = r.int(5) == 1,
                )
            }.distinctBy { it.entry to it.kana }
        }
    }

    fun forms(entry: Long): EntryForms {
        val rows = db.query("SELECT text, kana FROM form WHERE entry = ? ORDER BY rowid", listOf(entry)) { it.string(0)!! to (it.int(1) == 1) }
        return EntryForms(rows.filter { !it.second }.map { it.first }, rows.filter { it.second }.map { it.first })
    }

    fun senses(entry: Long): List<Sense> =
        db.query("SELECT n, pos, misc, gloss FROM sense WHERE entry = ? ORDER BY n", listOf(entry)) { r ->
            Sense(r.int(0), r.string(1)!!.split(',').filter { it.isNotBlank() },
                r.string(2)!!.split(',').filter { it.isNotBlank() }, r.string(3)!!)
        }

    fun close() = db.close()
}
