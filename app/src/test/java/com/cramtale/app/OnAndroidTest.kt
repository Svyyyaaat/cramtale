package com.cramtale.app

import com.cramtale.core.llm.AnthropicLlm
import com.cramtale.core.llm.CostLog
import com.cramtale.core.llm.Llm
import com.cramtale.core.llm.LlmRequest
import com.cramtale.core.llm.LlmResponse
import com.cramtale.core.match.GrammarPatterns
import com.cramtale.core.review.MistakeLog
import com.cramtale.core.story.DueGrammar
import com.cramtale.core.story.DuePool
import com.cramtale.core.story.DueWord
import com.cramtale.core.story.Prompts
import com.cramtale.core.story.StoryEngine
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The story flow as the phone runs it: Android's own libraries (Robolectric), the real Claude SDK
 * client, and a local server standing in for the API with scripted answers. Catches code that works on
 * desktop Java but not on Android (e.g. org.json methods Android doesn't have).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class OnAndroidTest {
    private val server = MockWebServer()
    @Volatile private var purpose = ""
    private val sent = ArrayList<JSONObject>()

    private val replies = mapOf(
        "setup" to """{"world":"森の村。","player":"見習いの薬屋。","characters":[{"name":"ミナ","file":"## Goals\nFind her brother."},
            {"name":"老人","file":"## Goals\nKeep the well shut."}],"secrets":[{"text":"The well hums at night","knows":["老人"]}],
            "clocks":[{"name":"井戸の歌","size":6,"when_full":"the children go missing"}],"complications":["[村] 雨が止まない"],
            "opening":{"place":"村の広場","present":["ミナ"],"situation":"ミナ asks for medicine."}}""",
        "style_picker" to """{"styles":[""" + (1..4).joinToString(",") {
            """{"name":"S$it","line":"Style $it.","passage":"${"ミナは小さな瓶を棚に戻した。".repeat(5)}"}"""
        } + "]}",
        "director" to """{"beats":[{"goal":"ミナ asks","place":"村の広場","characters":["ミナ"],"pressure":"rain"},
            {"goal":"the well","place":"井戸","characters":["老人"],"pressure":"night"},{"goal":"quiet","place":"店","characters":[],"pressure":""}],"opening":""}""",
        "offscreen" to """{"ops":[]}""",
        "mistakes" to """{"errors":[{"wrong":"行くます","right":"行きます","type":"conjugation","reason":"stem","key":"masu after the stem"}]}""",
        "adjudicator" to """{"roll":true,"action":"calm Mina","difficulty":0,"stakes":"Mina leaves"}""",
        "judge" to """{"violations":[]}""",
        "leak" to """{"guesses":["?"]}""",
        "scribe" to """{"ops":[{"op":"chronicle","a":"薬の話","b":"","list":["ミナ"],"n":0}]}""",
        "summary" to "Mina wants medicine.\n雨の場面。",
        "writer" to """{"passage":"ミナは瓶を見た。「これ、高いの？」","used":[{"word":"瓶","form":"瓶"}],"grammar":[]}""",
        "rewrite" to """{"passage":"ミナは瓶を見た。","used":[],"grammar":[]}""",
        "meaning" to "a bottle",
    )

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                synchronized(sent) { sent += body }
                val text = replies[purpose] ?: error("nothing scripted for $purpose")
                val reply = JSONObject().put("id", "msg_1").put("type", "message").put("role", "assistant")
                    .put("model", body.getString("model"))
                    .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                    .put("stop_reason", "end_turn").put("stop_sequence", JSONObject.NULL)
                    .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 5)
                        .put("cache_read_input_tokens", 0).put("cache_creation_input_tokens", 0))
                if (body.optBoolean("stream")) {
                    // the real API streams server-sent events: send the text in two pieces
                    fun ev(type: String, data: JSONObject) = "event: $type\ndata: $data\n\n"
                    val half = text.length / 2
                    fun delta(s: String) = JSONObject().put("type", "content_block_delta").put("index", 0)
                        .put("delta", JSONObject().put("type", "text_delta").put("text", s))
                    val start = JSONObject(reply.toString()).put("content", org.json.JSONArray()).put("stop_reason", JSONObject.NULL)
                    val sse = ev("message_start", JSONObject().put("type", "message_start").put("message", start)) +
                        ev("content_block_start", JSONObject().put("type", "content_block_start").put("index", 0)
                            .put("content_block", JSONObject().put("type", "text").put("text", ""))) +
                        ev("content_block_delta", delta(text.substring(0, half))) + ev("content_block_delta", delta(text.substring(half))) +
                        ev("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", 0)) +
                        ev("message_delta", JSONObject().put("type", "message_delta")
                            .put("delta", JSONObject().put("stop_reason", "end_turn").put("stop_sequence", JSONObject.NULL))
                            .put("usage", JSONObject().put("output_tokens", 5))) +
                        ev("message_stop", JSONObject().put("type", "message_stop"))
                    return MockResponse().setHeader("content-type", "text/event-stream").setBody(sse)
                }
                return MockResponse().setHeader("content-type", "application/json").setBody(reply.toString())
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    @Test
    fun `setup, styles, a session, turns, reroll, edit, chapter and meaning run on Android`() {
        val real = AnthropicLlm("test-key", baseUrl = server.url("/").toString().trimEnd('/'))
        val llm = object : Llm {
            // one at a time: the stand-in server tells calls apart by the purpose set here
            private val lock = Any()
            override fun call(req: LlmRequest): LlmResponse = synchronized(lock) { purpose = req.purpose; real.call(req) }
            override fun stream(req: LlmRequest, onText: (String) -> Unit): LlmResponse = synchronized(lock) { purpose = req.purpose; real.stream(req, onText) }
        }
        val dir = Files.createTempDirectory("story").toFile()
        Prompts(dir).installDefaults(Prompts.PROMPTS + Prompts.CONFIG)
        val e = StoryEngine(dir, llm, CostLog(File(dir, "log/costs.jsonl")), "s1",
            unknownWords = { 20 to emptyList() }, settings = StoryEngine.Settings(chapterTurns = 2))

        e.setup("a cute fantasy with sinister undertones")
        assertEquals(setOf("ミナ", "老人"), e.state.characterNames().toSet())
        val styles = e.styleOptions()
        assertEquals(4, styles.size)
        e.pickStyle(styles[1])

        val pool = DuePool(words = listOf(DueWord("瓶", 1, false)), grammar = listOf(DueGrammar("ことにする", "decide to", 3)))
        e.startSession(pool)
        val t = e.playerTurn(null, pool)
        assertTrue(t.draft.passage.startsWith("ミナ"))
        e.reroll()
        e.edit("ミナは瓶を棚に置いた。")
        e.commit()
        val t2 = e.playerTurn("森に行くます。", pool)
        assertEquals("masu after the stem", t2.corrections.single().key)
        val ops = e.commit()
        assertTrue(ops.toString(), ops.any { it.contains("chapter 2") })
        assertEquals("a bottle", e.meaning("瓶", "ミナは瓶を見た。"))

        // structured output reached the API as JSON schema objects, not strings
        val withSchema = sent.first { it.has("output_config") && it.getJSONObject("output_config").has("format") }
        assertEquals("object", withSchema.getJSONObject("output_config").getJSONObject("format").getJSONObject("schema").getString("type"))

        val mistakes = MistakeLog(File(dir, "log/mistakes.json"))
        mistakes.record("森に行くます。", t2.corrections, emptyList())
        // the grammar patterns compile and match on Android (one used a Java-only regex class)
        val found = GrammarPatterns.bundled()!!.find(com.cramtale.core.text.Tokenizer.tokenize("森に行くことにした。彼は見たがっている。"))
        assertTrue(found.toString(), found.size >= 2)
    }
}
