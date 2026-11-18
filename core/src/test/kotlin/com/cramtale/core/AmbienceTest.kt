package com.cramtale.core

import com.cramtale.core.llm.CostLog
import com.cramtale.core.llm.FakeLlm
import com.cramtale.core.story.Ambience
import com.cramtale.core.story.StoryEngine
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Background with timed effects (SillyTavern's sticky / cooldown / groups) and the end-of-prompt reminders. */
class AmbienceTest {
    private val table = """
        - [sticky 3; group weather] Rain on the roof.
        - [group weather] A gust of wind.
        - [cooldown 4] A dog barks.
        - [mill] The millstone grinds.
    """.trimIndent()

    @Test
    fun `options parse and rain keeps going while it lasts`() {
        val a = Ambience.parse(table, Random(1))
        val going = LinkedHashMap<String, Int>(); val resting = LinkedHashMap<String, Int>()
        going["Rain on the roof."] = 3
        val t1 = a.draw("road", 1, 3, going, resting)
        assertTrue("(still) Rain on the roof." in t1, t1.toString())
        assertFalse("A gust of wind." in t1, "one weather at a time")
        assertFalse("The millstone grinds." in t1, "only at the mill")
        // turn 4: the rain is over and rests; the wind is now possible
        val t4 = a.draw("road", 4, 3, going, resting)
        assertFalse(t4.any { "Rain" in it }, t4.toString())
        assertTrue("Rain on the roof." in resting)
    }

    @Test
    fun `nothing comes straight back`() {
        val a = Ambience.parse(table, Random(7))
        val going = LinkedHashMap<String, Int>(); val resting = LinkedHashMap<String, Int>()
        val seen = (1..12).map { t -> a.draw("road", t, 1, going, resting).filter { !it.startsWith("(still)") } }
        val barks = seen.withIndex().filter { "A dog barks." in it.value }.map { it.index }
        barks.zipWithNext().forEach { (x, y) -> assertTrue(y - x > 4, "dog barked at turns $barks") }
    }

    @Test
    fun `rules broken lately are restated at the end of the packet`() {
        val dir = Files.createTempDirectory("slips").toFile()
        File(dir, "log").mkdirs()
        File(dir, "log/turns.jsonl").writeText(listOf(
            """{"turn":1,"checks":["player_voice: fixed by rewrite: player_voice: 「頷いた」","banned phrases: ok"]}""",
            """{"turn":2,"checks":["player_voice: 「はい」と言った","motive: fixed by rewrite: x","banned phrases: 静寂が"]}""",
            """{"turn":3,"checks":["all checks: ok"]}""",
        ).joinToString("\n") + "\n")
        val e = StoryEngine(dir, FakeLlm(emptyMap()), CostLog(File(dir, "c.jsonl")), "s")
        val slips = e.recentSlips()
        val lines = slips.lines().filter { it.startsWith("- ") }
        assertEquals(3, lines.size, slips)
        assertTrue(lines[0].contains("only what the player wrote"), "the most frequent first: $slips")
        assertTrue(slips.contains("stock phrases"))
        assertEquals("", StoryEngine(Files.createTempDirectory("clean").toFile(), FakeLlm(emptyMap()), CostLog(null), "s").recentSlips())
    }
}
