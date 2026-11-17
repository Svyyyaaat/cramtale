package com.cramtale.core.match

import com.cramtale.core.text.Kana

/** One card of a note as AnkiDroid reports it (Anki's type: 0 new, 1 learning, 2 review, 3 relearning; queue < 0: suspended/buried). */
data class RawCard(val ord: Int, val template: String, val type: Int, val queue: Int)

/** A note as read from the collection: deck name with "::" separators, fields by name. */
data class RawNote(
    val noteId: Long,
    val deck: String,
    val noteType: String,
    val fields: Map<String, String>,
    val cards: List<RawCard>,
    val tags: String = "",
)

/** How to read the word out of a note type, and which of its templates are recognition cards. */
data class NoteTypeRule(
    val noteType: String,
    val wordField: String,
    val readingField: String? = null,
    val recognitionTemplates: Set<String>,
    /** The card's own meaning, shown when the word is tapped (null: the dictionary's). */
    val meaningField: String? = null,
)

enum class CardState { STUDIED, STUDIED_SUSPENDED, NEW, NEW_SUSPENDED }

data class DeckWord(
    val noteId: Long,
    val deck: String,
    val noteType: String,
    /** The first spelling, for display. */
    val word: String,
    val reading: String?,
    val state: CardState,
    val cards: List<RawCard>,
    /** Every spelling the field lists (良い，いい → 良い, いい). */
    val variants: List<String> = listOf(word),
    /** The meaning on the user's card (HTML stripped), if the note type has one. */
    val meaning: String? = null,
)

object DeckDefaults {
    /** The four note types in the user's collection (PLAN.md §1). */
    val rules = listOf(
        NoteTypeRule("Japanese Core 5k", "Lemma", null, setOf("Recognition"), meaningField = "English Gloss"),
        NoteTypeRule("Japanese (Furigana1, Kanji2 V2K)", "Expression", "Reading", setOf("Furigana1", "Kanji2"), meaningField = "Meaning"),
        NoteTypeRule("基本", "裏", null, setOf("カード:1")),
    )

    const val SUSPEND_QUEUE = -1
}

object WordExtract {
    private val tag = Regex("<[^>]*>")
    private val lineBreak = Regex("(?i)<br\\s*/?>|</div>|</p>|<div[^>]*>")
    private val furigana = Regex(" ?([^ \\[\\]]*?)\\[([^\\]]*)\\]")
    private val brackets = Regex("[(（][^)）]*[)）]")
    private val entities = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'")

    private fun plainLines(html: String): List<String> {
        var s = html.replace(lineBreak, "\n").replace(tag, "")
        entities.forEach { (k, v) -> s = s.replace(k, v) }
        return s.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** A field as plain text: HTML and sound/image references gone, lines joined with "; ". */
    fun plain(field: String): String = plainLines(field.replace(Regex("\\[sound:[^\\]]*\\]"), ""))
        .joinToString("; ").replace(Regex("\\s+"), " ").trim()

    /** The word from a field: first line, no HTML, no furigana brackets, no parenthesised notes or tildes. */
    fun word(field: String): String = variants(field).firstOrNull() ?: ""

    private val dash = Regex("\\s*[–—]\\s*|\\s+-\\s+")
    private val separators = Regex("[,，、/／]")

    /**
     * Every spelling on the field's first line (良い，いい; 聞く，聴く). A gloss after a dash is dropped
     * (確かに – точно), and so is anything without Japanese in it (an English sentence, "DVD").
     */
    fun variants(field: String): List<String> {
        val first = plainLines(field).firstOrNull() ?: return emptyList()
        val head = first.split(dash).firstOrNull { Kana.hasJapanese(it) } ?: return emptyList()
        return head.split(separators).map { v ->
            v.replace(furigana) { it.groupValues[1] }
                .replace(brackets, "")
                .replace(Regex("[～〜~]"), "")
                .replace(" ", "").replace("　", "")
                .trim()
                .trimStart('-', '－')
                .trimEnd('。', '、', '！', '？', '!', '?', '・')
        }.filter { Kana.hasJapanese(it) }.distinct()
    }

    /** Kana reading from a furigana field such as `一[いち]` or ` 食[た]べる` (kana outside brackets kept). */
    fun reading(field: String): String? {
        val first = plainLines(field).firstOrNull() ?: return null
        if ('[' !in first) return null
        return first.replace(furigana) { it.groupValues[2] }.replace(" ", "").trim().ifEmpty { null }
    }
}

object DeckReader {
    fun state(cards: List<RawCard>): CardState {
        val studied = cards.filter { it.type > 0 }
        return when {
            studied.any { it.queue >= 0 } -> CardState.STUDIED
            studied.isNotEmpty() -> CardState.STUDIED_SUSPENDED
            cards.any { it.queue >= 0 } -> CardState.NEW
            else -> CardState.NEW_SUSPENDED
        }
    }

    /** Words from notes whose type has a rule; only the rule's recognition cards count. */
    fun words(notes: List<RawNote>, rules: List<NoteTypeRule> = DeckDefaults.rules): List<DeckWord> {
        val byType = rules.associateBy { it.noteType }
        return notes.mapNotNull { n ->
            val rule = byType[n.noteType] ?: return@mapNotNull null
            val cards = n.cards.filter { it.template in rule.recognitionTemplates }
            if (cards.isEmpty()) return@mapNotNull null
            val variants = WordExtract.variants(n.fields[rule.wordField].orEmpty())
            if (variants.isEmpty()) return@mapNotNull null
            val reading = rule.readingField?.let { n.fields[it] }?.let(WordExtract::reading)
            val meaning = rule.meaningField?.let { n.fields[it] }?.let(WordExtract::plain)?.takeIf { it.isNotBlank() }
            DeckWord(n.noteId, n.deck, n.noteType, variants.first(), reading, state(cards), cards, variants, meaning)
        }
    }
}
