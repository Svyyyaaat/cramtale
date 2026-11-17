package com.cramtale.core

import com.cramtale.core.match.DeckIndex
import com.cramtale.core.match.FrequencyList
import com.cramtale.core.match.VocabCheck
import com.cramtale.core.match.VocabCheck.BandResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VocabCheckTest {
    @Test
    fun `threshold is the top of the last band known at 90 percent or more`() {
        val r = listOf(
            BandResult(2000, 4000, 10, 10), BandResult(4000, 6000, 10, 9),
            BandResult(6000, 8000, 10, 9), BandResult(8000, 10_000, 10, 6), BandResult(10_000, 12_000, 10, 9),
        )
        assertEquals(8000, VocabCheck.suggestedThreshold(r))
        assertEquals(2000, VocabCheck.suggestedThreshold(listOf(BandResult(2000, 4000, 10, 5))))
    }

    @Test
    fun `asks words outside the deck, skipping particles and kana spellings of kanji words`() {
        val dict = TestDict.fixture
        val freq = FrequencyList.parse(
            """
            1602700	付属	2500
            1358280	食べる	3000
            1358280	たべる	3100
            1516930	方	3200
            """.trimIndent().byteInputStream(),
        )
        val deck = DeckIndex(listOf(TestDict.word("食べる")), dict)
        val items = VocabCheck.items(dict, freq, deck, Random(1))
        val spellings = items.map { it.spelling }
        assertTrue("付属" in spellings, spellings.toString())
        assertTrue("食べる" !in spellings && "たべる" !in spellings, "deck word asked: $spellings")
        assertTrue(items.all { it.band == 2000 })
    }
}
