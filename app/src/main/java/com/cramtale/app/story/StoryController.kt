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
import kotlinx.coroutines.Dispatchers
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
)

data class ShownTurn(val player: String?, val corrections: List<Correction>, val passage: ShownPassage)

/**
 * Runs the story on the phone: Anki's due cards in, a passage out, taps and grades in read-only
 * mode (PLAN.md §4.4: nothing is sent to AnkiDroid in this build).
 */
class StoryController(private val context: Context, private val app: AppState) {
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

    fun hasKey() = keys.load() != null
    fun saveKey(k: String) { keys.save(k); engine = null }

    private var engine: StoryEngine? = null

    private fun engine(): StoryEngine {
        engine?.let { return it }
        val key = keys.load() ?: error("Add your Claude API key first")
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
        return StoryEngine(dir, AnthropicLlm(key), costs, session, unknown,
            settings = StoryEngine.Settings(firmStyleRules = app.firmStyleRules)).also { engine = it }
    }

    private suspend fun <T> work(label: String, block: () -> T): T? = withContext(Dispatchers.IO) {
        busy = label
        error = null
        try { block() } catch (e: Throwable) { error = e.message ?: e.toString(); null } finally { busy = null }
    }

    fun isSetUp() = File(dir, "state/world.md").exists()
    fun hasStyle() = File(dir, "config/style_sample.md").let { it.exists() && it.readText().isNotBlank() }

    suspend fun setup(seed: String) = work("Creating the story…") { engine().setup(seed) }

    suspend fun styleOptions(): List<Pair<StoryEngine.StyleOption, Double>>? = work("Writing four styles…") {
        engine().styleOptions().map { o ->
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
        pool = planner.pool(snapshot!!, mistakes.weakPoints())
    }

    /** Session start: the world moves, the director plans, and the first passage (or the next one) is written. */
    suspend fun start() = work("Starting the session…") {
        loadHistory()
        takeSnapshot()
        engine().startSession(pool)
        val t = engine().playerTurn(null, pool)
        show(t.player, t.corrections, t.draft, t.turn, t.roll?.toString(), t.complication)
    }

    /** Your reply: the current passage is graded (read-only: logged), then the next one is written. */
    suspend fun send(message: String) = work("Writing…") {
        commitCurrent()
        takeSnapshot()
        val keysKnown = mistakes.all.map { it.key }
        val t = engine().playerTurn(message.takeIf { it.isNotBlank() }, pool, keysKnown)
        if (!message.isBlank()) {
            proposals += mistakes.record(message, t.corrections, engine().lastCorrectKeys)
        }
        show(t.player, t.corrections, t.draft, t.turn, t.roll?.toString(), t.complication)
    }

    suspend fun reroll() = work("Rewriting…") {
        val t = engine().pending ?: return@work
        show(t.player, t.corrections, engine().reroll(), t.turn, t.roll?.toString(), t.complication, keepTaps = true)
    }

    suspend fun edit(text: String) = work("Checking…") {
        val t = engine().pending ?: return@work
        show(t.player, t.corrections, engine().edit(text), t.turn, t.roll?.toString(), t.complication, keepTaps = true)
    }

    private fun commitCurrent() {
        val e = engine()
        val t = e.pending ?: return
        val shown = current ?: return
        val m = app.matcher()
        val snap = snapshot
        if (m != null && snap != null) {
            val reader = AnkiReader(context, app.target!!)
            val inText = planner.notesInText(Analysis(shown.passage.text, shown.passage.spans), t.draft, t.pool)
            val read = PassageRead("t${t.turn}-$session", inText, tappedNotes.toSet(), tappedWords.values.toList(),
                System.currentTimeMillis() - shownAt)
            val grades = grading.decide(snap, read, reader::card)
            // read-only build: nothing is sent; the log records what would have been
            grading.commit(grades, Mode.READ_ONLY, reader, null, gradeLog)
            planner.recordSkips(t.pool, inText)
        }
        e.commit()
        history += shown
        current = null
        tappedNotes.clear()
        tappedWords.clear()
    }

    private fun show(player: String?, corrections: List<Correction>, d: Draft, turn: Int, roll: String?, complication: String?, keepTaps: Boolean = false) {
        if (!keepTaps) { tappedNotes.clear(); tappedWords.clear() }
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
            listOfNotNull(roll?.let { "Roll: $it" }, complication?.let { "Complication: $it" }, if (d.rewritten) "Rewritten once by the checks" else null))
        current = ShownTurn(player, corrections, passage)
        shownAt = System.currentTimeMillis()
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
    fun tap(span: Span) {
        if (span.deck.isEmpty()) return
        span.deck.forEach { w -> tappedNotes += w.noteId; tappedWords[w.noteId] = w }
    }

    /** "I know it": on a deck word it cancels the tap (a mis-tap); on other words it marks the word known. */
    fun iKnowIt(span: Span) {
        if (span.deck.isNotEmpty()) span.deck.forEach { tappedNotes -= it.noteId; tappedWords.remove(it.noteId) }
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

    /** Every session's costs, from the log file. */
    fun totalCost(): Double = File(dir, "log/costs.jsonl").takeIf { it.exists() }?.readLines()
        ?.mapNotNull { runCatching { JSONObject(it).optDouble("dollars") }.getOrNull() }?.sum() ?: 0.0
}
