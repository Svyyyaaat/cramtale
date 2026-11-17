package com.cramtale.core

import com.cramtale.core.match.DeckIndex
import com.cramtale.core.match.FrequencyList
import com.cramtale.core.match.KnownLists
import com.cramtale.core.match.KnownPolicy
import com.cramtale.core.match.Matcher
import com.cramtale.core.match.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrequencyListTest {
    // rows in the shape tools/jpdb_ranks.py writes (ids are JMdict entries in the fixture)
    private val sample = """
        # comment
        1358280	食べる	300	Anime=300,Books=400
        1358280	喰べる	45000	Books=45000
        1602700	付属	9000	Books=9000
    """.trimIndent()

    private val list = FrequencyList.parse(sample.byteInputStream())

    @Test
    fun `spelling rank first, else the entry's best`() {
        assertEquals(300, list.rank(listOf(1358280L), "食べる"))
        assertEquals(45000, list.rank(listOf(1358280L), "喰べる"))
        assertEquals(9000, list.rank(listOf(1602700L), "附属"))
        assertNull(list.rank(listOf(1L), "x"))
    }

    @Test
    fun `matcher uses it ahead of JMdict priority`() {
        val dict = TestDict.fixture
        val m = Matcher(dict, DeckIndex(emptyList(), dict), list)
        val span = m.analyze("食べた").words().single()
        assertEquals(300, span.rank)
        assertEquals(Status.FREQUENT, span.status(KnownPolicy(frequencyThreshold = 1000), KnownLists()))
    }
}
