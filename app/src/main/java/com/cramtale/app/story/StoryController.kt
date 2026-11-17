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
    /** New cards you already know: skipped here, would be suspended in Anki once writing is on. */
    val knownNew = com.cramtale.core.review.KnownNewCards(File(dir, "log/known-new.json"))
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
        val keep = listOf("log/mistakes.json", "log/grades.jsonl", "log/skips.json", "log/two-reads.json", "log/costs.jsonl", "log/not-vocab.txt", "log/known-new.json")
        val archive = File(context.filesDir, "story-archive/" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date()))
        archive.parentFile?.mkdirs()
        if (!dir.renameTo(archive)) return null
        dir.mkdirs()
        for (k in keep) File(archive, k).takeIf { it.exists() }?.let { f -> File(dir, k).also { it.parentFile?.mkdirs() }.writeBytes(f.readBytes()) }
        // your taste, picked style and prompt edits are yours, not the story's; the complication table is
        // filled in per story by setup, so it starts fresh
        for (sub in listOf("prompts", "config")) File(archive, sub).listFiles { f -> f.isFile }.orEmpty()
            .filter { it.name != "complications.md" }
            .forEach { f -> File(dir, "$sub/${f.name}").also { it.parentFile?.mkdirs() }.writeBytes(f.readBytes()) }
        engine = null
        history.clear(); current = null; error = null; retry = null; proposals.clear(); replyDraft = ""; ankiNote = null
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
            // new cards you already know are neither offered nor graded
            .let { s -> DueSnapshot(s.cards.filter { it.ref.noteId !in knownNew }, s.takenAt, s.unknownNotes) }
        snapshot = snap
        pool = planner.pool(snap, mistakes.weakPoints(), maxWords = app.passageLength.maxWords)
        ankiNote = if (snap.cards.isEmpty()) "Nothing is due in Anki right now: this is reading practice." else null
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
        // a reply sent while this passage was still waiting (its commit failed) goes back into the box
        if (message != null && e.pending?.ready == true && replyDraft.isBlank()) replyDraft = message
        // a waiting passage comes back as it was; a half-done turn is finished; otherwise the next one
        val t = if (e.pending != null) e.resume() else e.playerTurn(message, pool, knownKeys())
        afterTurn(t)
        // the reply that was waiting is answered: it leaves the box
        t.player?.let { p -> if (replyDraft.trim() == p.trim()) replyDraft = "" }
    }

    /** Your reply: the current passage is graded (read-only: logged), then the next one is written. */
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
        val t = if (e.pending != null) e.resume() else e.playerTurn(message.takeIf { it.isNotBlank() }, pool, knownKeys())
        afterTurn(t)
        replyDraft = ""
    }

    private fun afterTurn(t: PendingTurn) {
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
        val snap = snapshot
        // grades are decided first (Anki is asked now), but logged only once the turn is committed,
        // so a failed commit retried later doesn't log them twice
        var log: (() -> Unit)? = null
        if (m != null && snap != null && app.target != null) {
            val reader = AnkiReader(context, app.target!!)
            val inText = planner.notesInText(Analysis(shown.passage.text, shown.passage.spans), t.draft, t.pool)
            // stable across restarts and stories, so a retried commit is the same read, not a second one
            val read = PassageRead("${storyId()}-c${e.state.scene().chapter}-t${t.turn}", inText, tappedNotes.toSet(), tappedWords.values.toList(),
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

    /** This story's id (made once): passage ids stay unique after a new story starts turn numbers over. */
    private fun storyId(): String {
        val f = File(dir, "state/story-id")
        if (!f.exists() || f.readText().isBlank()) { f.parentFile?.mkdirs(); f.writeText(UUID.randomUUID().toString().take(8)) }
        return f.readText().trim()
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
        synchronized(t.tapped) { t.tapped.clear(); t.tapped += tappedNotes }
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
        // while an action runs (the passage is being committed or rewritten) a tap can't count
        if (span.deck.isEmpty() || isName(span) || isWorking) return
        span.deck.forEach { w -> tappedNotes += w.noteId; tappedWords[w.noteId] = w }
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
        words.forEach { w -> knownNew.add(w.noteId, w.word); tappedNotes -= w.noteId; tappedWords.remove(w.noteId) }
        app.lists = app.lists.copy(known = app.lists.known + words.map { it.word })
        saveTaps()
        // its inline meaning goes away at once
        val ids = words.map { it.noteId }.toSet()
        current?.let { t -> current = t.copy(passage = t.passage.copy(newNotes = t.passage.newNotes - ids)) }
    }

    /** Undo from the log: the card is a new card again. */
    fun undoAlreadyKnow(noteId: Long) {
        val e = knownNew.remove(noteId) ?: return
        app.lists = app.lists.copy(known = app.lists.known - e.word)
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
    data class StoryInfo(
        val role: String?, val met: List<String>, val chapters: List<Pair<Int, String>>,
        /** Today's reads (Anki's day): word and would-be grade (true = Good), latest first. */
        val today: List<Pair<String, Boolean>> = emptyList(),
    )

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
        val start = GradeLog.ankiDayStart()
        val today = gradeLog.snapshot().filter { it.optLong("at") >= start }
            .associateBy { "${it.optLong("noteId")}/${it.optInt("ord")}" }.values
            .sortedByDescending { it.optLong("at") }.map { it.optString("word") to (it.optInt("ease") == 3) }
        return StoryInfo(role, met, chapters, today)
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
