package com.cramtale.core

import com.cramtale.core.review.KnownNewCards
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnownNewCardsTest {
    @Test
    fun `known new cards are kept on disk and can be undone`() {
        val f = File(Files.createTempDirectory("known").toFile(), "known-new.json")
        val k = KnownNewCards(f)
        k.add(5, "書類")
        k.add(5, "書類")
        k.add(7, "許可")
        val again = KnownNewCards(f)
        assertEquals(listOf("書類", "許可"), again.all.map { it.word })
        assertTrue(5L in again)
        again.remove(5)
        assertFalse(5L in KnownNewCards(f))
    }
}
