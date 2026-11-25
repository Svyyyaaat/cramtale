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
        File(old, "config").mkdirs()
        File(old, "config/plot.md").writeText("Cold, quiet dread.\n")
        File(old, "config/style_name.txt").writeText("Light novel\n")
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
        assertEquals("Cold, quiet dread.", File(dir, "config/plot.md").readText().trim(), "the sequel keeps the earlier story's style")
        assertEquals("Light novel", File(dir, "config/style_name.txt").readText().trim())
        n.startSession(DuePool())
        assertTrue("A month later, the ward cracks" in f.sent("director").last().messages.joinToString { it.text }, "the player's wish reaches the plan")
        assertFalse(File(dir, "state/next.md").exists(), "used once")
    }

    @Test
    fun `ideas are Japanese at the learner's level, with review words and a gloss`() {
        val dir = Files.createTempDirectory("ideas").toFile()
        val f = FakeLlm(mapOf("setup" to setup, "knows_check" to """{"knows":"x"}""",
            "director" to """{"beats":[{"goal":"g","place":"the gate","characters":["鷺沼"],"pressure":"p"}],"opening":""}""",
            "adjudicator" to """{"roll":false,"action":"","difficulty":0,"stakes":"","observable":"x"}""",
            "mistakes" to """{"errors":[],"correct_keys":[]}""", "intent" to """{"notices":"x","wants":"y","does":"z"}""",
            "judge" to """{"violations":[]}""", "leak" to """{"guesses":[]}""", "scribe" to """{"ops":[]}""",
            "writer" to """{"passage":"鷺沼は薪を見た。","used":[],"grammar":[]}""",
            "ideas" to """{"ideas":[{"ja":"「薪はいかがですか」","en":"Offer her firewood"},{"ja":"黙って帰る","en":"Leave quietly"},{"ja":"","en":"empty"}]}"""))
        val e = StoryEngine(dir, f, CostLog(null), "s")
        e.setup("")
        e.startSession(DuePool())
        assertTrue(e.ideas().isEmpty(), "no passage waiting: nothing to suggest for")
        e.playerTurn("「こんばんは」と言う", DuePool(words = listOf(com.cramtale.core.story.DueWord("薪", 1, false))))
        val ideas = e.ideas()
        assertEquals(listOf("「薪はいかがですか」", "黙って帰る"), ideas.map { it.ja })
        assertEquals("Offer her firewood", ideas.first().en)
        val sent = f.sent("ideas").single()
        val text = sent.messages.joinToString { it.text }
        assertTrue("薪" in text && "「こんばんは」と言う" in text, "review words and how the player writes")
        assertEquals(com.cramtale.core.llm.Models.CHECKS, sent.model, "the cheap model")
    }

    @Test
    fun `a roll is kept with its turn, with its dice, outcome and what was tried`() {
        val dir = Files.createTempDirectory("roll").toFile()
        val f = FakeLlm(mapOf("setup" to setup, "knows_check" to """{"knows":"x"}""",
            "director" to """{"beats":[{"goal":"g","place":"the gate","characters":["鷺沼"],"pressure":"p"}],"opening":""}""",
            "adjudicator" to """{"roll":true,"action":"slip past the gate guard","difficulty":1,"stakes":"caught","observable":"a woodcutter edges along the wall"}""",
            "mistakes" to """{"errors":[],"correct_keys":[]}""", "intent" to """{"notices":"x","wants":"y","does":"z"}""",
            "judge" to """{"violations":[]}""", "leak" to """{"guesses":[]}""", "scribe" to """{"ops":[]}""",
            "writer" to """{"passage":"大槻がこちらを見た。","used":[],"grammar":[]}"""))
        val e = StoryEngine(dir, f, CostLog(null), "s", dice = com.cramtale.core.story.Dice(kotlin.random.Random(3)))
        e.setup("")
        e.startSession(DuePool())
        val t = e.playerTurn("壁ぞいに歩く", DuePool())
        assertEquals("slip past the gate guard", t.rollAction)
        // a restart keeps it (the waiting turn is on disk)
        assertEquals("slip past the gate guard", StoryEngine(dir, f, CostLog(null), "s").pending!!.rollAction)
        e.commit()
        val o = File(dir, "log/turns.jsonl").tailJson(1).single()
        assertEquals(t.roll!!.dice, (0 until o.getJSONArray("dice").length()).map { o.getJSONArray("dice").getInt(it) })
        assertEquals(t.roll!!.outcome.name, o.getString("outcome"))
        assertEquals("slip past the gate guard", o.getString("rollAction"))
    }

    @Test
    fun `what the character doesn't know about themself stays off the player's sheet`() {
        val dir = Files.createTempDirectory("hidden").toFile()
        val withHidden = setup.replace("\"player\":\"Vaal, a demon hiding as a woodcutter.\"",
            "\"player\":\"Vaal, a demon hiding as a woodcutter.\",\"player_hidden\":\"Each use of his power wakes the ward's old guardian.\"")
        val f = FakeLlm(mapOf("setup" to withHidden, "knows_check" to """{"knows":"x"}""",
            "director" to """{"beats":[{"goal":"g","place":"the gate","characters":["鷺沼"],"pressure":"p"}],"opening":""}""",
            "adjudicator" to """{"roll":false,"action":"","difficulty":0,"stakes":"","observable":"x"}""",
            "mistakes" to """{"errors":[],"correct_keys":[]}""", "intent" to """{"notices":"x","wants":"y","does":"z"}""",
            "judge" to """{"violations":[]}""", "leak" to """{"guesses":[]}""", "scribe" to """{"ops":[]}""",
            "writer" to """{"passage":"鷺沼は薪を見た。","used":[],"grammar":[]}""",
            "ideas" to """{"ideas":[{"ja":"「はい」","en":"Yes"}]}"""))
        val e = StoryEngine(dir, f, CostLog(null), "s")
        e.setup("")
        assertFalse("guardian" in File(dir, "state/player.md").readText(), "the sheet the player reads")
        e.startSession(DuePool())
        e.playerTurn("「こんにちは」", DuePool())
        assertTrue("guardian" in f.sent("writer").last().system.joinToString { it.text }, "the game master knows")
        e.ideas()
        assertFalse("guardian" in f.sent("ideas").single().messages.joinToString { it.text }, "suggestions don't")
        assertFalse("guardian" in f.sent("mistakes").single().messages.joinToString { it.text })
    }

    @Test
    fun `an older sheet with a hidden truth in it is split once`() {
        val dir = Files.createTempDirectory("split").toFile()
        File(dir, "state").mkdirs()
        // wording no pattern would catch: every older sheet is asked once
        File(dir, "state/player.md").writeText("Svyat is a student who can rewind objects. He has no idea each use erases a memory.\n\n## Established in the story\n- He sells bread\n")
        val f = FakeLlm(mapOf("player_split" to """{"known":"Svyat is a student who can rewind objects.","hidden":"Each use erases a memory."}"""))
        val e = StoryEngine(dir, f, CostLog(null), "s")
        e.splitPlayerSheet()
        val sheet = File(dir, "state/player.md").readText()
        assertFalse("erases" in sheet, sheet)
        assertTrue("## Established in the story\n- He sells bread" in sheet, "the rest of the sheet stays")
        assertEquals("Each use erases a memory.", File(dir, "state/player_hidden.md").readText().trim())
        e.splitPlayerSheet()
        assertEquals(1, f.sent("player_split").size, "once")
    }
}
