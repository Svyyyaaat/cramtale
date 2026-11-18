package com.cramtale.core

import com.cramtale.core.review.MistakeLog
import com.cramtale.core.story.Correction
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MistakeDismissTest {
    @Test
    fun `not a mistake removes the example and withdraws the proposal`() {
        val f = File(Files.createTempDirectory("m").toFile(), "mistakes.json")
        val log = MistakeLog(f)
        val c1 = Correction("犯したい", "避けたい", "word choice", "x", "犯す↔避ける")
        val c2 = Correction("犯した", "避けた", "word choice", "x", "犯す↔避ける")
        log.record("A 犯したい", listOf(c1), emptyList())
        assertEquals(1, log.record("B 犯した", listOf(c2), emptyList()).size)
        log.dismiss("A 犯したい", c1)
        val k = MistakeLog(f).all.single()
        assertEquals(1, k.count)
        assertFalse(k.proposed)
        log.dismiss("B 犯した", c2)
        assertTrue(MistakeLog(f).all.isEmpty())
    }
}
