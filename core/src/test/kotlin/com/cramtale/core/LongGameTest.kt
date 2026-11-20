package com.cramtale.core

import com.cramtale.core.llm.CostLog
import com.cramtale.core.llm.Llm
import com.cramtale.core.llm.LlmRequest
import com.cramtale.core.llm.LlmResponse
import com.cramtale.core.llm.Usage
import com.cramtale.core.review.CardInfo
import com.cramtale.core.review.CardRef
import com.cramtale.core.review.Grade
import com.cramtale.core.review.GradeLog
import com.cramtale.core.review.GradingEngine
import com.cramtale.core.review.MistakeLog
import com.cramtale.core.review.Mode
import com.cramtale.core.story.Dice
import com.cramtale.core.story.DueGrammar
import com.cramtale.core.story.DuePool
import com.cramtale.core.story.DueWord
import com.cramtale.core.story.StoryEngine
import com.cramtale.core.story.StoryState
import com.cramtale.core.story.UnknownWords
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A game of hundreds of hours, compressed: hundreds of turns whose every passage adds to the record (events,
 * things people learn, facts about the player, debts nobody settles, new faces, people who come to matter) and to
 * the learning data (a fresh review pool every turn, a new mistake key every turn, words seen, grades). What each
 * call is sent must stop growing; what's old must survive in the long memory.
 */
class LongGameTest {
    private var turn = 0

    /** Each scribe call makes the record grow the way a busy story does; each check finds a new mistake. */
    private inner class Busy : Llm {
        val sent = ArrayList<LlmRequest>()
        override fun call(req: LlmRequest): LlmResponse {
            synchronized(sent) { sent += req }
            val t = turn
            val text = when (req.purpose) {
                "setup" -> """{"title":"Salt","world":"A salt town. The works pay badly.","player":"Aki, a clerk at the salt works who skims.",
                    "history":[{"text":"The clerk took the job two winters ago","witnesses":["PLAYER","Ada"]}],"player_public":"The works' clerk.",
                    "characters":[{"name":"Ada","file":"## Goals\nRun the works.\n## Knows\nThe clerk is reliable.\n## Attitude\nCool."},
                                  {"name":"Bram","file":"## Goals\nOpen a rival works.\n## Knows\nThe clerk drinks at the Anchor.\n## Attitude\nWarm."}],
                    "extras":[],"secrets":[],"clocks":[{"name":"The audit","size":8,"when_full":"the books are opened"}],
                    "complications":["A cart breaks"],"ambience":[],"opening":{"place":"the works","present":["Ada","Bram"],"situation":"Pay day."}}"""
                "director" -> """{"beats":[{"goal":"pay day","place":"the works","characters":["Ada"],"pressure":"money"},
                    {"goal":"the rival","place":"the Anchor","characters":["Bram"],"pressure":"time"},{"goal":"quiet","place":"home","characters":[],"pressure":""}],"opening":""}"""
                "scribe" -> scribe()
                "adjudicator" -> """{"roll":false,"action":"","difficulty":0,"stakes":"","observable":"the clerk says something"}"""
                // a new kind of mistake every turn, and an old one now and then
                "mistakes" -> """{"errors":[{"wrong":"数えます","right":"数える","type":"other","reason":"r","key":"mistake kind $t","level":"error"},
                    {"wrong":"コイン","right":"硬貨","type":"word choice","reason":"r","key":"mistake kind ${t / 7}","level":"nitpick"}],"correct_keys":["mistake kind ${t - 3}"]}"""
                "intent" -> """{"notices":"the clerk","wants":"their pay","does":"asks"}"""
                "judge" -> """{"violations":[]}"""
                "leak" -> """{"guesses":["?","?"]}"""
                "summary" -> "Chapter summary: pay day came and went; Ada counted; Bram waited."
                "saga" -> "## What happened\nThe clerk skimmed the works for a long time.\n## Where things stand\nAda suspects nothing yet."
                "remember" -> """{"remembers":"I have known the clerk a long time; pay days, the audit, Bram's offers."}"""
                "condense" -> "- skims the works\n- drinks at the Anchor"
                "offscreen" -> """{"ops":[]}"""
                "knows_check" -> """{"knows":"The clerk is reliable."}"""
                "writer" -> """{"passage":"アダは単語${t}_0と単語${t}_1を二回数えた。「また足りない」","used":[{"word":"単語${t}_0","form":"単語${t}_0"},{"word":"単語${t}_1","form":"単語${t}_1"}],"grammar":[]}"""
                else -> error("unscripted ${req.purpose}")
            }
            return LlmResponse(text, Usage(1000, 100, 0, 0), "end_turn", req.model)
        }

        private fun scribe(): String {
            val t = turn
            val ops = mutableListOf(
                """{"op":"event","a":"On turn $t the clerk and Ada argued over the ledger, page $t","b":"","list":["PLAYER","Ada","Bram"],"n":0}""",
                """{"op":"event","a":"Bram mentioned buyer number $t","b":"","list":["PLAYER","Bram"],"n":0}""",
                """{"op":"learns","a":"Ada","b":"fact $t about the ledger","list":[],"n":0}""",
                """{"op":"player_fact","a":"the clerk was seen at the docks on turn $t","b":"","list":[],"n":0}""",
                """{"op":"consequence","a":"Debt number $t to the Anchor","b":"","list":[],"n":0}""",
                """{"op":"new_secret","a":"Secret number $t","b":"","list":["Person$t"],"n":0}""",
                """{"op":"extra","a":"Person$t","b":"a dock worker who saw the clerk on turn $t","list":[],"n":0}""",
                """{"op":"chronicle","a":"Outcome $t","b":"","list":["the works"],"n":0}""",
            )
            // every fifth turn someone comes to matter
            if (t % 5 == 0 && t > 0) ops += """{"op":"promote","a":"Person${t - 1}","b":"## Goals\nGet paid ($t).\n## Knows\nSaw the clerk.\n## Attitude\nWary.","list":[],"n":0}"""
            return """{"ops":[${ops.joinToString(",")}]}"""
        }

        fun last(purpose: String) = synchronized(sent) { sent.last { it.purpose == purpose } }
        fun count(purpose: String) = synchronized(sent) { sent.count { it.purpose == purpose } }
        fun size(r: LlmRequest) = (r.system + r.messages).sumOf { it.text.length }
    }

    /** A fresh review pool every turn, as a heavy Anki day would give: the full fifteen words and three patterns. */
    private fun pool(t: Int, mistakes: MistakeLog) = DuePool(
        words = (0 until 15).map { DueWord("単語${t}_$it", t * 100L + it, isNew = it % 4 == 0) },
        grammar = (0 until 3).map { DueGrammar("〜パターン${t}_$it", "pattern $t/$it", 900_000L + t * 10 + it) },
        weakPoints = mistakes.weakPoints(),
    )

    @Test
    fun `hundreds of turns - prompts stay the same size, the past stays remembered`() {
        val dir = Files.createTempDirectory("long").toFile()
        val llm = Busy()
        val mistakes = MistakeLog(File(dir, "log/mistakes.json"))
        val grades = GradeLog(File(dir, "log/grades.jsonl"))
        // every passage brings a few words the reader hasn't met (the seen-words list grows)
        val unknown = UnknownWords { text -> 40 to listOf("未知${turn}a", "未知${turn}b") }
        val e = StoryEngine(dir, llm, CostLog(File(dir, "log/costs.jsonl")), "s", unknown, dice = Dice(Random(1)),
            settings = StoryEngine.Settings(chapterTurns = 10, sessionGapMs = Long.MAX_VALUE / 4))
        e.setup()
        e.startSession(pool(0, mistakes))
        val early = HashMap<String, Int>()
        val late = HashMap<String, Int>()
        val total = 400
        val measured = listOf("writer", "intent", "scribe", "judge", "mistakes", "adjudicator")
        for (i in 0 until total) {
            turn = i
            val msg = if (i % 3 == 0) null else "コインを数えます。"
            val t = e.playerTurn(msg, pool(i, mistakes), mistakes.promptKeys(), mistakes::seen)
            if (msg != null) mistakes.record(msg, t.corrections, t.correctKeys)
            // the reads are graded (read-only): fifteen answers a turn into the grade log
            for (w in t.pool.words) grades.record(GradingEngine.Outcome(Grade(CardRef(w.noteId, 0), w.word, 3, "read", CardInfo(2, 2, 0), 0),
                sent = false, note = "read-only"), Mode.READ_ONLY)
            e.commit()
            // the same point in a chapter (its last turn: the writer's history is longest there)
            val sample = when (i) { 69 -> early; total - 1 -> late; else -> null }
            sample?.let { m -> for (p in measured) m[p] = llm.size(llm.last(p)) }
        }
        e.startSession(pool(total, mistakes))
        late["director"] = llm.size(llm.last("director"))
        early["director"] = llm.size(llm.sent.first { it.purpose == "director" }) + 20_000  // the first plan had almost no story yet

        for ((p, n) in late) {
            val before = early.getValue(p)
            println("$p: turn 69 = $before chars, turn $total = $n chars")
            assertTrue(n <= before * 1.5 + 2000, "$p grew from $before to $n characters")
            assertTrue(n < 120_000, "$p is $n characters")
        }

        // every closed chapter went into the long memory
        val chapters = e.state.scene().chapter - 1
        assertEquals(chapters, llm.count("saga"))
        assertTrue(e.saga()!!.isNotBlank())
        assertTrue("story so far, from the beginning" in llm.last("writer").system.drop(1).joinToString { it.text })
        // people's files hold a long memory instead of an endless list of what they learned
        val ada = e.state.character("Ada")!!
        assertTrue("## Remembers" in ada, ada)
        assertTrue(ada.length < 2000, "Ada's file is ${ada.length} characters")
        val sheet = File(dir, "state/player.md").readText()
        assertTrue(StoryState.section(sheet, "Established in the story")!!.lines().size < 40, sheet)
        // debts nobody settles don't pile up forever in what the game master reads
        assertTrue(e.state.consequences().count { it.open } <= 40)
        // the whole record is still there: nothing is deleted
        assertTrue(e.state.events().size >= total * 2)

        // the learning data: hundreds of mistake kinds, the checker is shown the recent ones; examples stay few
        assertTrue(mistakes.all.size > 250, "${mistakes.all.size} kinds")
        assertTrue(mistakes.all.all { it.examples.size <= MistakeLog.MAX_EXAMPLES })
        assertTrue(File(dir, "log/mistakes.json").length() < 600_000, "mistakes.json is ${File(dir, "log/mistakes.json").length()} bytes")
        // a repeat of an old kind is still recognised, though it's no longer in the checker's list
        assertTrue(mistakes.seen("mistake kind 3") && "mistake kind 3" !in mistakes.promptKeys())
        // six thousand grades on disk, a bounded tail in memory, today's count read from the end
        assertEquals(total * 15, File(dir, "log/grades.jsonl").readLines().size)
        val reopened = GradeLog(File(dir, "log/grades.jsonl"))
        assertTrue(reopened.entries.size <= GradeLog.KEEP, "${reopened.entries.size} grades in memory")
        // (here all six thousand fall in one Anki day; a real day reads far fewer than the tail holds)
        assertEquals(minOf(total * 15, GradeLog.KEEP), reopened.today().cards)
        // the slim whole log has no packets; the chapter files have them
        val slim = File(dir, "log/turns.jsonl").tailJson(1).single()
        assertTrue(!slim.has("packet") && slim.has("passage"))
        assertTrue(File(dir, "log/chapters/1.jsonl").readText().contains("\"packet\""))
    }
}
