package com.cramtale.app

import android.os.Looper
import com.cramtale.app.story.StoryController
import com.cramtale.core.llm.FakeLlm
import com.cramtale.core.llm.Llm
import com.cramtale.core.llm.LlmException
import com.cramtale.core.llm.LlmRequest
import com.cramtale.core.llm.LlmResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The story screen's controller on Android: restarts, failed calls and Try again, with Claude scripted.
 * No AnkiDroid and no dictionary here, so nothing is graded; the turn loop is what's tested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoryControllerTest {
    private val fake = FakeLlm(mapOf(
        "setup" to """{"world":"森の村。","player":"見習いの薬屋。","characters":[{"name":"ミナ","file":"## Goals\nFind her brother."},
            {"name":"老人","file":"## Goals\nKeep the well shut."}],"secrets":[],"clocks":[],"complications":["雨が止まない"],
            "opening":{"place":"村の広場","present":["ミナ"],"situation":"ミナ asks for medicine."}}""",
        "style_picker" to """{"styles":[""" + (1..4).joinToString(",") {
            """{"name":"S$it","line":"Style $it.","passage":"${"ミナは小さな瓶を棚に戻した。".repeat(5)}"}"""
        } + "]}",
        "director" to """{"beats":[{"goal":"ミナ asks","place":"村の広場","characters":["ミナ"],"pressure":"rain"}],"opening":""}""",
        "offscreen" to """{"ops":[]}""",
        "mistakes" to """{"errors":[],"correct_keys":[]}""",
        "adjudicator" to """{"roll":true,"action":"calm Mina","difficulty":0,"stakes":"Mina leaves"}""",
        "judge" to """{"violations":[]}""", "leak" to """{"guesses":["?"]}""",
        "scribe" to """{"ops":[]}""", "summary" to "Summary.",
        "writer" to """{"passage":"ミナは瓶を見た。「これ、高いの？」","used":[],"grammar":[]}""",
        "meaning" to "a bottle",
    ))
    private val failing = HashSet<String>()
    private val llm = object : Llm {
        override fun call(req: LlmRequest): LlmResponse {
            if (req.purpose in failing) throw LlmException("network: offline", retryable = true, cause = java.net.UnknownHostException("api.anthropic.com"))
            return fake.call(req)
        }
    }
    private val context get() = RuntimeEnvironment.getApplication()

    private fun controller() = StoryController(context, AppState(context), llmFor = { llm }, fixedKey = "test")

    /** Runs the main looper until the controller's background work is done. */
    private fun settle(c: StoryController) {
        val until = System.currentTimeMillis() + 10_000
        val started = System.currentTimeMillis() + 2_000
        while (!c.isWorking && c.busy == null && System.currentTimeMillis() < started) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        do { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) } while (c.isWorking && System.currentTimeMillis() < until)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Before fun clean() { File(context.filesDir, "story").deleteRecursively() }

    @Test
    fun `restart shows the waiting passage without writing a new one`() = runBlocking {
        val c = controller()
        c.setup("a cute fantasy")
        assertTrue(c.error ?: "", c.styleOptions() != null)
        // the four samples are kept: asking again (a rebuilt screen) costs nothing
        c.styleOptions()
        assertEquals(1, fake.sent("style_picker").size)
        c.start()
        val shown = c.current!!.passage.text
        val writes = fake.sent("writer").size

        val again = controller()
        again.start()
        assertEquals(shown, again.current!!.passage.text)
        assertEquals(writes, fake.sent("writer").size)
        assertEquals("one session, not one per open", 1, fake.sent("director").size)
    }

    @Test
    fun `a failed send keeps the reply, Try again finishes the same turn`() = runBlocking {
        val c = controller()
        c.setup("")
        c.start()
        c.replyDraft = "薬をあげる。"
        failing += "writer"
        c.send("薬をあげる。")
        assertNull(c.current)
        assertTrue(c.error!!, c.error!!.startsWith("Can't reach Claude"))
        assertEquals("薬をあげる。", c.replyDraft)
        val adjudications = fake.sent("adjudicator").size

        failing.clear()
        c.retry!!.invoke()
        settle(c)
        assertNull(c.error)
        assertEquals("薬をあげる。", c.current!!.player)
        assertEquals("", c.replyDraft)
        assertEquals("the roll isn't redone", adjudications, fake.sent("adjudicator").size)
        assertEquals("the earlier passage is in the history", 1, c.history.size)
    }

    @Test
    fun `a reply sent just before a crash is answered after the restart`() = runBlocking {
        val c = controller()
        c.setup("")
        c.start()
        failing += "mistakes"   // fails before the turn exists
        c.send("こんにちは。")
        failing.clear()

        val again = controller()
        again.start()
        assertEquals("こんにちは。", again.current!!.player)
    }

    @Test
    fun `reroll keeps versions you can switch between, and they survive a restart`() = runBlocking {
        val c = controller()
        c.setup("")
        c.start()
        fake.queue("writer", """{"passage":"老人は井戸の蓋を閉めた。","used":[],"grammar":[]}""")
        c.reroll()
        assertEquals(2, c.current!!.passage.versions)
        assertEquals("老人は井戸の蓋を閉めた。", c.current!!.passage.text)
        c.selectVersion(0)
        assertEquals(0, c.current!!.passage.version)

        val again = controller()
        again.start()
        assertEquals(2, again.current!!.passage.versions)
        assertEquals(0, again.current!!.passage.version)
    }
}
