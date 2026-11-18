package com.cramtale.core

import com.cramtale.core.llm.CostLog
import com.cramtale.core.llm.FakeLlm
import com.cramtale.core.llm.Models
import com.cramtale.core.llm.Role
import com.cramtale.core.story.Dice
import com.cramtale.core.story.DueGrammar
import com.cramtale.core.story.DuePool
import com.cramtale.core.story.DueWord
import com.cramtale.core.story.StoryEngine
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The turn loop against scripted model replies: what gets sent, what code decides, what gets recorded. */
class StoryEngineTest {
    private val setupJson = """{"world":"港町の倉庫街。銀行が貸し渋っている。","player":"小さな食堂の店主。佐伯に八万円の借りがある。",
        "characters":[{"name":"佐伯","file":"## Goals\nGet the warehouse key.\n## Secrets\nOwes the bank."},{"name":"北村","file":"## Goals\nSell the warehouse."}],
        "secrets":[{"text":"佐伯 owes the bank three million","knows":["佐伯"]}],
        "clocks":[{"name":"銀行の差し押さえ","size":6,"when_full":"the bank seizes the warehouse"}],
        "complications":["[倉庫] 荷物が届かない"],
        "opening":{"place":"倉庫","present":["佐伯"],"situation":"佐伯 asks for the key."}}"""

    private fun engine(llm: FakeLlm, dice: Dice = Dice(Random(3)), chapterTurns: Int = 30): StoryEngine {
        val dir = Files.createTempDirectory("story").toFile()
        val e = StoryEngine(dir, llm, CostLog(File(dir, "costs.jsonl")), "s1",
            unknownWords = { text -> if ("輪ゴム" in text) 20 to listOf("輪ゴム") else 20 to emptyList() },
            dice = dice, settings = StoryEngine.Settings(chapterTurns = chapterTurns))
        llm.queue("setup", setupJson)
        e.setup()
        return e
    }

    private fun fake() = FakeLlm(mapOf(
        "director" to """{"beats":[{"goal":"佐伯 demands the key","place":"倉庫","characters":["佐伯"],"pressure":"the bank calls"},
            {"goal":"北村 offers a deal","place":"店","characters":["北村"],"pressure":"rent"},{"goal":"quiet","place":"店","characters":[],"pressure":""}],"opening":""}""",
        "mistakes" to """{"errors":[{"wrong":"渡すます","right":"渡します","type":"conjugation","reason":"masu attaches to the stem","key":"masu after the stem"}]}""",
        "adjudicator" to """{"roll":true,"action":"stall Saeki","difficulty":2,"stakes":"Saeki stops trusting the player"}""",
        "intent" to """{"notices":"the player","wants":"the key","does":"asks again"}""",
        "judge" to """{"violations":[]}""",
        "leak" to """{"guesses":["?"]}""",
        "scribe" to """{"ops":[{"op":"chronicle","a":"鍵の話が出た","b":"","list":["倉庫","佐伯"],"n":0},{"op":"reveal","a":"S1","b":"北村","list":[],"n":0}]}""",
        "summary" to "Saeki wants the key.\n最後の場面。",
        "writer" to """{"passage":"佐伯は帳簿を閉じた。「鍵は明日だな」","used":[{"word":"帳簿","form":"帳簿"}],"grammar":[]}""",
    ))

    private val pool = DuePool(
        words = listOf(DueWord("帳簿", 1, false), DueWord("倉庫", 2, true)),
        grammar = listOf(DueGrammar("ことにする", "decide to", 3)),
        weakPoints = listOf("い-adjective past: 〜いでした → 〜かったです"),
    )

    @Test
    fun `setup writes the world, cast, secrets, clocks and the opening scene`() {
        val e = engine(fake())
        assertEquals(setOf("佐伯", "北村"), e.state.characterNames().toSet())
        assertEquals("銀行の差し押さえ", e.state.clocks().single().name)
        assertEquals("倉庫", e.state.scene().place)
        assertTrue(File(e.dir, "config/complications.md").readText().contains("荷物が届かない"))
    }

    @Test
    fun `a turn - mistakes, a code-decided roll, the writer's packet, and commit`() {
        val llm = fake()
        // with difficulty 2 the modifier is -2: find a seed that fails
        val failing = (0..200).first { Dice(Random(it)).roll(2).outcome == Dice.Outcome.FAILURE }
        val e = engine(llm, Dice(Random(failing)))
        e.startSession(pool)
        val t = e.playerTurn("明日、鍵を渡すます。", pool, knownMistakeKeys = emptyList())
        assertEquals("masu after the stem", t.corrections.single().key)
        assertEquals(Dice.Outcome.FAILURE, t.roll!!.outcome)
        // failure is written down before the writer sees it
        assertTrue(e.state.consequences().any { it.text.startsWith("Failed: stall Saeki") })

        val req = llm.sent("writer").last()
        assertEquals(Models.WRITER, req.model)
        assertTrue(req.system.first().cache, "fixed writer prompt is cached")
        val packet = req.messages.last()
        assertEquals(Role.SYSTEM, packet.role, "per-turn facts come as a mid-conversation system message")
        assertEquals("明日、鍵を渡すます。", req.messages[req.messages.size - 2].text)
        for (needle in listOf("failure", "帳簿、倉庫", "ことにする", "かったです", "佐伯 demands the key", "Owes the bank")) {
            assertTrue(needle in packet.text, "packet lacks $needle")
        }
        assertFalse("{{" in packet.text || "{{" in req.system.first().text, "unfilled placeholder")
        // the style line is the last thing the writer reads
        assertTrue(packet.text.trimEnd().endsWith("Plain, concrete prose."), packet.text.takeLast(200))
        assertEquals(listOf("帳簿" to "帳簿"), t.draft.used)

        val ops = e.commit()
        assertTrue(ops.any { it.startsWith("chronicle") })
        // 北村 wasn't in the scene: the scribe can't reveal the secret to him
        assertTrue(ops.any { it.startsWith("refused") }, ops.toString())
        assertEquals(1, e.state.scene().turn)
    }

    @Test
    fun `a banned phrase or too many unknown words get one combined rewrite`() {
        val llm = fake()
        llm.queue("writer", """{"passage":"佐伯は息を呑んだ。輪ゴムが切れた。","used":[],"grammar":[]}""")
        llm.queue("rewrite", """{"passage":"佐伯は帳簿を閉じた。","used":[{"word":"帳簿","form":"帳簿"}],"grammar":[]}""")
        val e = engine(llm)
        e.startSession(pool)
        val t = e.playerTurn(null, pool)
        assertTrue(t.draft.rewritten)
        assertEquals("佐伯は帳簿を閉じた。", t.draft.passage)
        val rewriteReq = llm.sent("rewrite").single()
        assertEquals(Role.USER, rewriteReq.messages.last().role)
        assertTrue("息を呑んだ" in rewriteReq.messages.last().text && "輪ゴム" in rewriteReq.messages.last().text)
    }

    @Test
    fun `leak check - a correctly guessed review word sends its sentence back`() {
        val llm = fake()
        llm.queue("leak", """{"guesses":["帳簿"]}""")
        llm.queue("rewrite", """{"passage":"佐伯は帳簿を見た。","used":[{"word":"帳簿","form":"帳簿"}],"grammar":[]}""")
        val e = engine(llm)
        e.startSession(pool)
        e.playerTurn(null, pool)
        assertTrue("□□" in llm.sent("leak").single().messages.single().text)
        assertTrue(llm.sent("rewrite").single().messages.last().text.contains("too easy to guess"))
    }

    @Test
    fun `reroll keeps earlier versions, chapters roll over with a summary`() {
        val e = engine(fake(), chapterTurns = 2)
        e.startSession(pool)
        e.playerTurn(null, pool)
        e.reroll()
        assertEquals(2, e.pending!!.drafts.size)
        e.commit()
        e.playerTurn("はい。", pool)
        val ops = e.commit()
        assertTrue(ops.any { it.contains("chapter 2") }, ops.toString())
        assertTrue(File(e.dir, "state/chapters/1.md").exists())
    }
}
