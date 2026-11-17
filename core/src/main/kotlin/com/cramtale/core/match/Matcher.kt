package com.cramtale.core.match

import com.cramtale.core.dict.Jmdict
import com.cramtale.core.text.Kana
import com.cramtale.core.text.Tok
import com.cramtale.core.text.Tokenizer

enum class Status {
    /** Not a word the reader is tested on: particle, auxiliary, interjection, number, name, suffix… */
    IGNORED,
    /** A whitelisted character name. */
    NAME,
    /** In the deck and studied. */
    DECK,
    /** Marked known by the user. */
    MARKED,
    /** Within the frequency threshold. */
    FREQUENT,
    /** A compound or set phrase not known itself, but every part is (持って|くる, 焼け|跡). */
    PARTS_KNOWN,
    /** In the deck but never studied (counts as unknown). */
    DECK_NEW,
    UNKNOWN,
}

/** What the reader is assumed to know. */
data class KnownPolicy(
    /** Words with a JMdict frequency rank at or below this count as known (0 = off). */
    val frequencyThreshold: Int = 6000,
    /** Studied cards you suspended (leeches) still count as known. */
    val studiedSuspendedKnown: Boolean = true,
    /** Never-studied cards you suspended (tagged suspend_me): you suspended them because you know them. */
    val newSuspendedKnown: Boolean = true,
)

/** The user's own lists, keyed by dictionary form. */
data class KnownLists(
    val known: Set<String> = emptySet(),
    val names: Set<String> = emptySet(),
    /** Words the user says aren't words to count (onomatopoeia, sound effects…). */
    val ignored: Set<String> = emptySet(),
)

/** One word (or one ignored token) of the text. */
data class Span(
    val start: Int,
    val end: Int,
    val surface: String,
    /** Dictionary form used for matching. */
    val lemma: String,
    val entries: Set<Long>,
    val rank: Int?,
    val deck: List<DeckWord>,
    /** Why it's ignored, null for words that count. */
    val ignoredBecause: String?,
    /** How many tokenizer tokens it spans (>1 for compounds and set phrases). */
    val tokens: Int,
    /** For a joined span: its tokens as single words. */
    val parts: List<Span> = emptyList(),
) {
    val isWord get() = ignoredBecause == null

    fun status(policy: KnownPolicy, lists: KnownLists): Status {
        if (ignoredBecause != null) return Status.IGNORED
        if (lemma in lists.names || surface in lists.names) return Status.NAME
        if (lemma in lists.ignored || surface in lists.ignored) return Status.IGNORED
        val states = deck.map { it.state }
        val deckKnown = states.any {
            it == CardState.STUDIED ||
                (it == CardState.STUDIED_SUSPENDED && policy.studiedSuspendedKnown) ||
                (it == CardState.NEW_SUSPENDED && policy.newSuspendedKnown)
        }
        if (deckKnown) return Status.DECK
        if (lemma in lists.known || surface in lists.known) return Status.MARKED
        if (rank != null && policy.frequencyThreshold > 0 && rank <= policy.frequencyThreshold) return Status.FREQUENT
        if (deck.isNotEmpty()) return Status.DECK_NEW
        val wordParts = parts.filter { it.isWord }
        if (wordParts.isNotEmpty() && wordParts.all { it.status(policy, lists) in KNOWN }) return Status.PARTS_KNOWN
        return Status.UNKNOWN
    }
}

private val KNOWN = setOf(Status.DECK, Status.MARKED, Status.FREQUENT, Status.PARTS_KNOWN, Status.NAME)

class Analysis(val text: String, val spans: List<Span>) {
    fun words() = spans.filter { it.ignoredBecause == null }

    data class Stats(val counted: Int, val unknown: Int, val byStatus: Map<Status, Int>) {
        val unknownRate get() = if (counted == 0) 0.0 else unknown.toDouble() / counted
    }

    fun stats(policy: KnownPolicy, lists: KnownLists): Stats {
        val statuses = spans.map { it.status(policy, lists) }
        val byStatus = statuses.groupingBy { it }.eachCount()
        val counted = statuses.count { it != Status.IGNORED && it != Status.NAME }
        val unknown = statuses.count { it == Status.UNKNOWN || it == Status.DECK_NEW }
        return Stats(counted, unknown, byStatus)
    }
}

/**
 * Splits text into words and decides, for each, whether the reader knows it.
 * Kuromoji does the segmentation; JMdict joins tokens back into one word where it has
 * the compound or set phrase (一人暮らし, 食べ過ぎる, 腹が立つ), deck entries first, longest first.
 */
class Matcher(
    private val dict: Jmdict,
    private val index: DeckIndex,
    private val frequency: FrequencyList? = null,
) {

    fun analyze(text: String): Analysis = synchronized(this) {
        wordCache.clear()
        val toks = Tokenizer.tokenize(text)
        val spans = ArrayList<Span>()
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t.isSymbol) { i++; continue }
            val multi = longest(toks, i)
            if (multi != null) {
                spans += multi
                i += multi.tokens
                continue
            }
            spans += single(t)
            i++
        }
        Analysis(text, spans)
    }

    /**
     * The spec's skip list (particles, auxiliaries, interjections, numbers, names) always applies.
     * The rest (suffixes, function nouns, helper verbs, prefixes) is skipped only when the deck
     * doesn't have the word: 倍, 末 or 都度 are cards worth reviewing.
     */
    private fun alwaysIgnored(reason: String) = reason in setOf("particle", "auxiliary", "interjection", "filler", "other", "number", "name")

    private fun ignoreReason(t: Tok): String? = when {
        t.pos1 == "助詞" -> "particle"
        t.pos1 == "助動詞" -> "auxiliary"
        t.pos1 == "感動詞" -> "interjection"
        t.pos1 == "フィラー" -> "filler"
        t.pos1 == "接頭詞" -> "prefix"
        t.pos1 == "その他" -> "other"
        t.pos1 == "名詞" && t.pos2 == "数" -> "number"
        t.pos1 == "名詞" && t.pos2 == "固有名詞" -> "name"
        t.pos1 == "名詞" && (t.pos2 == "接尾" || t.pos2 == "非自立" || t.pos2 == "特殊") -> "function noun"
        (t.pos1 == "動詞" || t.pos1 == "形容詞") && (t.pos2 == "非自立" || t.pos2 == "接尾") -> "auxiliary verb"
        else -> null
    }

    /** Tokens a joined word may start with: content words, numbers (一人), prefixes (お茶). */
    private fun canStart(t: Tok) = !t.isSymbol && t.pos1 != "助詞" && t.pos1 != "助動詞" &&
        !(t.pos1 == "名詞" && (t.pos2 == "接尾" || t.pos2 == "非自立")) &&
        // さ|せる, し|ます: forms of する start grammar, not words
        t.base != "する"

    private fun isNumberOrCounter(t: Tok) = t.pos1 == "名詞" && (t.pos2 == "数" || t.pos2 == "接尾")

    private fun longest(toks: List<Tok>, i: Int): Span? {
        if (!canStart(toks[i])) return null
        val max = minOf(MAX_JOIN, toks.size - i)
        for (n in max downTo 2) {
            val window = toks.subList(i, i + n)
            if (window.any { it.isSymbol }) continue
            // 三枚, 二十分: a number with its counter is still a number
            if (window.all(::isNumberOrCounter)) continue
            val surfaces = window.joinToString("") { it.surface }
            val withBase = window.dropLast(1).joinToString("") { it.surface } + window.last().base
            val candidates = listOf(surfaces, withBase).distinct()
            // deck spellings first: the deck's own set phrases win over anything else
            for (c in candidates) {
                if (index.hasText(c)) return joined(window, i, c, Normalize.entriesFor(dict, c, null))
            }
            // a JMdict join must end on a content word: 今日は|雨 is not こんにちは, 知ら|ない is 知る
            val last = window.last()
            if (last.pos1 == "助詞" || last.pos1 == "助動詞") continue
            // never let a JMdict compound swallow a deck word: 日本|企業 keeps 企業, すぐ|側 keeps 側
            if (window.any { t -> ignoreReason(t) == null && wordSpan(t).deck.isNotEmpty() }) continue
            for (c in candidates) {
                val entries = Normalize.entriesFor(dict, c, null)
                if (entries.isEmpty()) continue
                // join only into words JMdict rates as common, or ones in the deck: avoids
                // gluing ordinary grammar into obscure entries
                val inDeck = index.find(emptyList(), entries).isNotEmpty()
                if (inDeck || Normalize.bestRank(dict, entries, c) != null) return joined(window, i, c, entries)
            }
        }
        return null
    }

    private fun joined(window: List<Tok>, i: Int, lemma: String, entries: Set<Long>): Span {
        val start = window.first().start
        val end = window.last().end
        return Span(
            start, end, window.joinToString("") { it.surface }, lemma, entries,
            rank(entries, lemma),
            (index.find(listOf(lemma), entries) + affixFallback(lemma)).distinct(), null, window.size,
            parts = window.map(::single),
        )
    }

    private fun single(t: Tok): Span {
        val reason = ignoreReason(t)
        if (reason != null && alwaysIgnored(reason)) return ignored(t, reason)
        val span = wordSpan(t)
        if (reason != null && span.deck.isEmpty()) return ignored(t, reason)
        return span
    }

    private fun ignored(t: Tok, reason: String) = Span(t.start, t.end, t.surface, t.base, emptySet(), null, emptyList(), reason, 1)

    private val wordCache = HashMap<Tok, Span>()

    /** The token as a word, ignoring the skip rules. */
    private fun wordSpan(t: Tok): Span = wordCache.getOrPut(t) {
        var lemma = t.base
        var entries = Normalize.entriesFor(dict, lemma, readingIfPlain(t))
        if (entries.isEmpty() && t.surface != lemma) {
            val e = Normalize.entriesFor(dict, t.surface, null)
            if (e.isNotEmpty()) { lemma = t.surface; entries = e }
        }
        val texts = mutableListOf(lemma, t.surface)
        // IPADIC reads 属し as 属す; dictionaries and decks list 属する (and 要する, 愛する…)
        if (t.pos1 == "動詞" && t.base.length >= 2 && t.base.endsWith("す")) {
            val suru = t.base.dropLast(1) + "する"
            val e = Normalize.entriesFor(dict, suru, null)
            if (e.isNotEmpty() || index.hasText(suru)) {
                if (entries.isEmpty()) lemma = suru
                entries = entries + e
                texts += suru
            }
        }
        // both: 一気に is your V2K card 一気に and your Core 5k card 一気
        val deck = (index.find(texts.distinct(), entries) + affixFallback(lemma)).distinct()
        Span(t.start, t.end, t.surface, lemma, entries, rank(entries, lemma), deck, null, 1)
    }

    /**
     * IPADIC reads many adverbs as one word (完全に, 一気に, 依然として, 心から) and keeps honorific
     * お/ご attached (お祝い). Reading those means knowing the deck word inside, so they match it.
     */
    private fun affixFallback(lemma: String): List<DeckWord> {
        val stems = ArrayList<String>()
        for (suffix in listOf("として", "から", "に", "と", "で")) {
            if (lemma.length > suffix.length && lemma.endsWith(suffix)) stems += lemma.dropLast(suffix.length)
        }
        if (lemma.length > 1 && (lemma.startsWith("お") || lemma.startsWith("ご") || lemma.startsWith("御"))) stems += lemma.drop(1)
        for (stem in stems) {
            val found = index.find(listOf(stem), Normalize.entriesFor(dict, stem, null))
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** jpdb's fiction rank when the list has the word, else JMdict's coarser priority rank. */
    private fun rank(entries: Set<Long>, lemma: String): Int? =
        frequency?.rank(entries, lemma) ?: Normalize.bestRank(dict, entries, lemma)

    /** The tokenizer's reading narrows homographs only when it reads the dictionary form itself. */
    private fun readingIfPlain(t: Tok) = t.reading.takeIf { it.isNotEmpty() && t.surface == t.base && Kana.hasKanji(t.surface) }

    companion object {
        const val MAX_JOIN = 5
    }
}
