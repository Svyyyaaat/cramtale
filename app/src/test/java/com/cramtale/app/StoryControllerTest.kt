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
        "setup" to """{"title":"The Well","world":"森の村。","player":"見習いの薬屋。","characters":[{"name":"ミナ","file":"## Goals\nFind her brother."},
            {"name":"老人","file":"## Goals\nKeep the well shut."}],"secrets":[],"clocks":[],"complications":["雨が止まない"],
            "opening":{"place":"村の広場","present":["ミナ"],"situation":"ミナ asks for medicine."}}""",
        "style_picker" to """{"styles":[""" + (1..4).joinToString(",") {
            """{"name":"S$it","line":"Style $it.","passage":"${"ミナは小さな瓶を棚に戻した。".repeat(5)}"}"""
        } + "]}",
        "director" to """{"beats":[{"goal":"ミナ asks","place":"村の広場","characters":["ミナ"],"pressure":"rain"}],"opening":""}""",
        "offscreen" to """{"ops":[]}""",
        "mistakes" to """{"errors":[],"correct_keys":[]}""",
        "adjudicator" to """{"roll":true,"action":"calm Mina","difficulty":0,"stakes":"Mina leaves"}""",
        "intent" to """{"notices":"the player","wants":"the key","does":"asks again"}""",
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

    @Before fun clean() {
        File(context.filesDir, "story").deleteRecursively(); File(context.filesDir, "story-archive").deleteRecursively()
        File(context.filesDir, "new-story").deleteRecursively()
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

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

    @Test
    fun `a new story archives the old one and keeps your style, taste and mistakes`() = runBlocking {
        val c = controller()
        c.setup("")
        c.pickStyle(c.styleOptions()!!.first().first)
        c.start()
        File(c.dir, "log/mistakes.json").writeText("[]")
        File(c.dir, "config/plot.md").writeText("my taste")
        val archive = c.archiveAndReset()!!
        assertTrue(File(archive, "state/world.md").exists())
        assertTrue("the story itself is gone", !c.isSetUp())
        assertTrue("the picked style stays", c.hasStyle())
        assertEquals("my taste", File(c.dir, "config/plot.md").readText())
        assertTrue(File(c.dir, "log/mistakes.json").exists())
        assertNull(c.current)
        assertTrue(c.history.isEmpty())

        // choose the style again, then back out: the old one returns
        c.clearStyle()
        assertTrue(!c.hasStyle())
        assertTrue(c.restoreStyle())
        assertTrue(c.hasStyle())
    }

    @Test
    fun `a reply whose send failed at the commit survives a restart`() = runBlocking {
        val c = controller()
        c.setup("")
        // the scribe fails throughout (the early background run as well), so the send fails at the commit
        failing += "scribe"
        c.start()
        val shown = c.current!!.passage.text
        c.send("ありがとう。")
        failing.clear()

        val again = controller()
        again.start()
        assertEquals("the same passage is still waiting", shown, again.current!!.passage.text)
        assertEquals("ありがとう。", again.replyDraft)
        again.send(again.replyDraft)
        assertEquals("ありがとう。", again.current!!.player)
        assertTrue(!File(again.dir, "log/outbox.txt").exists())
    }

    @Test
    fun `already knowing a new card skips it and counts the word as known, with undo`() {
        val app = AppState(context)
        val c = StoryController(context, app, llmFor = { llm }, fixedKey = "test")
        val w = com.cramtale.core.match.DeckWord(42, "Core", "Japanese Core 5k", "書類", null, com.cramtale.core.match.CardState.NEW,
            listOf(com.cramtale.core.match.RawCard(0, "Recognition", 0, 0)))
        c.alreadyKnow(listOf(w))
        assertTrue(42L in c.knownNew)
        assertTrue("書類" in app.lists.known)
        c.undoAlreadyKnow(42)
        assertTrue(42L !in c.knownNew)
        assertTrue("書類" !in app.lists.known)
    }

    @Test
    fun `undo keeps a word that was known before Already know it`() {
        val app = AppState(context)
        app.lists = app.lists.copy(known = app.lists.known + "許可")
        val c = StoryController(context, app, llmFor = { llm }, fixedKey = "test")
        val w = com.cramtale.core.match.DeckWord(43, "Core", "Japanese Core 5k", "許可", null, com.cramtale.core.match.CardState.NEW,
            listOf(com.cramtale.core.match.RawCard(0, "Recognition", 0, 0)))
        c.alreadyKnow(listOf(w))
        c.undoAlreadyKnow(43)
        assertTrue("許可" in app.lists.known)
    }

    @Test
    fun `a sent message shows as a bubble at once and comes back to the box if the turn fails`() = runBlocking {
        val c = controller()
        c.setup("")
        c.start()
        failing += "writer"
        c.replyDraft = "音もしない。"
        c.submit("音もしない。")
        assertEquals("音もしない。", c.sending)
        assertEquals("", c.replyDraft)
        settle(c)
        // the failed turn hands the text back on the main thread: give it the moment it needs under load
        val until = System.currentTimeMillis() + 5_000
        while (c.sending != null && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        assertNull(c.sending)
        assertEquals("the text is back in the box", "音もしない。", c.replyDraft)

        failing.clear()
        c.submit(c.replyDraft)
        settle(c)
        assertNull(c.sending)
        assertEquals("音もしない。", c.current!!.player)
        assertEquals("", c.replyDraft)
    }

    @Test
    fun `a turn runs in a foreground service that stops when it's done`() = runBlocking {
        val c = controller()
        c.setup("")
        val app = shadowOf(RuntimeEnvironment.getApplication())
        while (app.nextStartedService != null) {}   // drop what setup started and stopped
        c.start()
        val started = generateSequence { app.nextStartedService }.toList()
        assertTrue(started.toString(), started.any { it.component?.className == com.cramtale.app.story.TurnService::class.java.name && it.action == null })
        assertEquals("stop", started.last().action)
    }

    @Test
    fun `stories can be picked up again, and what's yours follows you`() = runBlocking {
        val c = controller()
        c.setup("")
        c.pickStyle(com.cramtale.core.story.StylePresets.ALL[1])
        c.start(); settle(c)
        File(c.dir, "state/title.txt").writeText("First\n")
        c.archiveAndReset()!!
        c.setup("")
        File(c.dir, "state/title.txt").writeText("Second\n")
        File(c.dir, "log/mistakes.json").writeText("[\"new mistake\"]")
        val all = c.stories()
        assertEquals(listOf("Second", "First"), all.map { it.title })
        assertTrue(all[0].current)
        assertEquals("Light novel", all[1].style)
        assertTrue(c.switchTo(all[1].dir))
        assertEquals("First", c.stories().first { it.current }.title)
        assertEquals(listOf("First", "Second"), c.stories().map { it.title })
        assertEquals("your mistakes come along", "[\"new mistake\"]", File(c.dir, "log/mistakes.json").readText())
        assertEquals("Light novel", c.styleName())
        assertTrue("the first story's turn is still there", c.turn() >= 0 && File(c.dir, "log/turns.jsonl").exists() || File(c.dir, "state/pending.json").exists())
        // nothing outside the archive can be switched to
        assertTrue(!c.switchTo(File(context.filesDir, "elsewhere").apply { mkdirs() }))
    }

    @Test
    fun `a story saved as a zip opens again, with your history, and a crafted zip is refused`() = runBlocking {
        val c = controller()
        c.setup("")
        File(c.dir, "state/title.txt").writeText("Backed up\n")
        File(c.dir, "log/mistakes.json").writeText("[]")
        File(c.dir, "log/grades.jsonl").writeText("{\"at\":1}\n")
        File(c.dir, "log/calls.jsonl").writeText("debug\n")
        val zip = java.io.ByteArrayOutputStream()
        assertTrue(c.exportStory(c.dir, zip))
        val names = java.util.zip.ZipInputStream(zip.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertTrue("state/world.md" in names && "log/mistakes.json" in names)
        assertTrue("the debug log stays out", names.none { it.contains("calls") })

        // a phone with a history keeps its own
        val kept = c.importStory(zip.toByteArray().inputStream())
        assertTrue(kept.message, kept.ok && "kept" in kept.message)
        assertEquals(2, c.stories().count { it.title == "Backed up" })

        // a fresh phone takes the history from the backup
        File(c.dir, "log/mistakes.json").delete(); File(c.dir, "log/grades.jsonl").delete()
        val fresh = c.importStory(zip.toByteArray().inputStream())
        assertTrue(fresh.message, fresh.ok && "came with it" in fresh.message)
        assertTrue(File(c.dir, "log/grades.jsonl").exists())

        // ../ in an entry name: refused, nothing written outside the shelf
        val evil = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(evil).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("state/world.md")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("../../escaped.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        val r = c.importStory(evil.toByteArray().inputStream())
        assertTrue(!r.ok)
        assertTrue(!File(context.filesDir, "escaped.txt").exists() && !File(context.filesDir.parentFile, "escaped.txt").exists())
        assertTrue(!c.importStory("not a zip".byteInputStream()).ok)
    }

    @Test
    fun `what you type survives a restart, and a new story only replaces this one on Create`() = runBlocking {
        val c = controller()
        c.setup("")
        File(c.dir, "state/title.txt").writeText("Mine\n")
        c.replyDraft = "「待って」と言う"
        assertEquals("the reply is still there after a restart", "「待って」と言う", controller().replyDraft)

        // start a new story, type, back out: nothing moved
        assertTrue(c.beginNewStory())
        c.seedDraft = "a ghost at a café"
        assertEquals("a ghost at a café", controller().seedDraft)
        File(c.staging, "state/sources").mkdirs(); File(c.staging, "state/sources/lore.md").writeText("lore")
        c.cancelNewStory()
        assertTrue(c.isSetUp() && !c.newStoryAsked)
        assertEquals(listOf("Mine"), c.stories().map { it.title })
        assertTrue(!c.staging.exists())

        // again, and Create: this one goes onto the shelf, the picked files come along
        assertTrue(c.beginNewStory())
        File(c.staging, "state/sources").mkdirs(); File(c.staging, "state/sources/lore.md").writeText("lore")
        assertTrue(c.commitNewStory())
        assertTrue(!c.isSetUp())
        assertEquals("lore", File(c.dir, "state/sources/lore.md").readText())
        assertEquals(listOf("Mine"), c.stories().map { it.title })
    }

    @Test
    fun `a sequel of the story you're in continues it, and binary files are refused`() = runBlocking {
        val c = controller()
        c.setup("")
        File(c.dir, "state/title.txt").writeText("Well\n")
        assertTrue(c.beginNewStory(c.dir))
        assertEquals("Well", c.sequelTitle())
        assertTrue(c.attach("scan.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46, 0, 0, 1))!!.contains("isn't a text file"))
        assertNull(c.attach("notes.md", "Mina has a brother.".toByteArray()))
        assertEquals(listOf("notes.md"), c.attachments().map { it.name })
        assertTrue(c.commitNewStory())
        c.setup("a year later")
        assertEquals("Well II", File(c.dir, "state/title.txt").readText().trim())
        assertTrue(File(c.dir, "state/characters").listFiles()!!.isNotEmpty())
        assertEquals(2, c.stories().size)
    }
}
