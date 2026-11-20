package com.cramtale.app.story

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cramtale.app.AppState
import com.cramtale.app.anki.AnkiReader
import com.cramtale.core.tailJson
import com.cramtale.core.llm.AnthropicLlm
import com.cramtale.core.llm.ClaudeCodeLlm
import com.cramtale.core.llm.CostLog
import com.cramtale.core.llm.Prices
import com.cramtale.core.match.Analysis
import com.cramtale.core.match.DeckWord
import com.cramtale.core.match.GrammarPatterns
import com.cramtale.core.match.Span
import com.cramtale.core.match.Status
import com.cramtale.core.review.DueSnapshot
import com.cramtale.core.review.GradeLog
import com.cramtale.core.review.GradingEngine
import com.cramtale.core.review.MistakeLog
import com.cramtale.core.review.Mode
import com.cramtale.core.review.PassageRead
import com.cramtale.core.review.PendingReads
import com.cramtale.core.review.ReviewPlanner
import com.cramtale.core.story.Correction
import com.cramtale.core.story.Draft
import com.cramtale.core.story.DuePool
import com.cramtale.core.story.StoryEngine
import com.cramtale.core.story.UnknownWords
import com.cramtale.core.jsonLines
import com.cramtale.core.tailJson
import com.cramtale.core.llm.ErrorText
import com.cramtale.core.story.PendingTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One passage as shown: its words and grammar, which are tappable. */
data class ShownPassage(
    val turn: Int,
    val text: String,
    val spans: List<Span>,
    val grammar: List<GrammarPatterns.Found>,
    /** Notes of review cards that are new: their meaning shows inline. */
    val newNotes: Set<Long>,
    val glosses: Map<Long, String>,
    val checks: List<String>,
    val gmNotes: List<String>,
    /** Which version of this passage is shown, of how many (Reroll and Edit add versions). */
    val version: Int = 0,
    val versions: Int = 1,
)

data class ShownTurn(val player: String?, val corrections: List<Correction>, val passage: ShownPassage)

/**
 * Runs the story on the phone: Anki's due cards in, a passage out, taps and grades in read-only
 * mode (PLAN.md §4.4: nothing is sent to AnkiDroid in this build).
 */
class StoryController(
    private val context: Context,
    private val app: AppState,
    /** The Claude client for a key (tests pass a scripted one). */
    private val llmFor: (String) -> com.cramtale.core.llm.Llm = { AnthropicLlm(it) },
    /** A key that bypasses the keystore (tests). */
    private val fixedKey: String? = null,
) {
    val dir = File(context.filesDir, "story").apply { mkdirs() }
    private val keys = ApiKeyStore(context)
    val costs = CostLog(File(dir, "log/costs.jsonl"), Prices.DEFAULT)
    val session = UUID.randomUUID().toString().take(8)
    private val grammar = GrammarPatterns.bundled()
    private val planner = ReviewPlanner(grammar, File(dir, "log/skips.json"))
    val mistakes = MistakeLog(File(dir, "log/mistakes.json"))
    val gradeLog = GradeLog(File(dir, "log/grades.jsonl"))
    /** New cards you already know: skipped here, would be suspended in Anki once writing is on. */
    val knownNew = com.cramtale.core.review.KnownNewCards(File(dir, "log/known-new.json"))
    /** Cards answered as flashcards while waiting: out of the story's reviews for the rest of the day. */
    val flashcards = com.cramtale.core.review.Flashcards(File(dir, "log/flashcards.json"))

    /** The passage as it streams in while being written: (phase, text), null otherwise. */
    var draft by mutableStateOf<Pair<String, String>?>(null)
        private set

    /** Anki cards to answer while a passage is prepared (not ones this passage uses). */
    val study = mutableStateListOf<com.cramtale.core.review.DueCard>()

    /** A flashcard answered: Good/Again for a review (logged, read-only); new cards are only triaged. */
    fun answerFlashcard(card: com.cramtale.core.review.DueCard, good: Boolean) {
        study.remove(card)
        runCatching { flashcards.answer(card, good, gradeLog) }
    }

    /** A new card triaged while waiting: known (skip it) or left for the story. */
    fun triageNew(card: com.cramtale.core.review.DueCard, known: Boolean) {
        study.remove(card)
        if (known) alreadyKnow(listOf(card.word))
    }
    private val grading = GradingEngine(twoReads = app.twoReads, pendingReads = PendingReads(File(dir, "log/two-reads.json")))

    var busy by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    val history = mutableStateListOf<ShownTurn>()
    var current by mutableStateOf<ShownTurn?>(null)
    var proposals = mutableStateListOf<MistakeLog.Key>()
    /** Deck notes tapped on any version of the current passage, minus "I know it". */
    // touched from the main thread (taps) and the work thread (commit, restore): always under tapLock
    private val tapLock = Any()
    private val tappedNotes = HashSet<Long>()
    private val tappedWords = LinkedHashMap<Long, DeckWord>()
    private var shownAt = 0L
    private var snapshot: DueSnapshot? = null
    private var pool = DuePool()
    private val meaningCache = HashMap<String, String>()

    /** What to do about a failure, then its causes (so a screenshot tells what actually went wrong). */
    private fun describe(e: Throwable): String = ErrorText.explain(e)

    /** The reply being typed: kept here so leaving the screen or a failed send doesn't lose it. */
    var replyDraft by mutableStateOf("")
    /** Repeats the action that failed ("Try again"). */
    var retry by mutableStateOf<(() -> Unit)?>(null)
        private set

    /** Story work runs here, not in a screen's scope: leaving the screen doesn't cancel a turn halfway. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = AtomicBoolean(false)
    /** True while an action runs (between the tap and its result). */
    val isWorking get() = running.get()

    fun launch(block: suspend StoryController.() -> Unit) { scope.launch { block() } }

    fun hasKey() = (fixedKey ?: keys.load()) != null
    /** Claude is set up: a key, or Claude Code in Termux (its login can only be checked by a call: [testClaudeCode]). */
    fun canCallClaude() = if (app.claudeCode && fixedKey == null) Termux.ready(context) else hasKey()

    private fun claudeCodeLlm() = ClaudeCodeLlm { request, onText -> Termux.run(context, request, onText) }

    /** A tiny Haiku call through Termux: null when it answered, else what went wrong. Blocking: call off the main thread. */
    fun testClaudeCode(): String? = runCatching {
        claudeCodeLlm().call(com.cramtale.core.llm.LlmRequest("test", com.cramtale.core.llm.Models.CHECKS, emptyList(),
            listOf(com.cramtale.core.llm.Msg(com.cramtale.core.llm.Role.USER, "Reply with the single word OK."))))
    }.fold({ r -> if (r.text.isBlank()) "Claude Code answered with nothing" else null }, { it.message ?: it.toString() })
    fun saveKey(k: String) { keys.save(k); engine = null }

    /** The saved key, shortened for display ("sk-ant-…a1b2"). */
    fun keyHint(): String? = (fixedKey ?: keys.load())?.let { k -> if (k.length > 14) k.take(7) + "…" + k.takeLast(4) else "saved" }

    /** Settings changed: the next action builds the engine with them (the waiting turn is on disk). */
    fun settingsChanged() { if (!isWorking) { engine?.shutdown(); engine = null } }

    /**
     * A new story: this one moves to story-archive/ (nothing is deleted). What's about you, not about
     * the story, stays: your mistakes, the review log and skips, the costs.
     */
    fun archiveAndReset(): File? {
        if (isWorking) return null
        val keep = USER_FILES
        val archive = File(context.filesDir, "story-archive/" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date()))
        archive.parentFile?.mkdirs()
        engine?.shutdown()
        if (!dir.renameTo(archive)) return null
        dir.mkdirs()
        for (k in keep) File(archive, k).takeIf { it.exists() }?.let { f -> File(dir, k).also { it.parentFile?.mkdirs() }.writeBytes(f.readBytes()) }
        // your taste, picked style and prompt edits are yours, not the story's; the complication table is
        // filled in per story by setup, so it starts fresh
        for (sub in listOf("prompts", "config")) File(archive, sub).listFiles { f -> f.isFile }.orEmpty()
            .filter { it.name != "complications.md" && it.name != "ambience.md" }
            .forEach { f -> File(dir, "$sub/${f.name}").also { it.parentFile?.mkdirs() }.writeBytes(f.readBytes()) }
        engine = null
        history.clear(); current = null; error = null; retry = null; proposals.clear(); replyDraft = ""; ankiNote = null
        synchronized(tapLock) { tappedNotes.clear(); tappedWords.clear() }; meaningCache.clear()
        return archive
    }

    // ------------------------------------------------------------------ stories and style

    /** One story on the phone: the one being played, or one in story-archive/. */
    data class StorySummary(val dir: File, val title: String, val place: String?, val chapter: Int, val turn: Int,
                            val lastActive: Long, val lastLines: String?, val style: String?, val current: Boolean)

    private fun summary(d: File, current: Boolean): StorySummary? {
        val world = File(d, "state/world.md").takeIf { it.exists() } ?: return null
        val sc = runCatching { com.cramtale.core.story.StoryState(d).scene() }.getOrNull()
        // stories made before titles: the world's first line stands in
        val title = File(d, "state/title.txt").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
            ?: world.readLines().map { it.trim().trimStart('#').trim() }.firstOrNull { it.isNotEmpty() }?.let { if (it.length > 48) it.take(46) + "…" else it }
            ?: "Untitled story"
        val log = File(d, "log/turns.jsonl")
        val last = log.tailJson(1).lastOrNull()
        val active = sc?.lastActive?.takeIf { it > 0 } ?: last?.optLong("at")?.takeIf { it > 0 } ?: world.lastModified()
        return StorySummary(d, title, sc?.place?.ifBlank { null }, sc?.chapter ?: 1, sc?.turn ?: 0, active,
            last?.optString("passage")?.lines()?.filter { it.isNotBlank() }?.takeLast(2)?.joinToString("\n"),
            com.cramtale.core.story.StylePresets.name(d), current)
    }

    private val archiveRoot get() = File(context.filesDir, "story-archive")

    /** Every story on the phone, the one being played first, then the rest by when they were last played. */
    fun stories(): List<StorySummary> = listOfNotNull(summary(dir, true)) +
        archiveRoot.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { summary(it, false) }.sortedByDescending { it.lastActive }

    /**
     * Picks up an archived story where it was left: the current one goes to the archive and the chosen one
     * takes its place. What's about you rather than a story (mistakes, review log, costs) stays with you.
     */
    fun switchTo(other: File): Boolean {
        if (isWorking || other.parentFile != archiveRoot || !other.isDirectory) return false
        val mine = USER_FILES.associateWith { k -> File(dir, k).takeIf { it.exists() }?.readBytes() }
        engine?.shutdown(); engine = null
        if (File(dir, "state/world.md").exists()) {
            val parked = File(archiveRoot, java.text.SimpleDateFormat("yyyy-MM-dd_HHmm-ss", java.util.Locale.US).format(java.util.Date()))
            if (!dir.renameTo(parked)) return false
        } else dir.deleteRecursively()   // an empty, never set-up story: nothing to keep
        if (!other.renameTo(dir)) return false
        for ((k, bytes) in mine) if (bytes != null) File(dir, k).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
        history.clear(); current = null; error = null; retry = null; proposals.clear(); replyDraft = ""; ankiNote = null
        synchronized(tapLock) { tappedNotes.clear(); tappedWords.clear() }; meaningCache.clear()
        return true
    }

    /** The style's short name ("Light novel"), or its description for styles picked before names were kept. */
    fun styleName(): String? = com.cramtale.core.story.StylePresets.name(dir) ?: styleLine()?.let { if (it.length > 40) it.take(38) + "…" else it }

    /** The sample passage the writer imitates now. */
    fun styleSample(): String? = File(dir, "config/style_sample.md").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /** The built-in styles with how many words in their sample you'd not know. */
    fun presets(): List<Pair<StoryEngine.StyleOption, Double>> = com.cramtale.core.story.StylePresets.ALL.map { o ->
        o to (app.matcherIfReady()?.analyze(o.passage)?.stats(app.policy, app.lists)?.unknownRate ?: 0.0)
    }

    private var engine: StoryEngine? = null

    private fun engine(): StoryEngine {
        engine?.let { return it }
        val llm = if (app.claudeCode && fixedKey == null) claudeCodeLlm()
            else llmFor(fixedKey ?: keys.load() ?: error("Add your Claude API key first"))
        val unknown = UnknownWords { text ->
            val m = app.matcher() ?: return@UnknownWords 0 to emptyList()
            val a = m.analyze(text)
            val reviewing = pool.words.map { it.word }.toSet()
            val counted = a.words().filter { s -> s.status(app.policy, app.lists).let { it != Status.IGNORED && it != Status.NAME } }
            // review words (new ones included) are the point of the passage, not unknown words
            val unknown = counted.filter { s ->
                s.status(app.policy, app.lists) in setOf(Status.UNKNOWN, Status.DECK_NEW) && s.deck.none { it.word in reviewing }
            }
            counted.size to unknown.map { it.lemma }
        }
        return StoryEngine(dir, llm, costs, session, unknown,
            settings = StoryEngine.Settings(firmStyleRules = app.firmStyleRules, length = app.passageLength.text, aimWords = app.passageLength.aim,
                corrections = app.correctionLevel)).also { e ->
            engine = e
            e.onCall = { purpose -> if (isWorking) STEP_LABELS[purpose]?.let { busy = it } }
            var last = 0L
            e.onDraft = { phase, text, final ->
                // a few updates a second are plenty for the eye; the final one always shows
                val now = System.currentTimeMillis()
                if (final || now - last > 60 || draft == null) { last = now; draft = phase to text }
            }
        }
    }

    /**
     * Runs one action off the main thread: one at a time (a double tap doesn't start a second turn), with
     * a progress line per model call, and on failure the error plus a way to try the same action again.
     */
    private suspend fun <T> work(label: String, again: (suspend () -> Unit)? = null, block: () -> T): T? {
        if (!running.compareAndSet(false, true)) return null
        // keeps the app (and its connection to Claude) alive if you switch apps meanwhile
        TurnService.start(context, label)
        return withContext(Dispatchers.IO) {
            busy = label
            error = null
            retry = null
            engine?.onCall = { purpose -> if (isWorking) busy = STEP_LABELS[purpose] ?: label }
            try {
                block()
            } catch (e: Throwable) {
                error = describe(e)
                retry = again?.let { a -> { scope.launch { a() } } }
                null
            } finally {
                busy = null
                draft = null
                running.set(false)
                TurnService.stop(context)
            }
        }
    }

    fun isSetUp() = File(dir, "state/world.md").exists()
    fun hasStyle() = File(dir, "config/style_sample.md").let { it.exists() && it.readText().isNotBlank() }

    /** The picked style's one-line description, for Settings. */
    fun styleLine(): String? = File(dir, "config/style_line.txt").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /** "Choose again": the current style is set aside (kept as .previous) so the picker shows. */
    fun clearStyle() {
        for (n in listOf("style_sample.md", "style_line.txt")) {
            val f = File(dir, "config/$n")
            if (f.exists()) { f.copyTo(File(dir, "config/$n.previous"), overwrite = true); f.writeText("") }
        }
        engine = null
    }

    /** Back out of "Choose again": the style set aside comes back. False if there was none. */
    fun restoreStyle(): Boolean {
        val prev = File(dir, "config/style_sample.md.previous")
        if (!prev.exists() || prev.readText().isBlank()) return false
        for (n in listOf("style_sample.md", "style_line.txt")) File(dir, "config/$n.previous").takeIf { it.exists() }?.copyTo(File(dir, "config/$n"), overwrite = true)
        engine = null
        return true
    }

    suspend fun setup(seed: String): Unit? = work("Creating the story…", again = { setup(seed) }) {
        runCatching { takeSnapshot() }   // flashcards for the wait
        engine().setup(seed)
    }

    /** config/plot.md as it is now (the story's copy, else the bundled default). */
    fun taste(): String = File(dir, "config/plot.md").takeIf { it.exists() }?.readText()?.trim()
        ?: com.cramtale.core.story.Prompts(null).config("plot.md")?.trim().orEmpty()

    private val styleCache = File(dir, "state/style_options.json")

    /** The four samples, kept on disk: a rebuilt screen shows the same ones instead of paying for new ones. */
    suspend fun styleOptions(fresh: Boolean = false): List<Pair<StoryEngine.StyleOption, Double>>? = work("Writing four styles…") {
        if (study.isEmpty()) runCatching { takeSnapshot() }
        val cached = if (fresh) null else runCatching {
            val a = org.json.JSONArray(styleCache.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { StoryEngine.StyleOption(it.getString("name"), it.getString("line"), it.getString("passage")) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        val options = cached ?: engine().styleOptions().also { list ->
            styleCache.parentFile?.mkdirs()
            styleCache.writeText(org.json.JSONArray(list.map { org.json.JSONObject().put("name", it.name).put("line", it.line).put("passage", it.passage) }).toString())
        }
        options.map { o ->
            val rate = app.matcher()?.analyze(o.passage)?.stats(app.policy, app.lists)?.unknownRate ?: 0.0
            o to rate
        }
    }

    /** Sets the story's style (only files: no model call). The next passage is written in it. */
    fun pickStyle(o: StoryEngine.StyleOption) {
        com.cramtale.core.story.StylePresets.write(dir, o)
        for (n in listOf("style_sample.md", "style_line.txt")) File(dir, "config/$n.previous").delete()
        settingsChanged()
    }

    // ------------------------------------------------------------------ turns

    /** Why the story has no review words right now (shown above the reply box), or null. */
    var ankiNote by mutableStateOf<String?>(null)
        private set

    /** Queue notes already looked up that aren't vocabulary notes (sentence or grammar cards): not asked again. */
    private val notVocab = File(dir, "log/not-vocab.txt")
    /** Reading new notes failed once (an AnkiDroid without nid: search?): don't retry every passage. */
    private var addNotesBroken = false

    private fun takeSnapshot() {
        val noReviews = { why: String -> snapshot = null; pool = DuePool(weakPoints = mistakes.weakPoints()); ankiNote = why }
        val t = app.target ?: return noReviews("AnkiDroid isn't connected, so this passage has no review words. Connect it from Home.")
        val reader = AnkiReader(context, t)
        if (!reader.hasPermission()) return noReviews("Cramtale has no access to AnkiDroid, so no review words. Grant it in AnkiDroid connection.")
        val result = runCatching {
            var words = app.deck()
            val deckNames = words.map { it.deck }.toSet()
            val deckIds = reader.decks().filter { it.name in deckNames }.map { it.id }
            var snap = DueSnapshot.take(reader, deckIds, words)
            // cards added in Anki since the deck was read (the miner adds them daily): read just those notes
            val skip = notVocab.takeIf { it.exists() }?.readLines()?.mapNotNull { it.toLongOrNull() }?.toSet().orEmpty()
            val missing = snap.unknownNotes - skip
            if (missing.isNotEmpty() && !addNotesBroken) {
                // never fatal: without the new notes the passage still has the known ones
                val added = runCatching { app.addNotes(missing) }.getOrElse { addNotesBroken = true; return@runCatching snap }
                (missing - added).takeIf { it.isNotEmpty() }?.let { n -> notVocab.parentFile?.mkdirs(); notVocab.appendText(n.joinToString("\n", postfix = "\n")) }
                if (added.isNotEmpty()) {
                    words = app.deck()
                    snap = DueSnapshot.take(reader, deckIds, words)
                }
            }
            snap
        }
        val snap = result.getOrElse { return noReviews("Couldn't read Anki's queue (${it.message}). This passage has no review words.") }
            .let(::withoutExcluded)
        snapshot = snap
        pool = planner.pool(snap, mistakes.weakPoints(), maxWords = app.passageLength.maxWords)
        // flashcards for the wait: what this passage won't use (reviews first, then new cards to triage);
        // the list changes on the main thread, and a card answered meanwhile isn't put back
        val inPassage = (engine?.pending?.pool ?: pool).words.map { it.noteId }.toSet()
        val cards = flashcards.pick(snap, inPassage).sortedBy { if (it.isNew) 1 else 0 }
        main.post { study.clear(); study.addAll(cards.filter { !excluded(it) }) }
        ankiNote = if (snap.cards.isEmpty()) "Nothing is due in Anki right now: this is reading practice." else null
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Left out of the story's reviews and grading: a card answered as a flashcard today, or a NEW card
     * you said you already know (once it's a review card in Anki it's woven in again).
     */
    private fun excluded(c: com.cramtale.core.review.DueCard) =
        flashcards.answeredToday(c.ref) || (c.isNew && c.ref.noteId in knownNew)

    /** The snapshot as it stands now: answers given during the wait apply to the passage being graded. */
    private fun withoutExcluded(s: DueSnapshot) = DueSnapshot(s.cards.filter { !excluded(it) }, s.takenAt, s.unknownNotes)

    /** Keys of mistakes seen so far, so the checker reuses them. */
    /** The mistake keys the checker is shown (the recent ones: a long game makes hundreds). */
    private fun knownKeys() = mistakes.promptKeys()

    /** A reply that was sent but has no passage yet: kept on disk so a crash or failed call doesn't lose it. */
    private val outbox = File(dir, "log/outbox.txt")

    /**
     * Opening the story: the passage that was waiting (restored with its taps), or a turn whose writing
     * failed (finished now), or the next passage. A new session (off-screen moves, new beats) only
     * starts after a long break, not on every open.
     */
    suspend fun start(): Unit? = work("Opening the story…", again = { start() }) {
        // the engine first: a waiting turn's review words must not be offered as flashcards
        val e = engine()
        val waiting = e.pending
        if (waiting != null && waiting.ready && outbox.takeIf { it.exists() }?.readText().isNullOrBlank()) {
            // a passage is waiting: show it at once; word taps and Anki's queue fill in right after
            loadHistory(quick = true)
            restoreTaps(waiting)
            show(waiting, quick = true)
            finishOpening(waiting)
            return@work
        }
        loadHistory()
        takeSnapshot()
        if (e.pending == null && e.needsNewSession()) e.startSession(pool)
        val message = outbox.takeIf { it.exists() }?.readText()?.ifBlank { null }
        // a reply sent while this passage was still waiting (its commit failed) goes back into the box
        if (message != null && e.pending?.ready == true && replyDraft.isBlank()) replyDraft = message
        // a waiting passage comes back as it was; a half-done turn is finished; otherwise the next one
        val t = if (e.pending != null) e.resume() else e.playerTurn(message, pool, knownKeys(), mistakes::seen)
        afterTurn(t)
        // the reply that was waiting is answered: it leaves the box
        t.player?.let { p -> if (replyDraft.trim() == p.trim()) replyDraft = "" }
    }

    /** Your reply: the current passage is graded (read-only: logged), then the next one is written. */
    /** Your message as a bubble at once, while its turn is being written. */
    var sending by mutableStateOf<String?>(null)
        private set

    /**
     * Send from the reply box: the message shows as a bubble right away and leaves the box; if the turn
     * fails, it goes back into the box (Try again sends it as before).
     */
    fun submit(message: String) {
        if (isWorking) return
        if (message.isNotBlank()) { sending = message; replyDraft = "" }
        launch {
            send(message)
            sending?.let { m -> if (error != null && replyDraft.isBlank()) replyDraft = m }
            sending = null
        }
    }

    suspend fun send(message: String): Unit? = work("Sending…", again = { send(message) }) {
        val e = engine()
        // decided (and saved) before this turn touches the clock, so a failed plan is retried, not skipped
        if (e.needsNewSession()) e.markSessionDue()
        val newSession = e.needsNewSession()
        outbox.parentFile?.mkdirs()
        outbox.writeText(message)
        if (current != null && e.pending?.ready == true) commitCurrent()
        takeSnapshot()
        if (newSession && e.pending == null) e.startSession(pool)
        val t = if (e.pending != null) e.resume() else e.playerTurn(message.takeIf { it.isNotBlank() }, pool, knownKeys(), mistakes::seen)
        afterTurn(t)
        replyDraft = ""
    }

    private fun afterTurn(t: PendingTurn) {
        // the passage arrives with your message (and its corrections): the temporary bubble goes
        sending = null
        // the outbox goes only once this turn answered it (a passage restored as it was hasn't)
        val waiting = outbox.takeIf { it.exists() }?.readText()
        if (waiting == null || waiting.isBlank() || waiting.trim() == t.player?.trim()) outbox.delete()
        // mistakes count once per turn, even after retries or a kill: marked first (at most once)
        if (!t.player.isNullOrBlank() && !t.mistakesRecorded) {
            t.mistakesRecorded = true
            engine().savePending()
            proposals += mistakes.record(t.player!!, t.corrections, t.correctKeys)
        }
        restoreTaps(t)
        show(t)
    }

    suspend fun reroll(): Unit? = work("Rewriting…", again = { reroll() }) {
        if (engine().pending?.ready != true) return@work
        engine().reroll()
        show(engine().pending!!)
    }

    suspend fun edit(text: String): Unit? = work("Checking…", again = { edit(text) }) {
        if (engine().pending?.ready != true) return@work
        engine().edit(text)
        show(engine().pending!!)
    }

    /** Switches between the versions of this passage (Reroll keeps all of them). */
    fun selectVersion(i: Int) {
        val e = engine
        if (e?.pending?.ready != true || busy != null) return
        e.selectDraft(i)
        show(e.pending!!)
    }

    private fun commitCurrent() {
        val e = engine()
        val t = e.pending ?: return
        val shown = current ?: return
        val m = app.matcher()
        // opened quickly and sent before the queue was read: read it now, so the passage is still graded
        if (snapshot == null && app.target != null) runCatching { takeSnapshot() }
        val snap = snapshot?.let(::withoutExcluded)
        // grades are decided first (Anki is asked now), but logged only once the turn is committed,
        // so a failed commit retried later doesn't log them twice
        var log: (() -> Unit)? = null
        if (m != null && snap != null && app.target != null) {
            val reader = AnkiReader(context, app.target!!)
            val inText = planner.notesInText(Analysis(shown.passage.text, shown.passage.spans), t.draft, t.pool)
            // stable across restarts and stories, so a retried commit is the same read, not a second one
            // a tap on a word answered as a flashcard today, or a known new card, doesn't count either
            val (tapped, tappedList) = synchronized(tapLock) {
                tappedNotes.filter { !flashcards.noteAnsweredToday(it) }.toSet() to
                    tappedWords.values.filter { !flashcards.noteAnsweredToday(it.noteId) && it.noteId !in knownNew }
            }
            val read = PassageRead("${storyId()}-c${e.state.scene().chapter}-t${t.turn}", inText, tapped, tappedList,
                System.currentTimeMillis() - shownAt)
            val grades = grading.decide(snap, read, reader::card)
            log = {
                // read-only build: nothing is sent; the log records what would have been
                grading.commit(grades, Mode.READ_ONLY, reader, null, gradeLog)
                planner.recordSkips(t.pool, inText)
            }
        }
        e.commit()
        log?.invoke()
        history += shown
        current = null
        synchronized(tapLock) { tappedNotes.clear(); tappedWords.clear() }
    }

    /** This story's id (made once): passage ids stay unique after a new story starts turn numbers over. */
    private fun storyId(): String {
        val f = File(dir, "state/story-id")
        if (!f.exists() || f.readText().isBlank()) { f.parentFile?.mkdirs(); f.writeText(UUID.randomUUID().toString().take(8)) }
        return f.readText().trim()
    }

    /** Taps saved with the turn come back after a restart. */
    private fun restoreTaps(t: PendingTurn) = synchronized(tapLock) {
        tappedNotes.clear()
        tappedWords.clear()
        val ids = synchronized(t.tapped) { t.tapped.toList() }
        if (ids.isEmpty()) return@synchronized
        val byNote = app.deck().associateBy { it.noteId }
        for (id in ids) { tappedNotes += id; byNote[id]?.let { tappedWords[id] = it } }
    }


    private fun saveTaps() {
        val t = engine?.pending ?: return
        val ids = synchronized(tapLock) { tappedNotes.toList() }
        synchronized(t.tapped) { t.tapped.clear(); t.tapped += ids }
        runCatching { engine?.savePending() }
    }


    /**
     * After a quick open: build the matcher (seconds, the first time), then show the passage with its words
     * tappable and the history annotated, and read Anki's queue for the flashcards and new-card meanings.
     * Skipped wherever the player has already moved on.
     */
    private fun finishOpening(t: PendingTurn) {
        scope.launch(Dispatchers.IO) {
            runCatching { app.matcher() }
            if (isWorking || engine?.pending !== t) return@launch
            show(t)
            reannotateHistory()
            if (snapshot == null) runCatching { takeSnapshot() }
            if (!isWorking && engine?.pending === t) show(t)
        }
    }

    private fun reannotateHistory() {
        val m = app.matcher() ?: return
        val fresh = history.map { h -> if (h.passage.spans.isNotEmpty()) h else
            h.copy(passage = h.passage.copy(spans = withNames(m.analyze(h.passage.text)).spans)) }
        history.clear(); history.addAll(fresh)
    }

    private fun show(t: PendingTurn, quick: Boolean = false) {
        title = place()
        val d = t.draft
        val player = t.player
        // the ones kept back (above your corrections setting) are in the mistake log, not on screen
        val corrections = t.corrections.filter { it.shown }
        val turn = t.turn
        val roll = t.roll?.toString()
        val complication = t.complication
        refreshCast()
        // quick: whatever is ready now (no waiting on the matcher build); words become tappable a moment later
        val m = if (quick) app.matcherIfReady() else app.matcher()
        val analysis = m?.analyze(d.passage)?.let(::withNames)
        val grammarFound = grammar?.find(com.cramtale.core.text.Tokenizer.tokenize(d.passage)).orEmpty()
        val newNotes = snapshot?.let(::withoutExcluded)?.cards?.filter { it.isNew }?.map { it.ref.noteId }?.toSet().orEmpty()
        val glosses = HashMap<Long, String>()
        val dict = app.jmdict()
        analysis?.spans?.forEach { s ->
            s.deck.filter { it.noteId in newNotes }.forEach { w ->
                // the card's own meaning first (it's what you'll study), else the dictionary's first sense
                glosses[w.noteId] = (w.meaning ?: s.entries.firstOrNull()?.let { dict?.senses(it)?.firstOrNull()?.gloss })
                    ?.substringBefore(";")?.trim().orEmpty()
            }
        }
        val passage = ShownPassage(turn, d.passage, analysis?.spans.orEmpty(), grammarFound, newNotes, glosses,
            d.checks.map { "${it.name}: ${if (it.passed) "ok" else it.detail}" },
            listOfNotNull(roll?.let { "Roll: $it" }, complication?.let { "Complication: $it" }, if (d.rewritten) "Rewritten once by the checks" else null),
            version = t.current, versions = t.drafts.size)
        current = ShownTurn(player, corrections, passage)
        // the scribe for this version starts now, in the background, while it's read
        runCatching { engine?.prepareCommit() }
        if (shownAt == 0L || current?.passage?.turn != turn) shownAt = System.currentTimeMillis()
    }

    private fun loadHistory(quick: Boolean = false) {
        if (history.isNotEmpty()) return
        val f = File(dir, "log/turns.jsonl")
        if (!f.exists()) return
        refreshCast()
        f.tailJson(20).forEach { o ->
            val text = o.getString("passage")
            val a = (if (quick) app.matcherIfReady() else app.matcher())?.analyze(text)?.let(::withNames)
            history += ShownTurn(o.optString("player").ifBlank { null }, emptyList(),
                ShownPassage(o.getInt("turn"), text, a?.spans.orEmpty(), emptyList(), emptySet(), emptyMap(), emptyList(), emptyList()))
        }
    }

    // ------------------------------------------------------------------ taps

    /** A tap on a word in the current passage. Deck words count as tapped (Again) unless "I know it" follows. */
    /** The story's cast, so their names show as names (tappable for the reading, never graded or carded). */
    var castNames by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The scene's place, for the title bar (kept here: reading the file on every redraw is wasteful). */
    var title by mutableStateOf<String?>(null)
        private set

    fun refreshCast() { castNames = runCatching { com.cramtale.core.story.StoryState(dir).castNameParts() }.getOrDefault(emptySet()) }

    /** The cast's names (kanji or kana) as single name spans, not their pieces (さかきともえ, not さ + かき + ともえ). */
    private fun withNames(a: Analysis) = Analysis(a.text, com.cramtale.core.match.Names.merge(a.text, a.spans, castNames))

    /** A person's or place's name: from the tokenizer (固有名詞) or one of the story's characters. */
    fun isName(span: Span) = span.ignoredBecause == "name" || span.surface in castNames || span.lemma in castNames

    fun tap(span: Span) {
        // while an action runs (the passage is being committed or rewritten) a tap can't count
        if (span.deck.isEmpty() || isName(span) || isWorking) return
        synchronized(tapLock) { span.deck.forEach { w -> tappedNotes += w.noteId; tappedWords[w.noteId] = w } }
        saveTaps()
    }

    /** A new card in the current passage (Anki says it's new now, or the deck snapshot does). */
    fun newCardsIn(span: Span): List<DeckWord> =
        span.deck.filter { it.noteId in (current?.passage?.newNotes ?: emptySet()) || it.state == com.cramtale.core.match.CardState.NEW }
            .filter { it.noteId !in knownNew }

    /**
     * "Already know it" on a new card: it leaves the story's reviews, the word counts as known, and it's
     * listed as would-be suspended (this build doesn't write to Anki).
     */
    fun alreadyKnow(words: List<DeckWord>) {
        if (words.isEmpty()) return
        val known = app.lists.known
        synchronized(tapLock) { words.forEach { w -> tappedNotes -= w.noteId; tappedWords.remove(w.noteId) } }
        // remembered per card whether this put the word on the known list, so Undo only takes off its own
        words.forEach { w -> knownNew.add(w.noteId, w.word, addedWord = w.word !in known) }
        app.lists = app.lists.copy(known = known + words.map { it.word })
        saveTaps()
        // its inline meaning goes away at once
        val ids = words.map { it.noteId }.toSet()
        current?.let { t -> current = t.copy(passage = t.passage.copy(newNotes = t.passage.newNotes - ids)) }
    }

    /** Undo from the log: the card is a new card again. */
    fun undoAlreadyKnow(noteId: Long) {
        val e = knownNew.remove(noteId) ?: return
        if (e.addedWord) app.lists = app.lists.copy(known = app.lists.known - e.word)
    }

    /** "I know it": on a deck word it cancels the tap (a mis-tap); on other words it marks the word known. */
    fun iKnowIt(span: Span) {
        if (span.deck.isNotEmpty()) { synchronized(tapLock) { span.deck.forEach { tappedNotes -= it.noteId; tappedWords.remove(it.noteId) } }; saveTaps() }
        else app.lists = app.lists.copy(known = app.lists.known + span.lemma)
    }

    fun isTapped(span: Span) = synchronized(tapLock) { span.deck.any { it.noteId in tappedNotes } }

    /** The one-line meaning in this sentence, streamed into [onPartial] as it's written (cached after). */
    suspend fun meaning(span: Span, sentence: String, onPartial: (String) -> Unit = {}): String? {
        val k = span.lemma + "|" + sentence
        meaningCache[k]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { engine().meaning(span.surface, sentence) { onPartial(it.trim()) } }.getOrNull()?.also { meaningCache[k] = it }
        }
    }

    /** Corrections you said were wrong ("Not a mistake"): hidden, and taken out of the mistake log. */
    private val dismissedFile = File(dir, "log/dismissed-corrections.txt")
    var dismissed by mutableStateOf(dismissedFile.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.toSet().orEmpty())
        private set

    fun correctionId(message: String, c: Correction) = "$message\u0000${c.wrong}\u0000${c.right}".replace("\n", " ")

    fun notAMistake(message: String, c: Correction) {
        mistakes.dismiss(message, c)
        if (mistakes.all.none { it.key == c.key && it.proposed }) proposals.removeAll { it.key == c.key }
        dismissed = dismissed + correctionId(message, c)
        dismissedFile.parentFile?.mkdirs()
        dismissedFile.appendText(correctionId(message, c) + "\n")
    }

    fun decideProposal(key: String, approved: Boolean) {
        mistakes.decide(key, approved)
        proposals.removeAll { it.key == key }
    }

    fun sessionCost() = costs.total(session)

    private fun scene() = runCatching { com.cramtale.core.story.StoryState(dir).scene() }.getOrNull()
    fun place(): String? = scene()?.place?.ifBlank { null }
    fun turn(): Int = scene()?.turn ?: 0
    fun chapter(): Int = scene()?.chapter ?: 1

    /** The end of the last passage, for the home screen. */
    fun lastLines(): String? = File(dir, "log/turns.jsonl").tailJson(1).lastOrNull()
        ?.let { runCatching { it.getString("passage") }.getOrNull() }?.lines()?.filter { it.isNotBlank() }?.takeLast(3)?.joinToString("\n")

    /**
     * Review words a passage actually carries, measured over the last 30 passages that had any (the
     * grade log against the turn log); null until there are a few.
     */
    fun cardsPerPassage(): Double? {
        val turns = File(dir, "log/turns.jsonl").tailJson(30).mapNotNull { o -> o.optLong("at").takeIf { it > 0 } }
        if (turns.size < 3) return null
        val since = turns.first() - 60_000
        val graded = gradeLog.since(since).size
        return (graded.toDouble() / turns.size).takeIf { it > 0 }
    }

    /** What the player's character knows: their role, who they've met, the chapters so far. Never secrets. */
    data class StoryInfo(
        val role: String?, val met: List<String>, val chapters: List<Pair<Int, String>>,
        /** Today's reads (Anki's day): word and would-be grade (true = Good), latest first. */
        val today: List<Pair<String, Boolean>> = emptyList(),
        /** What your character remembers: every event they perceived, from the record (newest first). */
        val remembers: List<String> = emptyList(),
        /** Dollars on the API (0 with Claude Code): this session and all time. */
        val spentSession: Double = 0.0,
        val spent: Double = 0.0,
    )

    /** Your character in short (the card above the first passage). */
    fun role(): String? = playerSheet()?.substringBefore("\n## ")?.trim()?.ifEmpty { null }

    /** Your character's whole sheet: who they are, what they know, what the story has established about them. */
    fun playerSheet(): String? = File(dir, "state/player.md").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    fun storyInfo(): StoryInfo {
        val role = playerSheet()
        val text = File(dir, "log/turns.jsonl").jsonLines().joinToString("\n") { it.optString("passage") } + "\n" + (current?.passage?.text ?: "")
        val cast = runCatching { com.cramtale.core.story.StoryState(dir).characterNames() }.getOrDefault(emptyList())
        // met = named in a passage you've read (by full name or any part of it)
        // (a whole name counts at any length, 榊 included; parts of a long name need two characters)
        val met = cast.filter { n -> n in text || n.split(' ', '　', '・', '(', ')', '（', '）').any { it.length >= 2 && it in text } }
        val chapters = File(dir, "state/chapters").listFiles { f -> f.extension == "md" }.orEmpty()
            .mapNotNull { f -> f.nameWithoutExtension.toIntOrNull()?.let { it to f.readText().trim() } }.sortedBy { it.first }
        val remembers = runCatching {
            com.cramtale.core.story.StoryState(dir).events().filter { com.cramtale.core.story.StoryState.PLAYER in it.witnesses }
                .reversed().map { e -> (if (e.turn < 0) "Before the story: " else "") + e.text }
        }.getOrDefault(emptyList())
        val start = GradeLog.ankiDayStart()
        val today = gradeLog.since(start)
            .associateBy { "${it.optLong("noteId")}/${it.optInt("ord")}" }.values
            .sortedByDescending { it.optLong("at") }.map { it.optString("word") to (it.optInt("ease") == 3) }
        return StoryInfo(role, met, chapters, today, remembers, sessionCost(), totalCost())
    }

    /** Every session's costs, from the log file. */
    fun totalCost(): Double = File(dir, "log/costs.jsonl").takeIf { it.exists() }?.readLines()
        ?.mapNotNull { runCatching { JSONObject(it).optDouble("dollars") }.getOrNull() }?.sum() ?: 0.0

    companion object {
        /** What's about you, not a story: follows you to a new or another story. */
        val USER_FILES = listOf("log/mistakes.json", "log/grades.jsonl", "log/skips.json", "log/two-reads.json", "log/costs.jsonl",
            "log/not-vocab.txt", "log/known-new.json", "log/flashcards.json")
        /** The progress line while each model call runs. */
        val STEP_LABELS = mapOf(
            "mistakes" to "Checking your Japanese…", "adjudicator" to "Deciding what's at stake…",
            "offscreen" to "The world moves while you were away…", "director" to "Planning this session…",
            "intent" to "Deciding what people do…",
            "writer" to "Writing…", "rewrite" to "Fixing the passage…", "judge" to "Checking the passage…",
            "leak" to "Checking the review words…", "scribe" to "Recording what changed…",
            "summary" to "Closing the chapter…", "setup" to "Building the world…", "style_picker" to "Writing four styles…",
        )
    }
}
