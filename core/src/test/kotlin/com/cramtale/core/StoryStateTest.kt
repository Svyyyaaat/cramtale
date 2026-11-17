package com.cramtale.core

import com.cramtale.core.story.Complications
import com.cramtale.core.story.Dice
import com.cramtale.core.story.StateOp
import com.cramtale.core.story.StoryState
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoryStateTest {
    private fun state(): StoryState {
        val dir = Files.createTempDirectory("story").toFile()
        val s = StoryState(dir)
        s.writeCharacter("佐伯", "# 佐伯\n## Goals\nGet the warehouse key.\n")
        java.io.File(dir, "state").mkdirs()
        java.io.File(dir, "state/world.md").writeText("# World\n\n## Clocks\n- 地主が店を売る: 2/6 — when full: the shop is sold\n")
        java.io.File(dir, "state/secrets.md").writeText("- S1: 佐伯 owes the bank more than he says [knows: 佐伯]\n")
        s.save(s.scene().apply { turn = 5; place = "倉庫"; present = mutableListOf("佐伯") })
        return s
    }

    @Test
    fun `secrets are revealed only to someone in the scene`() {
        val s = state()
        val log = s.apply(listOf(StateOp.Reveal("S1", "北村"), StateOp.Reveal("S1", "佐伯")))
        assertTrue(log[0].startsWith("refused"), log.toString())
        assertEquals(setOf("佐伯"), s.secrets().single().knows)
        // between sessions, off-screen events can spread a secret anywhere
        s.apply(listOf(StateOp.Reveal("S1", "北村")), offscreen = true)
        assertEquals(setOf("佐伯", "北村"), s.secrets().single().knows)
    }

    @Test
    fun `clocks move one step per turn at most`() {
        val s = state()
        val log = s.apply(listOf(StateOp.AdvanceClock("地主が店を売る", 1), StateOp.AdvanceClock("地主が店を売る", 1)))
        assertTrue(log[1].startsWith("refused"), log.toString())
        assertEquals(3, s.clocks().single().filled)
    }

    @Test
    fun `consequences get ids and due turns, and close`() {
        val s = state()
        s.apply(listOf(StateOp.AddConsequence("佐伯 wants the 8万 by the weekend", 3)))
        val c = s.consequences().single()
        assertEquals("C1", c.id)
        assertEquals(8, c.dueTurn)
        s.apply(listOf(StateOp.ResolveConsequence("C1")))
        assertTrue(!s.consequences().single().open)
    }

    @Test
    fun `scribe JSON parses and unknown ops are skipped`() {
        val ops = StateOp.parse("""{"ops":[{"op":"chronicle","a":"鍵を渡した","b":"","list":["倉庫"],"n":0},{"op":"teleport","a":"","b":"","list":[],"n":0},{"op":"beat_done","a":"","b":"","list":[],"n":0}]}""")
        assertEquals(2, ops.size)
        val s = state()
        s.apply(ops)
        assertEquals("鍵を渡した", s.chronicle().single().text)
    }

    @Test
    fun `dice tiers`() {
        val d = Dice(Random(1))
        val rolls = List(500) { d.roll(0) }
        assertTrue(rolls.all { r -> r.outcome == when { r.total >= 10 -> Dice.Outcome.SUCCESS; r.total >= 7 -> Dice.Outcome.COST; else -> Dice.Outcome.FAILURE } })
        assertTrue(rolls.map { it.outcome }.toSet().size == 3)
    }

    @Test
    fun `complications prefer the place and don't repeat`() {
        val c = Complications.parse("- [倉庫] 荷崩れ\n- 雨が降り出す\n- [駅] 電車が止まる", Random(2))
        assertEquals("荷崩れ", c.draw("港の倉庫", emptyList()))
        assertEquals("雨が降り出す", c.draw("港の倉庫", listOf("荷崩れ")))
    }
}
