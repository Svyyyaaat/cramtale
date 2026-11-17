package com.cramtale.core

import com.cramtale.core.match.CardState
import com.cramtale.core.match.DeckIndex
import com.cramtale.core.match.DeckWord
import com.cramtale.core.match.GrammarPatterns
import com.cramtale.core.match.Matcher
import com.cramtale.core.match.RawCard
import com.cramtale.core.review.CardInfo
import com.cramtale.core.review.CardRef
import com.cramtale.core.review.DueCard
import com.cramtale.core.review.DueSnapshot
import com.cramtale.core.review.MistakeLog
import com.cramtale.core.review.ReviewPlanner
import com.cramtale.core.story.Correction
import com.cramtale.core.story.Draft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewPlannerTest {
    private fun w(id: Long, word: String) = DeckWord(id, "d", "Japanese Core 5k", word, null, CardState.STUDIED, listOf(RawCard(0, "Recognition", 2, 2)))
    private fun due(word: DeckWord) = DueCard(CardRef(word.noteId, 0), word, CardInfo(2, 2, 900))

    private val 食べる = w(1, "食べる")
    private val 猫 = w(2, "猫")
    private val ことにする = w(3, "ことにする")

    @Test
    fun `pool splits words and grammar, skipped words come first`() {
        val planner = ReviewPlanner(GrammarPatterns.bundled(), null)
        val snap = DueSnapshot(listOf(due(食べる), due(猫), due(ことにする)), 0)
        val pool = planner.pool(snap, emptyList())
        assertEquals(listOf("食べる", "猫"), pool.words.map { it.word })
        assertEquals(listOf("ことにする"), pool.grammar.map { it.id })
        planner.recordSkips(pool, setOf(1L, 3L)) // 猫 was offered but not used
        assertEquals("猫", planner.pool(snap, emptyList()).words.first().word)
    }

    @Test
    fun `grammar counts only when declared by the writer and found by the matcher`() {
        val dict = TestDict.fixture
        val planner = ReviewPlanner(GrammarPatterns.bundled(), null)
        val m = Matcher(dict, DeckIndex(listOf(食べる, 猫, ことにする), dict))
        val text = "毎日パンを食べることにした。"
        val snap = DueSnapshot(listOf(due(食べる), due(ことにする)), 0)
        val pool = planner.pool(snap, emptyList())
        val declared = Draft(text, emptyList(), listOf("ことにする"), emptyList(), false)
        val silent = declared.copy(grammar = emptyList())
        assertEquals(setOf(1L, 3L), planner.notesInText(m.analyze(text), declared, pool))
        assertEquals(setOf(1L), planner.notesInText(m.analyze(text), silent, pool))
    }

    @Test
    fun `mistake keys - second occurrence proposes a card, three correct uses mark it fixed`() {
        val log = MistakeLog(null)
        val c = Correction("寒いでした", "寒かったです", "conjugation", "", "い-adjective past")
        assertTrue(log.record("昨日は寒いでした。", listOf(c), emptyList()).isEmpty())
        val proposed = log.record("高いでした。", listOf(c.copy(wrong = "高いでした")), emptyList())
        assertEquals(listOf("い-adjective past"), proposed.map { it.key })
        assertEquals(listOf("い-adjective past"), log.weakPoints())
        repeat(3) { log.record("楽しかったです。", emptyList(), listOf("い-adjective past")) }
        assertTrue(log.weakPoints().isEmpty())
    }
}
