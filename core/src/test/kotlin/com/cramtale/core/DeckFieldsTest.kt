package com.cramtale.core

import com.cramtale.core.match.CardState
import com.cramtale.core.match.DeckReader
import com.cramtale.core.match.RawCard
import com.cramtale.core.match.RawNote
import com.cramtale.core.match.WordExtract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Field shapes taken from the user's real notes. */
class DeckFieldsTest {
    @Test
    fun `core 5k lemma is used as is`() {
        assertEquals(listOf("学生"), WordExtract.variants("学生"))
    }

    @Test
    fun `several spellings on one line`() {
        assertEquals(listOf("良い", "いい"), WordExtract.variants("良い，いい"))
        assertEquals(listOf("くらい", "ぐらい"), WordExtract.variants("くらい,ぐらい"))
        assertEquals(listOf("応ずる", "応じる"), WordExtract.variants("応ずる、応じる"))
    }

    @Test
    fun `kihon back field - word on the first line, translation after`() {
        assertEquals("任す", WordExtract.word("任す<br><br>I was **entrusted** with a new job"))
        assertEquals("閉まる", WordExtract.word("閉まる<br><br>(Дверь) закрыта"))
    }

    @Test
    fun `gloss after a dash and non-Japanese lines are dropped`() {
        assertEquals(listOf("確かに"), WordExtract.variants("確かに – точно, уверенно"))
        assertEquals(emptyList(), WordExtract.variants("Director/president, I will help you"))
        assertEquals(emptyList(), WordExtract.variants("DVD"))
    }

    @Test
    fun `furigana, tildes, html and leading dashes are cleaned`() {
        assertEquals("一", WordExtract.word("一[いち]"))
        assertEquals("飛び込む", WordExtract.word(" 飛[と]び 込[こ]む"))
        assertEquals("ております", WordExtract.word("-ております"))
        assertEquals("さん", WordExtract.word("～さん"))
        assertEquals("食べる", WordExtract.word("<div><b>食べる</b></div>"))
    }

    @Test
    fun `v2k reading field`() {
        assertEquals("いち", WordExtract.reading("一[いち]"))
        assertNull(WordExtract.reading("それ"))
    }

    @Test
    fun `card state from recognition cards only`() {
        val note = RawNote(
            1, "日本語::Japanese V2K", "Japanese (Furigana1, Kanji2 V2K)",
            mapOf("Expression" to "それ", "Reading" to "それ"),
            listOf(RawCard(0, "Furigana1", 2, 2), RawCard(1, "Kanji2", 0, -1)),
        )
        val w = DeckReader.words(listOf(note)).single()
        assertEquals(CardState.STUDIED, w.state)
        assertEquals(2, w.cards.size)
        val gre = note.copy(noteType = "GRE vocabulary with Mnemonics")
        assertEquals(emptyList(), DeckReader.words(listOf(gre)))
    }

    @Test
    fun `suspended states`() {
        assertEquals(CardState.STUDIED_SUSPENDED, DeckReader.state(listOf(RawCard(0, "Recognition", 2, -1))))
        assertEquals(CardState.NEW_SUSPENDED, DeckReader.state(listOf(RawCard(0, "Recognition", 0, -1))))
        assertEquals(CardState.NEW, DeckReader.state(listOf(RawCard(0, "Recognition", 0, 0))))
    }
}
