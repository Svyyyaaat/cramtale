package com.cramtale.core

import com.cramtale.core.llm.CostLog
import com.cramtale.core.llm.FakeLlm
import com.cramtale.core.story.DuePool
import com.cramtale.core.story.StateOp
import com.cramtale.core.story.StoryEngine
import com.cramtale.core.story.StoryState
import com.cramtale.core.story.StoryState.Companion.PLAYER
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The record is the truth; memories are computed from it; perception is checked in code. */
class EventLogTest {
    private val dir = Files.createTempDirectory("events").toFile()
    private val state = StoryState(dir)

    @Test
    fun `a character remembers only what they perceived`() {
        state.addEvent(-1, "before the story", "Lenz searched the forest for three weeks", setOf(PLAYER, "Konrad"))
        state.addEvent(-1, "before the story", "Mia paid Lenz's wages", setOf("Mia", PLAYER))
        state.addEvent(2, "mill", "Konrad wrote a line in his notebook", setOf("Konrad"))
        assertEquals(listOf("Lenz searched the forest for three weeks", "Konrad wrote a line in his notebook"), state.memoryOf("Konrad").map { it.text })
        assertEquals(listOf("Mia paid Lenz's wages"), state.memoryOf("Mia").map { it.text })
        assertEquals(2, state.memoryOf(PLAYER).size)
    }

    @Test
    fun `older memories come back when they bear on the scene`() {
        state.addEvent(-1, "before the story", "Konrad lost a bet to the miller", setOf("Konrad"))
        repeat(20) { state.addEvent(it, "road", "walked the road again ($it)", setOf("Konrad")) }
        val plain = state.memoryOf("Konrad", recent = 5)
        assertTrue(plain.any { it.text.startsWith("Konrad lost a bet") }, "backstory always stays")
        state.addEvent(30, "mill", "met the miller's son", setOf("Konrad"))
        repeat(10) { state.addEvent(40 + it, "road", "more road ($it)", setOf("Konrad")) }
        val focused = state.memoryOf("Konrad", focus = listOf("miller"), recent = 5)
        assertTrue(focused.any { it.text == "met the miller's son" }, focused.toString())
    }

    @Test
    fun `the recorder can't make someone a witness who wasn't there`() {
        state.save(state.scene().also { it.place = "mill"; it.present = mutableListOf("Mia") })
        val ops = state.apply(StateOp.parse("""{"ops":[
            {"op":"event","a":"Lenz cried and hid it","b":"","list":["Mia","Konrad","PLAYER"],"n":0},
            {"op":"event","a":"something only Konrad saw","b":"","list":["Konrad"],"n":0}]}"""))
        assertTrue(ops[0].contains("not there: Konrad"), ops.toString())
        assertTrue(ops[1].startsWith("refused"), ops.toString())
        assertEquals(setOf("Mia", PLAYER), state.events().single().witnesses)
        // between sessions anyone can have been there
        state.apply(StateOp.parse("""{"ops":[{"op":"event","a":"Konrad met the bailiff","b":"","list":["Konrad"],"n":0}]}"""), offscreen = true)
        assertTrue(state.memoryOf("Konrad").any { it.text == "Konrad met the bailiff" })
    }

    @Test
    fun `setup seeds the record and each person decides from their own memory`() {
        val fake = FakeLlm(mapOf(
            "setup" to """{"world":"村。","player":"Lenz, a returned woodcutter.","player_public":"A tired young man.",
                "history":[{"text":"Lenz searched the forest for three weeks","witnesses":["PLAYER","Konrad"]},
                           {"text":"Mia hid her debts from everyone","witnesses":["Mia"]}],
                "characters":[{"name":"Konrad","file":"## Goals\nFind the truth."},{"name":"Mia","file":"## Goals\nKeep the mill."}],
                "secrets":[],"clocks":[],"complications":[],"ambience":[],
                "opening":{"place":"mill","present":["Konrad","Mia"],"situation":"Konrad asks questions."}}""",
            "director" to """{"beats":[{"goal":"questions","place":"mill","characters":["Konrad"],"pressure":"time"}],"opening":""}""",
            "intent" to """{"notices":"x","wants":"y","does":"z"}""",
            "mistakes" to """{"errors":[],"correct_keys":[]}""", "adjudicator" to """{"roll":false,"action":"","difficulty":0,"stakes":"","observable":"Lenz says he was in the forest for three weeks."}""",
            "judge" to """{"violations":[]}""", "leak" to """{"guesses":[]}""", "scribe" to """{"ops":[]}""",
            "writer" to """{"passage":"Konradは黙った。","used":[],"grammar":[]}""",
        ))
        val e = StoryEngine(dir, fake, CostLog(File(dir, "c.jsonl")), "s", unknownWords = { 10 to emptyList() })
        e.setup()
        assertEquals(2, e.state.events().size)
        e.startSession(DuePool())
        e.playerTurn(null, DuePool())
        val asked = fake.sent("intent").associate { r -> r.messages.joinToString { it.text }.let { t -> (if ("Who you are\nKonrad" in t) "Konrad" else "Mia") to t } }
        assertTrue("three weeks" in asked["Konrad"]!!)
        assertFalse("three weeks" in asked["Mia"]!!, "Mia never perceived it")
        assertFalse("debts" in asked["Konrad"]!!, "Konrad never perceived it")
        assertTrue("debts" in asked["Mia"]!!)
        e.commit()
        e.playerTurn("「三週間、森にいた」と言う", DuePool())
        val second = fake.sent("intent").takeLast(2).map { r -> r.messages.joinToString { it.text } }
        assertTrue(second.all { "Konradは黙った。" in it }, "they remember the earlier passage word for word")
        assertTrue(second.none { "三週間、森にいた」と言う" in it }, "never the raw message")
        assertTrue(fake.sent("intent").all { it.model == com.cramtale.core.llm.Models.CHARACTERS })
        val packet = fake.sent("writer").last().messages.last().text
        assertTrue("three weeks" in packet && "perceived by" in packet, "the writer gets the record")
    }

    @Test
    fun `a stance replaces the last one instead of piling up`() {
        state.writeCharacter("ブルーノ", """
            ## Goals
            Get the guards paid double.
            ## Attitude
            Guarded and blunt.
            - (turn 4) Openly suspects Kai over the goat bones.
            - (turn 6) Now suspects the charm reacts to Kai.
            ## Learned
            - (turn 5) The guest has a charm.
        """.trimIndent())
        state.save(state.scene().also { it.turn = 9; it.present = mutableListOf("ブルーノ") })
        state.apply(StateOp.parse("""{"ops":[{"op":"character","a":"ブルーノ","b":"Let the matter drop for tonight; still wary of Kai.","list":[],"n":0}]}"""))
        val f = state.character("ブルーノ")!!
        assertFalse("Openly suspects" in f || "Now suspects" in f, f)
        assertTrue("(turn 9) Let the matter drop" in f, f)
        assertTrue("- (turn 5) The guest has a charm." in f, "what they learned stays")
        state.apply(StateOp.parse("""{"ops":[{"op":"character","a":"ブルーノ","b":"Owes Kai a favour.","list":[],"n":0}]}"""))
        val g = state.character("ブルーノ")!!
        assertEquals(1, Regex("## Now").findAll(g).count(), g)
        assertFalse("Let the matter drop" in g)
        assertTrue("Guarded and blunt." in g)
    }
}
