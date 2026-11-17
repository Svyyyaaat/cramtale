package com.cramtale.app.story

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cramtale.app.AppState
import com.cramtale.app.anki.AnkiReader
import com.cramtale.core.llm.AnthropicLlm
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
    private val grading = GradingEngine(twoReads = app.twoReads, pendingReads = PendingReads(File(dir, "log/two-reads.json")))

    var busy by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    val history = mutableStateListOf<ShownTurn>()
    var current by mutableStateOf<ShownTurn?>(null)
    var proposals = mutableStateListOf<MistakeLog.Key>()
    /** Deck notes tapped on any version of the current passage, minus "I know it". */
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
    fun saveKey(k: String) { keys.save(k); engine = null }

    /** The saved key, shortened for display ("sk-ant-…a1b2"). */
    fun keyHint(): String? = (fixedKey ?: keys.load())?.let { k -> if (k.length > 14) k.take(7) + "…" + k.takeLast(4) else "saved" }

    /** Settings changed: the next action builds the engine with them (the waiting turn is on disk). */
    fun settingsChanged() { if (!isWorking) engine = null }

    /**
     * A new story: this one moves to story-archive/ (nothing is deleted). What's about you, not about
     * the story, stays: your mistakes, the review log and skips, the costs.
     */
    fun archiveAndReset(): File? {
        if (isWorking) return null
        val keep = listOf("log/mistakes.json", "log/grades.jsonl", "log/skips.json", "log/two-reads.json", "log/costs.jsonl")
        val archive = File(context.filesDir, "story-archive/" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date()))
        archive.parentFile?.mkdirs()
        if (!dir.renameTo(archive)) return null
        dir.mkdirs()
        for (k in keep) File(archive, k).takeIf { it.exists() }?.let { f -> File(dir, k).also { it.parentFile?.mkdirs() }.writeBytes(f.readBytes()) }
        engine = null
        history.clear(); current = null; error = null; retry = null; proposals.clear(); replyDraft = ""
        tappedNotes.clear(); tappedWords.clear(); meaningCache.clear()
        return archive
    }

    private var engine: StoryEngine? = null

    private fun engine(): StoryEngine {
        engine?.let { return it }
        val key = fixedKey ?: keys.load() ?: error("Add your Claude API key first")
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
        return StoryEngine(dir, llmFor(key), costs, session, unknown,
            settings = StoryEngine.Settings(firmStyleRules = app.firmStyleRules, length = app.passageLength.text, aimWords = app.passageLength.aim)).also { e ->
            engine = e
            e.onCall = { purpose -> STEP_LABELS[purpose]?.let { busy = it } }
        }
    }

    /**
     * Runs one action off the main thread: one at a time (a double tap doesn't start a second turn), with
     * a progress line per model call, and on failure the error plus a way to try the same action again.
     */
    private suspend fun <T> work(label: String, again: (suspend () -> Unit)? = null, block: () -> T): T? {
        if (!running.compareAndSet(false, true)) return null
        return withContext(Dispatchers.IO) {
            busy = label
            error = null
            retry = null
            engine?.onCall = { purpose -> busy = STEP_LABELS[purpose] ?: label }
            try {
                block()
            } catch (e: Throwable) {
                error = describe(e)
                retry = again?.let { a -> { scope.launch { a() } } }
                null
            } finally {
                busy = null
                running.set(false)
            }
        }
    }

    fun isSetUp() = File(dir, "state/world.md").exists()
    fun hasStyle() = File(dir, "config/style_sample.md").let { it.exists() && it.readText().isNotBlank() }

    suspend fun setup(seed: String): Unit? = work("Creating the story…", again = { setup(seed) }) { engine().setup(seed) }

    private val styleCache = File(dir, "state/style_options.json")

    /** The four samples, kept on disk: a rebuilt screen shows the same ones instead of paying for new ones. */
    suspend fun styleOptions(fresh: Boolean = false): List<Pair<StoryEngine.StyleOption, Double>>? = work("Writing four styles…") {
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

    suspend fun pickStyle(o: StoryEngine.StyleOption) = work("Saving…") { engine().pickStyle(o) }

    // ------------------------------------------------------------------ turns

    private fun takeSnapshot() {
        val t = app.target ?: return run { snapshot = null; pool = DuePool(weakPoints = mistakes.weakPoints()) }
        val reader = AnkiReader(context, t)
        if (!reader.hasPermission()) { snapshot = null; pool = DuePool(weakPoints = mistakes.weakPoints()); return }
        val words = app.deck()
        val deckNames = words.map { it.deck }.toSet()
        val deckIds = reader.decks().filter { it.name in deckNames }.map { it.id }
        snapshot = DueSnapshot.take(reader, deckIds, words)
        pool = planner.pool(snapshot!!, mistakes.weakPoints(), maxWords = app.passageLength.maxWords)
    }

    /** Keys of mistakes seen so far, so the checker reuses them. */
    private fun knownKeys() = mistakes.all.map { it.key }

    /** A reply that was sent but has no passage yet: kept on disk so a crash or failed call doesn't lose it. */
    private val outbox = File(dir, "log/outbox.txt")

    /**
     * Opening the story: the passage that was waiting (restored with its taps), or a turn whose writing
     * failed (finished now), or the next passage. A new session (off-screen moves, new beats) only
     * starts after a long break, not on every open.
     */
    suspend fun start(): Unit? = work("Opening the story…", again = { start() }) {
        loadHistory()
        takeSnapshot()
        val e = engine()
        if (e.pending == null && e.needsNewSession()) e.startSession(pool)
        val message = outbox.takeIf { it.exists() }?.readText()?.ifBlank { null }
        // a waiting passage comes back as it was; a half-done turn is finished; otherwise the next one
        val t = if (e.pending != null) e.resume() else e.playerTurn(message, pool, knownKeys())
        afterTurn(t)
    }

    /** Your reply: the current passage is graded (read-only: logged), then the next one is written. */
    suspend fun send(message: String): Unit? = work("Sending…", again = { send(message) }) {
        val e = engine()
        // decided before this turn touches the clock
        val newSession = e.needsNewSession()
        outbox.parentFile?.mkdirs()
        outbox.writeText(message)
        if (current != null && e.pending?.ready == true) commitCurrent()
        takeSnapshot()
        if (newSession && e.pending == null) e.startSession(pool)
        val t = if (e.pending != null) e.resume() else e.playerTurn(message.takeIf { it.isNotBlank() }, pool, knownKeys())
        afterTurn(t)
        replyDraft = ""
    }

    private fun afterTurn(t: PendingTurn) {
        outbox.delete()
        // mistakes count once per turn, even if writing the passage needed a retry
        if (!t.player.isNullOrBlank() && !t.mistakesRecorded) {
            proposals += mistakes.record(t.player!!, t.corrections, engine().lastCorrectKeys)
            t.mistakesRecorded = true
            engine().savePending()
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
        val snap = snapshot
        // grades are decided first (Anki is asked now), but logged only once the turn is committed,
        // so a failed commit retried later doesn't log them twice
        var log: (() -> Unit)? = null
        if (m != null && snap != null && app.target != null) {
            val reader = AnkiReader(context, app.target!!)
            val inText = planner.notesInText(Analysis(shown.passage.text, shown.passage.spans), t.draft, t.pool)
            val read = PassageRead("t${t.turn}-$session", inText, tappedNotes.toSet(), tappedWords.values.toList(),
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
        tappedNotes.clear()
        tappedWords.clear()
    }

    /** Taps saved with the turn come back after a restart. */
    private fun restoreTaps(t: PendingTurn) {
        tappedNotes.clear()
        tappedWords.clear()
        if (t.tapped.isEmpty()) return
        val byNote = app.deck().associateBy { it.noteId }
        for (id in t.tapped) { tappedNotes += id; byNote[id]?.let { tappedWords[id] = it } }
    }

    private fun saveTaps() {
        val t = engine?.pending ?: return
        t.tapped.clear()
        t.tapped += tappedNotes
        runCatching { engine?.savePending() }
    }

    private fun show(t: PendingTurn) {
        title = place()
        val d = t.draft
        val player = t.player
        val corrections = t.corrections
        val turn = t.turn
        val roll = t.roll?.toString()
        val complication = t.complication
        refreshCast()
        val m = app.matcher()
        val analysis = m?.analyze(d.passage)
        val grammarFound = grammar?.find(com.cramtale.core.text.Tokenizer.tokenize(d.passage)).orEmpty()
        val newNotes = snapshot?.cards?.filter { it.isNew }?.map { it.ref.noteId }?.toSet().orEmpty()
        val glosses = HashMap<Long, String>()
        val dict = app.jmdict()
        analysis?.spans?.forEach { s ->
            s.deck.filter { it.noteId in newNotes }.forEach { w ->
                glosses[w.noteId] = s.entries.firstOrNull()?.let { dict?.senses(it)?.firstOrNull()?.gloss }?.substringBefore(";").orEmpty()
            }
        }
        val passage = ShownPassage(turn, d.passage, analysis?.spans.orEmpty(), grammarFound, newNotes, glosses,
            d.checks.map { "${it.name}: ${if (it.passed) "ok" else it.detail}" },
            listOfNotNull(roll?.let { "Roll: $it" }, complication?.let { "Complication: $it" }, if (d.rewritten) "Rewritten once by the checks" else null),
            version = t.current, versions = t.drafts.size)
        current = ShownTurn(player, corrections, passage)
        if (shownAt == 0L || current?.passage?.turn != turn) shownAt = System.currentTimeMillis()
    }

    private fun loadHistory() {
        if (history.isNotEmpty()) return
        val f = File(dir, "log/turns.jsonl")
        if (!f.exists()) return
        f.readLines().filter { it.isNotBlank() }.takeLast(20).map(::JSONObject).forEach { o ->
            val text = o.getString("passage")
            val a = app.matcher()?.analyze(text)
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

    /** A person's or place's name: from the tokenizer (固有名詞) or one of the story's characters. */
    fun isName(span: Span) = span.ignoredBecause == "name" || span.surface in castNames || span.lemma in castNames

    fun tap(span: Span) {
        if (span.deck.isEmpty() || isName(span)) return
        span.deck.forEach { w -> tappedNotes += w.noteId; tappedWords[w.noteId] = w }
        saveTaps()
    }

    /** "I know it": on a deck word it cancels the tap (a mis-tap); on other words it marks the word known. */
    fun iKnowIt(span: Span) {
        if (span.deck.isNotEmpty()) { span.deck.forEach { tappedNotes -= it.noteId; tappedWords.remove(it.noteId) }; saveTaps() }
        else app.lists = app.lists.copy(known = app.lists.known + span.lemma)
    }

    fun isTapped(span: Span) = span.deck.any { it.noteId in tappedNotes }

    suspend fun meaning(span: Span, sentence: String): String? {
        val k = span.lemma + "|" + sentence
        meaningCache[k]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { engine().meaning(span.surface, sentence) }.getOrNull()?.also { meaningCache[k] = it }
        }
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
    fun lastLines(): String? = File(dir, "log/turns.jsonl").takeIf { it.exists() }?.readLines()?.lastOrNull { it.isNotBlank() }
        ?.let { runCatching { JSONObject(it).getString("passage") }.getOrNull() }?.lines()?.filter { it.isNotBlank() }?.takeLast(3)?.joinToString("\n")

    /**
     * Review words a passage actually carries, measured over the last 30 passages that had any (the
     * grade log against the turn log); null until there are a few.
     */
    fun cardsPerPassage(): Double? {
        val turns = File(dir, "log/turns.jsonl").jsonLines().mapNotNull { o -> o.optLong("at").takeIf { it > 0 } }.takeLast(30)
        if (turns.size < 3) return null
        val since = turns.first() - 60_000
        val graded = gradeLog.snapshot().count { it.optLong("at") >= since }
        return (graded.toDouble() / turns.size).takeIf { it > 0 }
    }

    /** What the player's character knows: their role, who they've met, the chapters so far. Never secrets. */
    data class StoryInfo(val role: String?, val met: List<String>, val chapters: List<Pair<Int, String>>)

    fun role(): String? = File(dir, "state/player.md").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    fun storyInfo(): StoryInfo {
        val role = role()
        val text = File(dir, "log/turns.jsonl").jsonLines().joinToString("\n") { it.optString("passage") } + "\n" + (current?.passage?.text ?: "")
        val cast = runCatching { com.cramtale.core.story.StoryState(dir).characterNames() }.getOrDefault(emptyList())
        // met = named in a passage you've read (by full name or any part of it)
        // (a whole name counts at any length, 榊 included; parts of a long name need two characters)
        val met = cast.filter { n -> n in text || n.split(' ', '　', '・', '(', ')', '（', '）').any { it.length >= 2 && it in text } }
        val chapters = File(dir, "state/chapters").listFiles { f -> f.extension == "md" }.orEmpty()
            .mapNotNull { f -> f.nameWithoutExtension.toIntOrNull()?.let { it to f.readText().trim() } }.sortedBy { it.first }
        return StoryInfo(role, met, chapters)
    }

    /** Every session's costs, from the log file. */
    fun totalCost(): Double = File(dir, "log/costs.jsonl").takeIf { it.exists() }?.readLines()
        ?.mapNotNull { runCatching { JSONObject(it).optDouble("dollars") }.getOrNull() }?.sum() ?: 0.0

    companion object {
        /** The progress line while each model call runs. */
        val STEP_LABELS = mapOf(
            "mistakes" to "Checking your Japanese…", "adjudicator" to "Deciding what's at stake…",
            "offscreen" to "The world moves while you were away…", "director" to "Planning this session…",
            "writer" to "Writing…", "rewrite" to "Fixing the passage…", "judge" to "Checking the passage…",
            "leak" to "Checking the review words…", "scribe" to "Recording what changed…",
            "summary" to "Closing the chapter…", "setup" to "Building the world…", "style_picker" to "Writing four styles…",
        )
    }
}
