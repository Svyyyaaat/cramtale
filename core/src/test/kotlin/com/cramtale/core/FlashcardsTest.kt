package com.cramtale.core

import com.cramtale.core.match.CardState
import com.cramtale.core.match.DeckWord
import com.cramtale.core.match.RawCard
import com.cramtale.core.review.CardInfo
import com.cramtale.core.review.CardRef
import com.cramtale.core.review.DueCard
import com.cramtale.core.review.DueSnapshot
import com.cramtale.core.review.Flashcards
import com.cramtale.core.review.GradeLog
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FlashcardsTest {
    private fun card(id: Long, isNew: Boolean = false) = DueCard(CardRef(id, 0),
        DeckWord(id, "d", "Japanese Core 5k", "語$id", null, CardState.STUDIED, listOf(RawCard(0, "Recognition", 2, 2))),
        if (isNew) CardInfo(0, 0, 1) else CardInfo(2, 2, 10, 4, 12))

    @Test
    fun `answered cards leave the pool for the day, reviews are logged, new cards aren't graded`() {
        val dir = Files.createTempDirectory("fc").toFile()
        var day = 1_000L
        val fc = Flashcards(File(dir, "fc.json")) { day }
        val log = GradeLog(File(dir, "grades.jsonl"))
        val snap = DueSnapshot(listOf(card(1), card(2), card(3, isNew = true)), 0)
        assertEquals(listOf(1L, 3L), fc.pick(snap, inPassage = setOf(2L)).map { it.ref.noteId })
        fc.answer(card(1), good = true, log = log)
        fc.answer(card(3, isNew = true), good = false, log = log)
        assertEquals(listOf(2L), fc.pick(snap, emptySet()).map { it.ref.noteId })
        assertEquals(1, log.snapshot().size)
        assertEquals(3, log.snapshot().single().getInt("ease"))
        // a new Anki day: they're due again (in read-only mode Anki didn't change)
        day = System.currentTimeMillis() + 1
        assertEquals(3, Flashcards(File(dir, "fc.json")) { day }.pick(snap, emptySet()).size)
    }

    @Test
    fun `a flashcard answer covers its note for the day`() {
        val dir = Files.createTempDirectory("fc2").toFile()
        val fc = Flashcards(File(dir, "fc.json")) { 0L }
        fc.answer(card(7), good = false, log = null)
        assertEquals(true, fc.noteAnsweredToday(7))
        assertEquals(false, fc.noteAnsweredToday(70))
    }
}
