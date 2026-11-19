package com.cramtale.core

import com.cramtale.core.llm.CostLog
import com.cramtale.core.llm.FakeLlm
import com.cramtale.core.review.MistakeLog
import com.cramtale.core.story.Correction
import com.cramtale.core.story.StoryEngine
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Corrections by level: what shows depends on the setting; what doesn't is kept and comes back on a repeat. */
class MistakeLevelsTest {
    private val dir = Files.createTempDirectory("levels").toFile()
    private val reply = """{"errors":[
        {"wrong":"寒いでした","right":"寒かったです","type":"conjugation","reason":"い-adjective past","key":"い-adjective past: 〜いでした → 〜かったです","level":"error"},
        {"wrong":"薬を食べる","right":"薬を飲む","type":"word choice","reason":"medicine is drunk","key":"薬を飲む, not 食べる","level":"unnatural"},
        {"wrong":"ありがとう","right":"どうも","type":"other","reason":"lighter thanks","key":"light thanks: どうも","level":"nitpick"}],"correct_keys":[]}"""
    private val msg = "昨日は寒いでした。薬を食べる。ありがとう"

    private fun check(level: Int, known: List<String> = emptyList()) =
        StoryEngine(dir, FakeLlm(mapOf("mistakes" to reply)), CostLog(null), "s", settings = StoryEngine.Settings(corrections = level))
            .checkMistakes(msg, known)

    @Test
    fun `the setting decides what shows`() {
        assertEquals(listOf(true, false, false), check(0).map { it.shown })
        assertEquals(listOf(true, true, false), check(1).map { it.shown })
        assertEquals(listOf(true, true, true), check(2).map { it.shown })
    }

    @Test
    fun `a kept-back correction shows when the same mistake comes again, and both count`() {
        val log = MistakeLog(File(dir, "mistakes.json"))
        val first = check(0)
        log.record(msg, first, emptyList())
        assertFalse(log.weakPoints().contains("薬を飲む, not 食べる"), "a hidden one isn't sent to the writer as a weak point")
        val again = check(0, known = log.all.map { it.key })
        val medicine = again.single { it.key == "薬を飲む, not 食べる" }
        assertTrue(medicine.shown && medicine.repeat)
        val proposed = log.record(msg, again, emptyList())
        assertTrue(proposed.any { it.key == "薬を飲む, not 食べる" }, "shown now with the hidden first time: two, so a card is proposed")
        // it survives a restart
        val reloaded = MistakeLog(File(dir, "mistakes.json"))
        assertEquals(2, reloaded.all.single { it.key == "薬を飲む, not 食べる" }.count)
        assertEquals(2, reloaded.all.single { it.key == "light thanks: どうも" }.count, "the nitpick came up twice too")
        assertEquals(Correction.LEVEL_ERROR, reloaded.all.single { it.key.startsWith("い-adjective") }.examples.first().second.level)
    }
}
