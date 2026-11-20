package com.cramtale.core

import com.cramtale.core.llm.CostLog
import com.cramtale.core.llm.FakeLlm
import com.cramtale.core.story.DuePool
import com.cramtale.core.story.Sources
import com.cramtale.core.story.StoryEngine
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Your own files build the story; a sequel keeps the world, people and memories of the story before. */
class SourcesSequelTest {
    private val setup = """{"title":"Frost","world":"A frozen pass.","player":"Vaal, a demon hiding as a woodcutter.","history":[],"player_public":"A woodcutter.",
        "characters":[{"name":"鷺沼","file":"## Goals\nKill a demon.\n## Knows\nThe ward is failing."},{"name":"千早","file":"## Goals\nFind Lukas.\n## Knows\nTraders."}],
        "extras":[{"name":"大槻","line":"Gate captain."}],"secrets":[{"text":"Vaal is a demon","knows":["黒江"]}],"clocks":[{"name":"The ward","size":6,"when_full":"it falls"}],
        "complications":["Snow closes the pass"],"ambience":[],"opening":{"place":"the gate","present":["鷺沼"],"situation":"Dusk."}}"""

    private fun fake() = FakeLlm(mapOf(
        "setup" to setup, "knows_check" to """{"knows":"The ward is failing."}""",
        "summary" to "Vaal sold wood; Saginuma watched.", "offscreen" to """{"ops":[]}""",
        "director" to """{"beats":[{"goal":"the market","place":"the square","characters":["千早"],"pressure":"snow"}],"opening":""}""",
    ))

    @Test
    fun `files are read for what they are and kept as reference`() {
        val dir = Files.createTempDirectory("src").toFile()
        Sources(dir).add("world.md", "# The pass\nA demon hunts in the frost.")
        Sources(dir).add("me.txt", "My character: Vaal, who can heat iron.")
        val f = fake()
        StoryEngine(dir, f, CostLog(null), "s").setup("")
        val sent = f.sent("setup").single().messages.joinToString { it.text }
        assertTrue("## Material from the player" in sent && "heat iron" in sent && "A sheet for the player's character" in sent)
        assertFalse(Sources.looksLikeText(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0, 1, 2)), "a PDF is not text")
        assertTrue(Sources.looksLikeText("鷺沼は見ている".toByteArray()))
    }

    @Test
    fun `a sequel keeps the world, the people and what happened`() {
        val old = Files.createTempDirectory("old").toFile()
        val e = StoryEngine(old, fake(), CostLog(null), "s")
        e.setup("")
        e.state.writeCharacter("鷺沼", e.state.character("鷺沼")!! + "\n## Remembers\nThe woodcutter never feels the cold.")
        File(old, "state/title.txt").writeText("Frost\n")
        File(old, "state/chapters").mkdirs(); File(old, "state/chapters/1.md").writeText("Chapter one: snow.")

        val dir = Files.createTempDirectory("next").toFile()
        val f = fake()
        val n = StoryEngine(dir, f, CostLog(null), "s")
        n.carryOver(old, "A month later, the ward cracks")
        assertTrue("never feels the cold" in n.state.character("鷺沼")!!, "memories come along")
        assertEquals(setOf("大槻"), n.state.extras().keys)
        assertTrue(n.state.secrets().any { "demon" in it.text })
        assertEquals("Frost II", File(dir, "state/title.txt").readText().trim())
        assertTrue("Chapter one: snow." in n.saga()!! && File(dir, "state/chapters/0.md").exists())
        assertTrue(n.state.scene().sessionDue)
        n.startSession(DuePool())
        assertTrue("A month later, the ward cracks" in f.sent("director").last().messages.joinToString { it.text }, "the player's wish reaches the plan")
        assertFalse(File(dir, "state/next.md").exists(), "used once")
    }
}
