package com.cramtale.core

import com.cramtale.core.match.CardState
import com.cramtale.core.match.DeckWord
import com.cramtale.core.match.RawCard
import com.cramtale.core.review.AnkiAnswers
import com.cramtale.core.review.AnkiSource
import com.cramtale.core.review.CardInfo
import com.cramtale.core.review.CardRef
import com.cramtale.core.review.DryRunReport
import com.cramtale.core.review.DueSnapshot
import com.cramtale.core.review.GradeLog
import com.cramtale.core.review.GradingEngine
import com.cramtale.core.review.Mode
import com.cramtale.core.review.PassageRead
import com.cramtale.core.review.PendingReads
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The grading rules of PLAN.md §4.3 against a fake AnkiDroid. */
class GradingTest {
    private val now = 1_800_000_000L

    private class FakeAnki(val cards: MutableMap<CardRef, CardInfo>, val queueOrder: List<CardRef>, val nowS: Long) : AnkiSource {
        val answered = ArrayList<Pair<CardRef, Int>>()
        override fun queue(deckId: Long, limit: Int) = queueOrder.take(limit)
        override fun card(ref: CardRef) = cards[ref]
        override fun nowSeconds() = nowS
    }

    private fun word(id: Long, w: String, vararg ords: Int) =
        DeckWord(id, "d", "Japanese Core 5k", w, null, CardState.STUDIED, ords.map { RawCard(it, "Recognition", 2, 2) })

    private val 鍵 = word(1, "鍵", 0)
    private val 倉庫 = word(2, "倉庫", 0)
    private val 帳簿 = word(3, "帳簿", 0)
    private val 銀行 = word(4, "銀行", 0)
    private val 一気 = word(5, "一気に", 0, 1) // V2K: two recognition cards

    private fun anki() = FakeAnki(
        mutableMapOf(
            CardRef(1, 0) to CardInfo(2, 2, 900, reps = 5, ivl = 30),       // review, due today
            CardRef(2, 0) to CardInfo(0, 0, 10, reps = 0),                  // new, offered by Anki
            CardRef(3, 0) to CardInfo(1, 1, now + 300, reps = 1),           // learning, step not over
            CardRef(4, 0) to CardInfo(2, 2, 950, reps = 7, ivl = 40),       // review, not due (not in queue)
            CardRef(5, 0) to CardInfo(2, 2, 900, reps = 3, ivl = 10),
            CardRef(5, 1) to CardInfo(2, 2, 900, reps = 3, ivl = 10),
        ),
        listOf(CardRef(1, 0), CardRef(2, 0), CardRef(3, 0), CardRef(5, 0), CardRef(5, 1)), now,
    )

    private val words = listOf(鍵, 倉庫, 帳簿, 銀行, 一気)

    @Test
    fun `snapshot - learning cards only once their step has really passed`() {
        val snap = DueSnapshot.take(anki(), listOf(1L), words)
        assertEquals(setOf(1L, 2L, 5L), snap.cards.map { it.ref.noteId }.toSet())
    }

    @Test
    fun `untapped due = Good, tapped due = Again, new first read = Again, one read answers both V2K cards`() {
        val a = anki()
        val snap = DueSnapshot.take(a, listOf(1L), words)
        val read = PassageRead("p1", notesInText = setOf(1, 2, 5), tappedNotes = setOf(1), tappedWords = listOf(鍵), readMs = 40_000)
        val grades = GradingEngine().decide(snap, read, a::card).associate { it.ref to it.ease }
        assertEquals(1, grades[CardRef(1, 0)])
        assertEquals(1, grades[CardRef(2, 0)])
        assertEquals(3, grades[CardRef(5, 0)])
        assertEquals(3, grades[CardRef(5, 1)])
        assertEquals(4, grades.size)
    }

    @Test
    fun `tapped but not due - Again right away, due but absent - nothing`() {
        val a = anki()
        val snap = DueSnapshot.take(a, listOf(1L), words)
        val read = PassageRead("p1", notesInText = setOf(4), tappedNotes = setOf(4), tappedWords = listOf(銀行), readMs = 10_000)
        val grades = GradingEngine().decide(snap, read, a::card)
        assertEquals(listOf(CardRef(4, 0) to 1), grades.map { it.ref to it.ease })
    }

    @Test
    fun `read-only mode logs and sends nothing, a card changed meanwhile is skipped`() {
        val a = anki()
        val snap = DueSnapshot.take(a, listOf(1L), words)
        val grades = GradingEngine().decide(snap, PassageRead("p1", setOf(1, 5), emptySet(), emptyList(), 30_000), a::card)
        a.cards[CardRef(5, 1)] = CardInfo(2, 2, 920, reps = 4, ivl = 20) // reviewed in AnkiDroid meanwhile
        val log = GradeLog(null)
        val out = GradingEngine().commit(grades, Mode.READ_ONLY, a, AnkiAnswers { r, e, _ -> a.answered += r to e; true }, log)
        assertTrue(a.answered.isEmpty())
        assertEquals(listOf("dry run", "dry run", "changed since the passage started"), out.map { it.note })
        assertEquals(3, log.entries.size)
    }

    @Test
    fun `grading mode sends real answers`() {
        val a = anki()
        val snap = DueSnapshot.take(a, listOf(1L), words)
        val grades = GradingEngine().decide(snap, PassageRead("p1", setOf(1), emptySet(), emptyList(), 30_000), a::card)
        GradingEngine().commit(grades, Mode.GRADING, a, AnkiAnswers { r, e, _ -> a.answered += r to e; true }, GradeLog(null))
        assertEquals(listOf(CardRef(1, 0) to 3), a.answered)
    }

    @Test
    fun `two-read option - young review card needs a second untapped read in another passage`() {
        val a = anki()
        val engine = GradingEngine(twoReads = true, pendingReads = PendingReads(null))
        val snap = DueSnapshot.take(a, listOf(1L), words)
        val first = engine.decide(snap, PassageRead("p1", setOf(5), emptySet(), emptyList(), 30_000), a::card)
        assertTrue(first.isEmpty(), "first read sends nothing: $first")
        val again = engine.decide(snap, PassageRead("p1", setOf(5), emptySet(), emptyList(), 30_000), a::card)
        assertTrue(again.isEmpty(), "same passage again doesn't count")
        val second = engine.decide(snap, PassageRead("p2", setOf(5), emptySet(), emptyList(), 30_000), a::card)
        assertEquals(setOf(3), second.map { it.ease }.toSet())
    }

    @Test
    fun `dry-run report infers what you pressed in AnkiDroid`() {
        val before = CardInfo(2, 2, 900, reps = 5)
        assertEquals(DryRunReport.Real.AGAIN, DryRunReport.infer(before, CardInfo(3, 1, now + 600, reps = 6)))
        assertEquals(DryRunReport.Real.PASSED, DryRunReport.infer(before, CardInfo(2, 2, 930, reps = 6)))
        assertEquals(DryRunReport.Real.NOT_YET, DryRunReport.infer(before, before))
    }
}
