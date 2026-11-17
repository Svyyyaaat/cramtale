package com.cramtale.core

import com.cramtale.core.llm.CostLog
import com.cramtale.core.llm.FakeLlm
import com.cramtale.core.llm.Llm
import com.cramtale.core.llm.LlmException
import com.cramtale.core.llm.LlmRequest
import com.cramtale.core.llm.LlmResponse
import com.cramtale.core.story.Dice
import com.cramtale.core.story.DuePool
import com.cramtale.core.story.DueWord
import com.cramtale.core.story.StoryEngine
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Turns survive restarts and failed calls: nothing is rolled, moved or charged twice. */
class StoryResumeTest {
    private val fake = FakeLlm(mapOf(
        "setup" to """{"world":"港町。","player":"店主。","characters":[{"name":"佐伯","file":"## Goals\nKey."},{"name":"北村","file":"## Goals\nSell."}],"secrets":[],
            "clocks":[],"complications":["荷物が届かない"],"opening":{"place":"倉庫","present":["佐伯"],"situation":"佐伯 asks."}}""",
        "director" to """{"beats":[{"goal":"佐伯 demands the key","place":"倉庫","characters":["佐伯"],"pressure":"bank"}],"opening":""}""",
        "offscreen" to """{"ops":[{"op":"chronicle","a":"銀行が電話した","b":"","list":["佐伯"],"n":0}]}""",
        "mistakes" to """{"errors":[],"correct_keys":[]}""",
        "adjudicator" to """{"roll":true,"action":"lie to Saeki","difficulty":2,"stakes":"Saeki stops trusting the player"}""",
        "judge" to """{"violations":[]}""", "leak" to """{"guesses":["?"]}""",
        "scribe" to """{"ops":[]}""", "summary" to "Summary.",
        "writer" to """{"passage":"佐伯は帳簿を閉じた。","used":[{"word":"帳簿","form":"帳簿"}],"grammar":[]}""",
    ))

    /** Fails every call whose purpose is in [failing]. */
    private inner class Flaky : Llm {
        val failing = HashSet<String>()
        override fun call(req: LlmRequest): LlmResponse {
            if (req.purpose in failing) throw LlmException("network: offline")
            return fake.call(req)
        }
    }

    private val dir = Files.createTempDirectory("resume").toFile()
    private val llm = Flaky()
    private val pool = DuePool(words = listOf(DueWord("帳簿", 1, false)))
    private val failingSeed = (0..500).first { Dice(Random(it)).roll(2).outcome == Dice.Outcome.FAILURE }

    private fun engine(seed: Int = failingSeed, gap: Long = 3 * 3600_000L) = StoryEngine(dir, llm, CostLog(File(dir, "costs.jsonl")), "s",
        unknownWords = { 10 to emptyList() }, dice = Dice(Random(seed)), settings = StoryEngine.Settings(sessionGapMs = gap))

    private fun ready(): StoryEngine = engine().also { it.setup(); it.startSession(pool) }

    @Test
    fun `a restart shows the same passage, versions and taps`() {
        val e = ready()
        e.playerTurn("嘘をつく。", pool)
        e.reroll()
        e.selectDraft(0)
        e.pending!!.tapped += 1L
        e.savePending()

        val again = engine()
        val t = assertNotNull(again.pending)
        assertEquals(2, t.drafts.size)
        assertEquals(0, t.current)
        assertEquals(setOf(1L), t.tapped)
        assertEquals("嘘をつく。", t.player)
        assertEquals(Dice.Outcome.FAILURE, t.roll!!.outcome)
        assertEquals(listOf("帳簿" to "帳簿"), t.draft.used)
        assertEquals(e.pending!!.packet, t.packet)
        again.commit()
        assertNull(engine().pending)
    }

    @Test
    fun `a failed writer call resumes the same turn - no second roll, no doubled consequence`() {
        val e = ready()
        llm.failing += "writer"
        assertFailsWith<LlmException> { e.playerTurn("嘘をつく。", pool) }
        val consequences = e.state.consequences().size
        assertEquals(1, consequences)
        val adjudications = fake.sent("adjudicator").size

        llm.failing.clear()
        // the app was killed meanwhile: a fresh engine, a different dice seed
        val again = engine(seed = 99)
        assertFalse(again.pending!!.ready)
        val t = again.playerTurn("嘘をつく。", pool)
        assertTrue(t.ready)
        assertEquals(Dice.Outcome.FAILURE, t.roll!!.outcome)
        assertEquals(adjudications, fake.sent("adjudicator").size)
        assertEquals(consequences, again.state.consequences().size)
    }

    @Test
    fun `a waiting passage can't be replaced by a new turn`() {
        val e = ready()
        e.playerTurn(null, pool)
        assertFailsWith<IllegalStateException> { e.playerTurn("もう一回", pool) }
    }

    @Test
    fun `a failed plan doesn't move the world twice`() {
        val e = ready()
        e.playerTurn(null, pool)
        e.commit()
        llm.failing += "director"
        assertFailsWith<Exception> { e.startSession(pool) }
        llm.failing.clear()
        e.startSession(pool)
        assertEquals(1, fake.sent("offscreen").size)
        // and the next session moves it again
        e.playerTurn(null, pool); e.commit()
        e.startSession(pool)
        assertEquals(2, fake.sent("offscreen").size)
    }

    @Test
    fun `sessions start after a long gap, not on every open`() {
        val e = engine(gap = 1000)
        e.setup()
        assertTrue(e.needsNewSession())
        e.startSession(pool)
        assertFalse(e.needsNewSession())
        assertTrue(e.needsNewSession(System.currentTimeMillis() + 5000))
    }

    @Test
    fun `a failed chapter summary is retried before the next turn`() {
        val e = StoryEngine(dir, llm, CostLog(File(dir, "costs.jsonl")), "s", unknownWords = { 10 to emptyList() },
            settings = StoryEngine.Settings(chapterTurns = 1))
        e.setup(); e.startSession(pool)
        e.playerTurn(null, pool)
        llm.failing += "summary"
        e.commit()
        assertEquals(1, e.state.scene().chapter)
        llm.failing.clear()
        e.playerTurn(null, pool)
        assertEquals(2, e.state.scene().chapter)
        assertTrue(File(dir, "state/chapters/1.md").exists())
    }
}
