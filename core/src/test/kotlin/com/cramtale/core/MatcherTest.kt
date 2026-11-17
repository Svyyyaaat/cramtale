package com.cramtale.core

import com.cramtale.core.match.CardState
import com.cramtale.core.match.DeckIndex
import com.cramtale.core.match.KnownLists
import com.cramtale.core.match.KnownPolicy
import com.cramtale.core.match.Matcher
import com.cramtale.core.match.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Matcher against the JMdict fixture: segmentation, normalisation, spelling variants, ignore rules. */
class MatcherTest {
    private val dict = TestDict.fixture
    private val policy = KnownPolicy(frequencyThreshold = 0)
    private val none = KnownLists()

    private fun matcher(vararg words: String) = Matcher(dict, DeckIndex(words.map { TestDict.word(it) }, dict))

    /** lemma -> status for the words that count. */
    private fun words(m: Matcher, text: String, lists: KnownLists = none) =
        m.analyze(text).words().map { it.lemma to it.status(policy, lists) }

    @Test
    fun `conjugated verbs match their dictionary form`() {
        val m = matcher("食べる", "知る", "行う")
        assertEquals("食べる" to Status.DECK, words(m, "昨日、パンを食べた。").last())
        assertEquals(listOf("知る" to Status.DECK), words(m, "知らない。"))
        assertEquals(listOf("行う" to Status.DECK), words(m, "会議が行われる。").filter { it.first == "行う" })
    }

    @Test
    fun `compound verbs join into one word`() {
        val w = words(matcher("食べ過ぎる"), "ケーキを食べ過ぎた。")
        assertEquals("食べ過ぎる" to Status.DECK, w.single { it.first.startsWith("食べ") })
    }

    @Test
    fun `multi-word deck entries match conjugated, longest first`() {
        val m = matcher("腹が立つ", "腹")
        val w = words(m, "あいつの態度に腹が立った。")
        assertTrue("腹が立つ" to Status.DECK in w, w.toString())
        assertTrue(w.none { it.first == "腹" }, "the set phrase wins over its parts: $w")
    }

    @Test
    fun `set phrases from JMdict join even outside the deck`() {
        val spans = matcher().analyze("彼は一人暮らしだ。").words()
        assertTrue(spans.any { it.lemma == "一人暮らし" }, spans.toString())
    }

    @Test
    fun `spelling variants of the same entry match the deck word`() {
        assertEquals(Status.DECK, words(matcher("付属"), "附属の病院").first { it.first == "附属" }.second)
        assertEquals(Status.DECK, words(matcher("何故"), "なぜ来ない？").first { it.first == "なぜ" }.second)
        assertEquals(Status.DECK, words(matcher("かつ丼"), "カツ丼を頼んだ").first { it.first == "カツ丼" }.second)
    }

    @Test
    fun `kana spelling does not match every verb read the same way`() {
        // 掛ける is usually written in kana; 欠ける and 駆ける are not, so かける doesn't hit them
        assertEquals(Status.DECK, words(matcher("掛ける"), "電話をかける").first { it.first == "かける" }.second)
        assertEquals(Status.UNKNOWN, words(matcher("欠ける"), "電話をかける").first { it.first == "かける" }.second)
    }

    @Test
    fun `particles, auxiliaries, numbers, names and suffixes are ignored`() {
        val a = matcher().analyze("田中さんは三冊の本を読みました。")
        val counted = a.words().map { it.lemma }
        assertEquals(listOf("本", "読む"), counted)
        val reasons = a.spans.filter { it.ignoredBecause != null }.map { it.surface to it.ignoredBecause }
        assertTrue("田中" to "name" in reasons, reasons.toString())
        assertTrue("三" to "number" in reasons, reasons.toString())
        assertTrue("は" to "particle" in reasons, reasons.toString())
    }

    @Test
    fun `te-iru and te-shimau helpers are not words to count`() {
        val counted = matcher().analyze("見てしまった。勉強している。").words().map { it.lemma }
        assertEquals(listOf("見る", "勉強", "する"), counted)
    }

    @Test
    fun `not a greeting - 今日は followed by more is 今日`() {
        val counted = matcher("今日").analyze("今日は雨だ。").words().map { it.lemma }
        assertEquals(listOf("今日", "雨"), counted)
    }

    @Test
    fun `new deck cards count as unknown until studied`() {
        val dict = TestDict.fixture
        val m = Matcher(dict, DeckIndex(listOf(TestDict.word("猫", CardState.NEW)), dict))
        assertEquals(listOf("猫" to Status.DECK_NEW), words(m, "猫"))
    }

    @Test
    fun `suspended new cards follow the policy`() {
        val m = Matcher(dict, DeckIndex(listOf(TestDict.word("猫", CardState.NEW_SUSPENDED)), dict))
        val span = m.analyze("猫").words().single()
        assertEquals(Status.DECK, span.status(policy, none))
        assertEquals(Status.DECK_NEW, span.status(policy.copy(newSuspendedKnown = false), none))
    }

    @Test
    fun `marked known, names and ignored lists`() {
        val m = matcher()
        val lists = KnownLists(known = setOf("帳簿"), names = setOf("佐伯"), ignored = setOf("ぶらぶら"))
        val w = words(m, "佐伯は帳簿を見る", lists).toMap()
        assertEquals(Status.MARKED, w["帳簿"])
        assertEquals(Status.UNKNOWN, w["見る"])
    }

    @Test
    fun `frequency threshold makes common words known`() {
        val span = matcher().analyze("雨").words().single()
        assertEquals(Status.UNKNOWN, span.status(policy, none))
        assertEquals(Status.FREQUENT, span.status(KnownPolicy(frequencyThreshold = 30_000), none))
    }

    @Test
    fun `homograph narrowed by the deck reading`() {
        // 方 has separate entries for かた and ほう; a reading picks one
        val dict = TestDict.fixture
        val hou = TestDict.word("方", reading = "ほう")
        val index = DeckIndex(listOf(hou), dict)
        val entriesHou = index.find(emptyList(), dict.lookup("方").map { it.entry })
        assertEquals(listOf(hou), entriesHou)
        val kataEntries = dict.lookup("方").map { it.entry }.filter { e -> "かた" in dict.forms(e).kana }
        assertTrue(index.find(emptyList(), kataEntries).isEmpty())
    }

    @Test
    fun `stats count only words`() {
        val a = matcher("食べる").analyze("猫が魚を食べた。")
        val s = a.stats(policy, none)
        assertEquals(3, s.counted)
        assertEquals(2, s.unknown)
    }
}
