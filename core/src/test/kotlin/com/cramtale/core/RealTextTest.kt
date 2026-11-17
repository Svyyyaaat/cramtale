package com.cramtale.core

import com.cramtale.core.match.Calibration
import com.cramtale.core.match.CardState
import com.cramtale.core.match.DeckExport
import com.cramtale.core.match.DeckIndex
import com.cramtale.core.match.DeckReader
import com.cramtale.core.match.KnownLists
import com.cramtale.core.match.KnownPolicy
import com.cramtale.core.match.Matcher
import com.cramtale.core.match.Status
import com.cramtale.core.text.Kana
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real Japanese text (three Aozora Bunko stories, public domain, plus three modern passages
 * in the register the story writer will use). Structural checks always run against the
 * fixture dictionary; the full-dictionary and real-deck checks run where local/ has the data.
 */
class RealTextTest {
    private val texts = listOf(
        "aozora_kumono_ito.txt", "aozora_hashire_merosu.txt", "aozora_chumonno_oi_ryoriten.txt",
        "modern_debt.txt", "modern_cafe.txt", "modern_fire.txt",
    )

    @Test
    fun `spans are ordered, inside the text, and cover every Japanese character outside symbols`() {
        val dict = TestDict.full ?: TestDict.fixture
        val m = Matcher(dict, DeckIndex(emptyList(), dict))
        for (name in texts) {
            val text = TestDict.text(name)
            val spans = m.analyze(text).spans
            var last = 0
            for (s in spans) {
                assertTrue(s.start >= last && s.end > s.start && s.end <= text.length, "$name: bad span $s")
                assertEquals(text.substring(s.start, s.end), s.surface, name)
                last = s.end
            }
            val covered = BooleanArray(text.length)
            spans.forEach { s -> for (i in s.start until s.end) covered[i] = true }
            val missed = text.indices.filter { !covered[it] && text[it] != '々' && (Kana.isKanji(text[it]) || text[it] in 'ぁ'..'ゖ') }
            assertTrue(missed.isEmpty(), "$name: uncovered ${missed.take(10).map { text[it] }}")
        }
    }

    @Test
    fun `full dictionary - lemmas of the modern passages`() {
        val dict = TestDict.full
        assumeTrue(dict != null, "local/jmdict.sqlite not built")
        val m = Matcher(dict!!, DeckIndex(emptyList(), dict))
        val lemmas = m.analyze(TestDict.text("modern_debt.txt")).words().map { it.lemma }.toSet()
        for (w in listOf("帳簿", "上げる", "売り上げ", "輪ゴム", "足りる", "返す", "銀行", "家賃", "荷物", "下ろす", "叩く", "倉庫", "鍵", "預ける")) {
            assertTrue(w in lemmas, "missing $w in $lemmas")
        }
        val fire = m.analyze(TestDict.text("modern_fire.txt")).words().map { it.lemma }.toSet()
        for (w in listOf("倉庫", "夜明け", "消防車", "崩れ落ちる", "焼け跡", "書き込む", "立ち上る", "借金", "返済", "期限")) {
            assertTrue(w in fire, "missing $w in $fire")
        }
    }

    @Test
    fun `real deck - report unknown rates and flagged words`() {
        val dict = TestDict.full
        val deckFile = TestDict.dataDir?.let { File(it, "deck.jsonl") }
        assumeTrue(dict != null && deckFile?.exists() == true, "local/jmdict.sqlite or local/deck.jsonl missing")
        val words = DeckReader.words(deckFile!!.useLines { DeckExport.parse(it) })
        val m = Matcher(dict!!, DeckIndex(words, dict), com.cramtale.core.match.FrequencyList.bundled())
        val lists = KnownLists(names = setOf("佐伯", "北村", "メロス", "セリヌンティウス", "ディオニス", "カンダタ"))
        val policy = KnownPolicy(frequencyThreshold = 6000)
        println("== Unknown rate with the real deck (studied + leeches known, threshold columns) ==")
        println("text".padEnd(36) + Calibration.THRESHOLDS.joinToString("") { "%7d".format(it) })
        for (name in texts) {
            val a = m.analyze(TestDict.text(name))
            val rates = Calibration.ratesByThreshold(a, policy, lists)
            println(name.padEnd(36) + rates.joinToString("") { "%6.1f%%".format(it.second.unknownRate * 100) })
        }
        for (name in listOf("modern_debt.txt", "modern_cafe.txt", "modern_fire.txt")) {
            val a = m.analyze(TestDict.text(name))
            val flagged = Calibration.flagged(a, policy, lists, dict)
            println("-- $name flagged at 6000: " + flagged.joinToString("  ") { "${it.lemma}(${it.rank ?: "-"}${if (it.deck.isNotEmpty()) ",deck-new" else ""})" })
        }
        // sanity: words the user has studied are recognised as deck words
        val debt = m.analyze(TestDict.text("modern_debt.txt"))
        val deckHits = debt.words().filter { it.status(policy, lists) == Status.DECK }.map { it.lemma }
        assertTrue(deckHits.size > 10, "few deck matches: $deckHits")
        assertTrue(words.count { it.state == CardState.STUDIED } > 3000)
    }
}
