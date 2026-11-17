package com.cramtale.core.match

import com.cramtale.core.dict.FormHit
import com.cramtale.core.dict.Jmdict
import com.cramtale.core.text.Kana
import com.cramtale.core.text.Tokenizer

/**
 * Deck words keyed two ways: by their exact spelling, and by JMdict entry, so spelling
 * variants of the same entry (附属/付属, 何故/なぜ, かつ丼/カツ丼) find the same note.
 */
class DeckIndex(val words: List<DeckWord>, private val dict: Jmdict) {
    private val byText = HashMap<String, MutableList<DeckWord>>()
    private val byEntry = HashMap<Long, MutableList<DeckWord>>()
    /** Deck words JMdict doesn't know (kept for the calibration report). */
    val unresolved = ArrayList<DeckWord>()

    init {
        for (w in words) {
            var found = false
            for (v in w.variants) {
                byText.getOrPut(v) { ArrayList() } += w
                // a reading belongs to the first spelling only
                val entries = Normalize.entriesFor(dict, v, w.reading.takeIf { v == w.word })
                    .ifEmpty { fallback(v) }
                if (entries.isNotEmpty()) found = true
                for (e in entries) byEntry.getOrPut(e) { ArrayList() }.let { if (w !in it) it += w }
            }
            if (!found) unresolved += w
        }
    }

    /**
     * A deck word JMdict lacks as written: a suru-verb or adverb form (経由する → 経由,
     * 完全に → 完全), or something conjugated (食べた → 食べる). Uses its one content word.
     */
    private fun fallback(word: String): Set<Long> {
        for (suffix in listOf("する", "な", "に")) {
            if (word.length > suffix.length && word.endsWith(suffix)) {
                val e = Normalize.entriesFor(dict, word.dropLast(suffix.length), null)
                if (e.isNotEmpty()) return e
            }
        }
        val content = Tokenizer.tokenize(word).filterNot {
            it.isSymbol || it.pos1 == "助詞" || it.pos1 == "助動詞" || (it.pos1 == "名詞" && it.pos2 == "接尾")
        }
        if (content.size != 1 || content[0].base == word) return emptySet()
        return Normalize.entriesFor(dict, content[0].base, null)
    }

    fun find(texts: Collection<String>, entries: Collection<Long>): List<DeckWord> {
        val out = LinkedHashSet<DeckWord>()
        texts.forEach { t -> byText[t]?.let(out::addAll) }
        entries.forEach { e -> byEntry[e]?.let(out::addAll) }
        return out.toList()
    }

    fun hasText(text: String) = text in byText
}

object Normalize {
    /**
     * JMdict entries a written word stands for. Kanji spellings match kanji forms. A kana
     * spelling matches entries normally written in kana (no kanji form, or a sense marked
     * "uk"), so かける doesn't turn into every verb read かける. A known reading narrows
     * entries that share a kanji spelling (方: かた / ほう).
     */
    fun entriesFor(dict: Jmdict, text: String, reading: String?): Set<Long> {
        val hits = dict.lookup(text)
        if (hits.isEmpty()) return emptySet()
        var chosen: List<FormHit> = if (Kana.hasKanji(text)) {
            hits.filter { !it.kana }.ifEmpty { hits }
        } else {
            hits.filter { it.kana && (it.uk || !it.hasKanji) }.ifEmpty { hits.filter { it.kana } }.ifEmpty { hits }
        }
        if (reading != null && chosen.size > 1) {
            val r = Kana.kataToHira(reading)
            val narrowed = chosen.filter { h -> r in dict.forms(h.entry).kana.map(Kana::kataToHira) }
            if (narrowed.isNotEmpty()) chosen = narrowed
        }
        return chosen.map { it.entry }.toSet()
    }

    fun bestRank(dict: Jmdict, entries: Collection<Long>, text: String): Int? {
        val hits = dict.lookup(text).filter { it.entry in entries }
        // the rank of this spelling when it has one, else the entry's
        return hits.mapNotNull { it.formRank ?: it.entryRank }.minOrNull()
    }
}
