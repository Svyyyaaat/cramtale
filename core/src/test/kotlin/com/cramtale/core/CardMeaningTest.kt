package com.cramtale.core

import com.cramtale.core.match.DeckReader
import com.cramtale.core.match.RawCard
import com.cramtale.core.match.RawNote
import com.cramtale.core.match.WordExtract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardMeaningTest {
    @Test
    fun `card meanings come out as plain text`() {
        assertEquals("of; in; at; for; by", WordExtract.plain("of; in; at; for; by"))
        assertEquals("to entrust; I was entrusted", WordExtract.plain("to entrust<br><b>I</b> was entrusted&nbsp;[sound:x.mp3]"))
    }

    @Test
    fun `deck words carry their card's meaning`() {
        val core = RawNote(1, "Core", "Japanese Core 5k", mapOf("Lemma" to "の", "English Gloss" to "of; in"), listOf(RawCard(0, "Recognition", 2, 2)))
        val furi = RawNote(2, "V2K", "Japanese (Furigana1, Kanji2 V2K)", mapOf("Expression" to "それ", "Reading" to "それ", "Meaning" to "that, that one"),
            listOf(RawCard(0, "Furigana1", 2, 2)))
        val kihon = RawNote(3, "基本", "基本", mapOf("表" to "x", "裏" to "任す<br><br>entrusted"), listOf(RawCard(0, "カード:1", 2, 2)))
        val w = DeckReader.words(listOf(core, furi, kihon)).associateBy { it.noteId }
        assertEquals("of; in", w[1]!!.meaning)
        assertEquals("that, that one", w[2]!!.meaning)
        assertNull(w[3]!!.meaning)
    }
}
