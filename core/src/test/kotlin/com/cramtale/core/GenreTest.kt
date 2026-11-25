package com.cramtale.core

import com.cramtale.core.llm.CostLog
import com.cramtale.core.llm.FakeLlm
import com.cramtale.core.story.Prompts
import com.cramtale.core.story.StoryEngine
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stories in any genre: the premise leads, "Surprise me" gets a spark, a world's own tables replace the generic ones. */
class GenreTest {
    private val setup = """{"title":"Egg","world":"A mountain temple.","player":"Rin, a novice cook.","history":[],"player_public":"A cook.",
        "characters":[{"name":"住職","file":"## Goals\nKeep the vow.\n## Knows\nRin cooks."},{"name":"ハル","file":"## Goals\nSell the egg.\n## Knows\nThe kitchen."}],
        "extras":[],"secrets":[],"clocks":[],"complications":["The egg grows warmer", "A pilgrim asks to see the kitchen"],
        "ambience":["[kitchen; sticky 2] The stove ticks as it cools."],"opening":{"place":"the kitchen","present":["住職"],"situation":"Dawn."}}"""

    private fun engine(dir: File, fake: FakeLlm) = StoryEngine(dir, fake, CostLog(null), "s")

    @Test
    fun `the premise comes first, and an empty one gets a spark`() {
        val dir = Files.createTempDirectory("genre").toFile()
        val fake = FakeLlm(mapOf("setup" to setup, "knows_check" to """{"knows":"Rin cooks."}"""))
        engine(dir, fake).setup("A dragon egg hatches in a temple kitchen")
        val sent = fake.sent("setup").single().messages.joinToString { it.text }
        assertTrue(sent.indexOf("What the player asked for") in 0 until sent.indexOf("taste in stories"), "the premise leads")
        assertTrue("bookkeeping by default" in sent, "the genre-neutral wording is sent")

        val dir2 = Files.createTempDirectory("genre2").toFile()
        val fake2 = FakeLlm(mapOf("setup" to setup, "knows_check" to """{"knows":"Rin cooks."}"""))
        engine(dir2, fake2).setup("")
        val sent2 = fake2.sent("setup").single().messages.joinToString { it.text }
        assertTrue(StoryEngine.SPARKS.any { it in sent2 }, "a spark from the list")
    }

    @Test
    fun `a world's own complications and background replace the generic ones`() {
        val dir = Files.createTempDirectory("genre3").toFile()
        engine(dir, FakeLlm(mapOf("setup" to setup, "knows_check" to """{"knows":"Rin cooks."}"""))).setup("eggs")
        val c = File(dir, "config/complications.md").readText()
        assertTrue("The egg grows warmer" in c && c.startsWith("#"), c)
        assertFalse("Someone who shouldn't be here" in c, "the generic ones are gone")
        val a = File(dir, "config/ambience.md").readText()
        assertTrue("stove ticks" in a && "dog barks" !in a, a)
        // and the default taste no longer pulls toward money trouble
        assertFalse("debt" in Prompts(null).config("plot.md").orEmpty())
    }

    @Test
    fun `the name the player chose reaches setup exactly`() {
        val dir = Files.createTempDirectory("name").toFile()
        val fake = FakeLlm(mapOf("setup" to setup, "knows_check" to """{"knows":"Rin cooks."}"""))
        engine(dir, fake).setup("a temple kitchen", name = "セラフ")
        val sent = fake.sent("setup").single().messages.joinToString { it.text }
        assertTrue("## The player's character's name\nセラフ" in sent && "Use exactly this name" in sent)
    }
}
