package com.cramtale.core.match

import com.cramtale.core.dict.Jmdict
import com.cramtale.core.text.Kana
import kotlin.random.Random

/**
 * A two-minute vocabulary check that sets the frequency cut-off. It shows words that are NOT in
 * the user's decks, sampled from frequency bands, and asks known / not known. The cut-off becomes
 * the top of the last band (counting up) where the known share is still at least [TARGET].
 */
object VocabCheck {
    val BANDS = listOf(2000, 4000, 6000, 8000, 10_000, 12_000, 15_000, 20_000, 30_000)
    const val PER_BAND = 10
    const val TARGET = 0.9

    /** [reading]: kana for a spelling with kanji (shown as furigana once answered), "" otherwise. */
    data class Item(val entry: Long, val spelling: String, val rank: Int, val band: Int, val gloss: String, val reading: String = "")

    data class BandResult(val from: Int, val to: Int, val asked: Int, val known: Int) {
        val share get() = if (asked == 0) 0.0 else known.toDouble() / asked
    }

    /** Parts of speech that aren't vocabulary to test (particles, auxiliaries, affixes, counters…). */
    private val SKIP_POS = setOf("prt", "aux", "aux-v", "aux-adj", "cop", "suf", "pref", "ctr", "int", "conj", "pn", "exp")

    /** Words to ask, band by band, in random order within each band. */
    fun items(dict: Jmdict, freq: FrequencyList, deck: DeckIndex, random: Random = Random.Default): List<Item> =
        BANDS.zipWithNext().flatMap { (from, to) ->
            freq.inRange(from, to).shuffled(random).asSequence()
                // whole words only: no single characters (能, 住), no digits (１ミリ)
                .filter { Kana.hasJapanese(it.spelling) && it.spelling.length > 1 && it.spelling.none { c -> c.isDigit() } }
                .filter { deck.find(listOf(it.spelling), listOf(it.entry)).isEmpty() }
                .mapNotNull { r ->
                    val senses = dict.senses(r.entry)
                    if (senses.isEmpty() || senses.first().pos.any { it in SKIP_POS }) return@mapNotNull null
                    // a kana spelling of a word normally written in kanji is a reading test, not a word test
                    val forms = dict.forms(r.entry)
                    if (Kana.isKana(r.spelling) && forms.kanji.isNotEmpty() && senses.none { "uk" in it.misc }) return@mapNotNull null
                    Item(r.entry, r.spelling, r.rank, from, senses.first().gloss,
                        if (Kana.hasKanji(r.spelling)) forms.kana.firstOrNull().orEmpty() else "")
                }
                .distinctBy { it.entry }
                .take(PER_BAND)
                .toList()
        }

    fun results(answers: Map<Item, Boolean>): List<BandResult> =
        BANDS.zipWithNext().map { (from, to) ->
            val inBand = answers.filterKeys { it.band == from }
            BandResult(from, to, inBand.size, inBand.count { it.value })
        }

    /** Cut-off: the top of the last band, counting up, whose known share is at least [TARGET]. */
    fun suggestedThreshold(results: List<BandResult>): Int {
        var threshold = BANDS.first()
        for (r in results) {
            if (r.asked == 0 || r.share < TARGET) break
            threshold = r.to
        }
        return threshold
    }
}
