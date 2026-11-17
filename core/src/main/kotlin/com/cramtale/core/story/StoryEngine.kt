package com.cramtale.core.story

import com.cramtale.core.jsonLines
import com.cramtale.core.writeAtomic
import com.cramtale.core.llm.CostLog
import com.cramtale.core.llm.Llm
import com.cramtale.core.llm.LlmRequest
import com.cramtale.core.llm.Models
import com.cramtale.core.llm.Msg
import com.cramtale.core.llm.Role
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A definite mistake in the player's Japanese (prompts/mistakes.md). */
data class Correction(val wrong: String, val right: String, val type: String, val reason: String, val key: String)

/** Review material for the writer, already in priority order. */
data class DueWord(val word: String, val noteId: Long, val isNew: Boolean)
data class DueGrammar(val id: String, val meaning: String, val noteId: Long)
data class DuePool(val words: List<DueWord> = emptyList(), val grammar: List<DueGrammar> = emptyList(), val weakPoints: List<String> = emptyList())

data class CheckResult(val name: String, val passed: Boolean, val detail: String)

/** One version of a passage, with what the checks found. */
data class Draft(
    val passage: String,
    /** (word as listed, form in the passage) for review words the writer says it used and the text confirms. */
    val used: List<Pair<String, String>>,
    val grammar: List<String>,
    val checks: List<CheckResult>,
    val rewritten: Boolean,
)

class PendingTurn(
    val turn: Int,
    val player: String?,
    val corrections: List<Correction>,
    val roll: Dice.Roll?,
    /** What the roll means for the writer (the attempt, the instruction, what failure costs). */
    val rollNote: String?,
    val complication: String?,
    val pool: DuePool,
    val drafts: MutableList<Draft> = ArrayList(),
) {
    var current = 0
    /** The facts sent with this turn; rerolls reuse them, and history keeps them. */
    var packet: String? = null
    /** Deck notes the reader tapped on any version (the app keeps this; saved with the turn). Lock it to iterate. */
    val tapped: MutableSet<Long> = java.util.Collections.synchronizedSet(LinkedHashSet())
    /** The player's mistakes from this turn are in the mistake log (once, even if writing was retried). */
    var mistakesRecorded = false
    /** Known mistake keys the player's message got right (for "fixed" tracking), kept with the turn. */
    var correctKeys: List<String> = emptyList()
    val draft get() = drafts[current]
    /** Written yet? A turn whose writer call failed has no drafts and resumes from here. */
    val ready get() = drafts.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("turn", turn).put("player", player ?: JSONObject.NULL).put("rollNote", rollNote ?: JSONObject.NULL)
        .put("complication", complication ?: JSONObject.NULL).put("packet", packet ?: JSONObject.NULL).put("current", current)
        .put("corrections", JSONArray(corrections.map { JSONObject().put("wrong", it.wrong).put("right", it.right)
            .put("type", it.type).put("reason", it.reason).put("key", it.key) }))
        .put("roll", roll?.let { r -> JSONObject().put("dice", JSONArray(r.dice)).put("modifier", r.modifier).put("outcome", r.outcome.name) }
            ?: JSONObject.NULL)
        .put("pool", JSONObject()
            .put("words", JSONArray(pool.words.map { JSONObject().put("word", it.word).put("noteId", it.noteId).put("isNew", it.isNew) }))
            .put("grammar", JSONArray(pool.grammar.map { JSONObject().put("id", it.id).put("meaning", it.meaning).put("noteId", it.noteId) }))
            .put("weakPoints", JSONArray(pool.weakPoints)))
        .put("drafts", JSONArray(drafts.map { d -> JSONObject().put("passage", d.passage)
            .put("used", JSONArray(d.used.map { JSONObject().put("word", it.first).put("form", it.second) }))
            .put("grammar", JSONArray(d.grammar)).put("rewritten", d.rewritten)
            .put("checks", JSONArray(d.checks.map { JSONObject().put("name", it.name).put("passed", it.passed).put("detail", it.detail) })) }))
        .put("tapped", JSONArray(synchronized(tapped) { tapped.toList() })).put("mistakesRecorded", mistakesRecorded)
        .put("correctKeys", JSONArray(correctKeys))

    companion object {
        private fun JSONArray?.objects() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
        private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k)

        fun fromJson(o: JSONObject): PendingTurn {
            val p = o.getJSONObject("pool")
            val t = PendingTurn(
                turn = o.getInt("turn"), player = o.str("player"),
                corrections = o.optJSONArray("corrections").objects().map {
                    Correction(it.getString("wrong"), it.getString("right"), it.optString("type"), it.optString("reason"), it.getString("key"))
                },
                roll = o.optJSONObject("roll")?.let { r ->
                    val d = r.getJSONArray("dice")
                    Dice.Roll((0 until d.length()).map { d.getInt(it) }, r.getInt("modifier"), Dice.Outcome.valueOf(r.getString("outcome")))
                },
                rollNote = o.str("rollNote"), complication = o.str("complication"),
                pool = DuePool(
                    p.optJSONArray("words").objects().map { DueWord(it.getString("word"), it.getLong("noteId"), it.getBoolean("isNew")) },
                    p.optJSONArray("grammar").objects().map { DueGrammar(it.getString("id"), it.getString("meaning"), it.getLong("noteId")) },
                    p.optJSONArray("weakPoints")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                ),
            )
            o.optJSONArray("drafts").objects().forEach { d ->
                t.drafts += Draft(d.getString("passage"),
                    d.optJSONArray("used").objects().map { it.getString("word") to it.getString("form") },
                    d.optJSONArray("grammar")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    d.optJSONArray("checks").objects().map { CheckResult(it.getString("name"), it.getBoolean("passed"), it.optString("detail")) },
                    d.optBoolean("rewritten"))
            }
            t.current = o.optInt("current").coerceIn(0, (t.drafts.size - 1).coerceAtLeast(0))
            t.packet = o.str("packet")
            t.mistakesRecorded = o.optBoolean("mistakesRecorded")
            t.correctKeys = o.optJSONArray("correctKeys")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            o.optJSONArray("tapped")?.let { a -> (0 until a.length()).forEach { t.tapped += a.getLong(it) } }
            return t
        }
    }
}

/**
 * How long passages are, and so how many review words each one can carry: longer passages cover a big
 * review pile in fewer (cheaper) turns, shorter ones give more back-and-forth.
 */
enum class PassageLength(
    val label: String, val text: String, val maxWords: Int,
    /** review words a passage usually carries (until measured) */ val estimate: Double,
    /** how many review words the writer is asked to aim for */ val aim: Int,
) {
    SHORT("Short", "about 100–200 Japanese characters; a quiet beat can be a few lines", 8, 3.5, 4),
    NORMAL("Normal", "about 150–350 Japanese characters; a quiet beat can be much shorter", 15, 6.0, 7),
    LONG("Long", "about 300–550 Japanese characters, still ending at the player's decision", 25, 10.0, 12),
}

/** Measures a passage against what the reader knows (backed by the matcher in the app). */
fun interface UnknownWords {
    /** Counted words, and the lemma of every unknown occurrence. */
    fun check(text: String): Pair<Int, List<String>>
}

/**
 * The game master loop (PLAN.md §6). Code owns state and decides outcomes; each model call does one
 * job: the director plans beats, the writer writes, Haiku calls check, judge and keep records.
 */
class StoryEngine(
    val dir: File,
    private val llm: Llm,
    private val costs: CostLog,
    private val session: String,
    private val unknownWords: UnknownWords? = null,
    private val dice: Dice = Dice(),
    val settings: Settings = Settings(),
) {
    data class Settings(
        val unknownLimit: Double = 0.03,
        val length: String = "about 150–350 Japanese characters; a quiet beat can be much shorter",
        val firmStyleRules: Boolean = false,
        val chapterTurns: Int = 30,
        /** Away longer than this, and the next turn starts a new session (off-screen moves, new beats). */
        val sessionGapMs: Long = 3 * 60 * 60 * 1000L,
        /** Review words to aim for in a passage (fewer is fine when the scene doesn't take them). */
        val aimWords: Int = 7,
    )

    val prompts = Prompts(dir)
    val state = StoryState(dir)
    private val logFile = File(dir, "log/turns.jsonl")
    private val chaptersDir = File(dir, "state/chapters")
    private val pendingFile = File(dir, "state/pending.json")

    /** The turn on screen, kept on disk: a restart shows the same passage, a failed write resumes it. */
    var pending: PendingTurn? = null
        private set

    /** Told which job is running ("writer", "judge"…), for the progress line. */
    var onCall: (String) -> Unit = {}

    init {
        prompts.installDefaults(Prompts.PROMPTS + Prompts.CONFIG)
        pending = runCatching { if (pendingFile.exists()) PendingTurn.fromJson(JSONObject(pendingFile.readText())) else null }.getOrNull()
    }

    @Synchronized
    fun savePending() {
        val t = pending
        if (t == null) pendingFile.delete() else pendingFile.writeAtomic(t.toJson().toString())
    }

    /** No session yet, or the player has been away long enough that the world should move. */
    fun needsNewSession(now: Long = System.currentTimeMillis()): Boolean {
        val s = state.scene()
        return s.session == 0 || s.beats.isEmpty() || s.sessionDue || now - s.lastActive > settings.sessionGapMs
    }

    /**
     * Records that the next turn starts a new session, before anything touches the clock: if the plan
     * then fails, Try again still makes it (instead of carrying on with the old beats).
     */
    fun markSessionDue() {
        val s = state.scene()
        if (!s.sessionDue) { s.sessionDue = true; state.save(s) }
    }

    private fun touch() {
        val s = state.scene()
        s.lastActive = System.currentTimeMillis()
        state.save(s)
    }

    // ------------------------------------------------------------------ calls

    private fun call(purpose: String, model: String, system: String, messages: List<Msg>, schema: String? = null,
                     maxTokens: Int = 4096, cacheSystem: Boolean = false, extraSystem: List<Msg> = emptyList()): String {
        // one-shot jobs (everything but the writer): the instructions and material go in the user
        // message, ending with the request, which is where Claude reads a request best
        val oneShot = purpose != "writer" && purpose != "rewrite"
        val req = LlmRequest(
            purpose = purpose, model = model,
            system = if (oneShot) listOf(Msg(Role.SYSTEM, ONE_SHOT_SYSTEM))
                else listOf(Msg(Role.SYSTEM, system, cache = cacheSystem)) + extraSystem,
            messages = if (oneShot) listOf(Msg(Role.USER, system.trimEnd() + "\n\n" + messages.joinToString("\n") { it.text })) else messages, maxTokens = maxTokens, effort = "low", jsonSchema = schema,
            thinking = model != Models.CHECKS,
        )
        onCall(purpose)
        var resp = llm.call(req)
        costs.record(session, req, resp)
        if (resp.stopReason == "max_tokens") {
            // thinking counts against max_tokens and can't be turned off on Opus 5.5: one retry with room to spare
            val bigger = req.copy(maxTokens = req.maxTokens * 2)
            resp = llm.call(bigger)
            costs.record(session, bigger, resp)
        }
        // every reply, for tuning prompts (log/calls.jsonl)
        File(dir, "log").mkdirs()
        File(dir, "log/calls.jsonl").appendText(JSONObject().put("purpose", purpose).put("model", resp.model)
            .put("out", resp.usage.output).put("text", resp.text).toString() + "\n")
        return resp.text
    }

    private fun json(text: String) = JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())

    /**
     * Structured output guarantees the JSON's shape, not its content: at low effort a model can
     * return every field empty. Such replies are retried (twice), then the call fails loudly.
     */
    private fun validJson(purpose: String, valid: (JSONObject) -> Boolean, attempt: () -> String): JSONObject {
        var last: String? = null
        repeat(3) {
            val text = attempt()
            last = text
            val o = runCatching { json(text) }.getOrNull()
            if (o != null && valid(o)) return o
        }
        throw IllegalStateException("$purpose: no usable reply after 3 tries (last: ${last?.take(200)})")
    }

    // ------------------------------------------------------------------ setup and style

    fun isSetUp() = File(dir, "state/world.md").exists()

    /** Creates the world, cast, secrets, clocks and opening situation from the plot taste. */
    fun setup(seed: String = "") {
        val o = validJson("setup", { o ->
            o.optString("world").isNotBlank() && (o.optJSONArray("characters")?.length() ?: 0) >= 2 &&
                o.optJSONObject("opening")?.optString("place")?.isNotBlank() == true
        }) {
            call("setup", Models.WRITER, Prompts.fill(prompts.prompt("setup"), mapOf(
                "plot" to prompts.config("plot.md"), "seed" to if (seed.isBlank()) "" else "Seed from the player: $seed",
            )), listOf(Msg(Role.USER, "Create the game now, with every field filled in.")), schema = SETUP_SCHEMA, maxTokens = 16000)
        }
        val stateDir = File(dir, "state").apply { mkdirs() }
        val clocks = o.optJSONArray("clocks")
        File(stateDir, "world.md").writeAtomic(o.getString("world").trimEnd() + "\n\n## Clocks\n" +
            (0 until (clocks?.length() ?: 0)).joinToString("\n") { i ->
                val c = clocks!!.getJSONObject(i)
                "- ${c.getString("name")}: 0/${c.optInt("size", 6)} — when full: ${c.optString("when_full")}"
            } + "\n")
        File(stateDir, "player.md").writeAtomic(o.optString("player") + "\n")
        val chars = o.getJSONArray("characters")
        for (i in 0 until chars.length()) chars.getJSONObject(i).let { state.writeCharacter(it.getString("name"), it.getString("file")) }
        val secrets = o.optJSONArray("secrets")
        File(stateDir, "secrets.md").writeAtomic("# Secrets (game master only)\n\n" + (0 until (secrets?.length() ?: 0)).joinToString("\n") { i ->
            val s = secrets!!.getJSONObject(i)
            "- S${i + 1}: ${s.getString("text")} [knows: ${(0 until s.getJSONArray("knows").length()).joinToString(", ") { s.getJSONArray("knows").getString(it) }}]"
        } + "\n")
        o.optJSONArray("complications")?.let { a ->
            val f = File(dir, "config/complications.md")
            f.appendText((0 until a.length()).joinToString("") { "- ${a.getString(it).removePrefix("- ")}\n" })
        }
        val opening = o.getJSONObject("opening")
        state.save(state.scene().apply {
            place = opening.getString("place")
            present = (0 until opening.getJSONArray("present").length()).map { opening.getJSONArray("present").getString(it) }.toMutableList()
            beats = mutableListOf(StoryState.Beat(opening.getString("situation"), place, present.toList(), ""))
        })
    }

    data class StyleOption(val name: String, val line: String, val passage: String)

    /** Four versions of one scene in clearly different styles (first run). */
    fun styleOptions(scene: String = "A tenant comes to ask the landlord for more time to pay the rent. The landlord has his own debts."): List<StyleOption> {
        val a = validJson("style_picker", { o ->
            val s = o.optJSONArray("styles")
            s != null && s.length() >= 4 && (0 until s.length()).all { s.getJSONObject(it).optString("passage").length > 50 }
        }) {
            call("style_picker", Models.WRITER, Prompts.fill(prompts.prompt("style_picker"), mapOf("scene" to scene)),
                listOf(Msg(Role.USER, "Write the four versions now.")), schema = STYLES_SCHEMA, maxTokens = 12000)
        }.getJSONArray("styles")
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { StyleOption(it.getString("name"), it.getString("line"), it.getString("passage")) } }
    }

    fun pickStyle(o: StyleOption) {
        File(dir, "config").mkdirs()
        File(dir, "config/style_sample.md").writeAtomic(o.passage.trim() + "\n")
        File(dir, "config/style_line.txt").writeAtomic(o.line.trim() + "\n")
    }

    fun hasStyle() = !prompts.config("style_sample.md").isNullOrBlank()

    // ------------------------------------------------------------------ sessions

    /** Between sessions the world moves (off-screen), then the director plans three beats. */
    fun startSession(pool: DuePool): List<String> {
        val scene = state.scene()
        val log = ArrayList<String>()
        if (scene.session > 0 && scene.turn > 0 && scene.movedForSession != scene.session + 1) {
            val text = call("offscreen", Models.WRITER, Prompts.fill(prompts.prompt("offscreen"), records()),
                listOf(Msg(Role.USER, "What happened off-screen?")), schema = StateOp.SCHEMA, maxTokens = 12000)
            log += state.apply(StateOp.parse(text), offscreen = true)
            state.save(state.scene().also { it.movedForSession = scene.session + 1 })
        }
        val s = state.scene()
        val weak = (pool.weakPoints + pool.grammar.map { "${it.id} (${it.meaning})" }).distinct()
        val openingRule = if (weak.isEmpty()) "" else
            "The player is practising these points: ${weak.joinToString("; ")}. You may plan ONE beat where one of them " +
                "would come up naturally in what the player's character has to say or do, only if it fits a beat you would " +
                "plan anyway. Never make it a quiz or a prompt to use it. Usually plan none."
        val o = validJson("director", { o ->
            val b = o.optJSONArray("beats")
            b != null && b.length() >= 1 && (0 until b.length()).all { b.getJSONObject(it).optString("goal").isNotBlank() }
        }) {
            call("director", Models.WRITER, Prompts.fill(prompts.prompt("director"), records() + mapOf(
            "opening_rule" to openingRule,
            "situation" to "Place: ${s.place}. Present: ${s.present.joinToString()}. Turn ${s.turn}. " +
                "Unfinished beats: ${s.beats.filter { !it.done }.joinToString("; ") { it.goal }.ifEmpty { "none" }}",
        )), listOf(Msg(Role.USER, "Plan the three beats now.")), schema = DIRECTOR_SCHEMA, maxTokens = 16000)
        }
        val beats = o.getJSONArray("beats")
        s.beats = (0 until beats.length()).map { i ->
            val b = beats.getJSONObject(i)
            StoryState.Beat(b.getString("goal"), b.optString("place"),
                (0 until (b.optJSONArray("characters")?.length() ?: 0)).map { b.getJSONArray("characters").getString(it) }, b.optString("pressure"))
        }.toMutableList()
        s.session += 1
        s.movedForSession = s.session
        s.sessionDue = false
        s.lastActive = System.currentTimeMillis()
        s.openingsThisSession = if (o.optString("opening").isNotBlank()) 1 else 0
        s.complication = null
        s.complicationFor = null
        state.save(s)
        log += "beats: " + s.beats.joinToString(" | ") { it.goal }
        return log
    }

    // ------------------------------------------------------------------ a turn

    /**
     * The player's message (null to start or just continue): mistakes and the referee first, then
     * code rolls and draws the scene's complication, then the writer, then the checks.
     */
    fun playerTurn(message: String?, pool: DuePool, knownMistakeKeys: List<String> = emptyList()): PendingTurn {
        pending?.let { p ->
            check(!p.ready) { "a passage is already waiting for its reply" }
            return resume()
        }
        maybeNewChapter()
        val scene = state.scene()
        val corrections = if (message.isNullOrBlank()) emptyList() else checkMistakes(message, knownMistakeKeys)
        val correctKeys = lastCorrectKeys
        var roll: Dice.Roll? = null
        var rollNote: String? = null
        if (!message.isNullOrBlank()) {
            val a = json(call("adjudicator", Models.CHECKS, Prompts.fill(prompts.prompt("adjudicator"), mapOf(
                "scene" to sceneSummary(scene), "message" to message,
            )), listOf(Msg(Role.USER, "Decide.")), schema = ADJUDICATOR_SCHEMA, maxTokens = 400))
            if (a.optBoolean("roll")) {
                roll = dice.roll(a.optInt("difficulty").coerceIn(-2, 2))
                // a failure is written down before the writer sees it: it can't be narrated away
                if (roll.outcome == Dice.Outcome.FAILURE && a.optString("stakes").isNotBlank()) {
                    state.apply(listOf(StateOp.AddConsequence("Failed: ${a.optString("action")}. ${a.optString("stakes")}", null)))
                }
                rollNote = "The player's character attempts: ${a.optString("action")}. ${roll.outcome.instruction} " +
                    "(If it failed: ${a.optString("stakes")})"
            }
        }
        // one complication per scene: a new beat draws a new one
        val s = state.scene()
        val beat = s.beats.firstOrNull { !it.done }
        if (s.complicationFor != beat?.goal) {
            val c = Complications.parse(prompts.config("complications.md").orEmpty()).draw(s.place, s.usedComplications)
            s.complication = c
            s.complicationFor = beat?.goal
            if (c != null) s.usedComplications += c
            state.save(s)
        }
        val turn = PendingTurn(s.turn, message, corrections, roll, rollNote, s.complication, pool)
        turn.correctKeys = correctKeys
        // saved before the writer runs: if that call fails, the roll and its consequence are already
        // decided and a retry resumes this turn instead of rolling again
        pending = turn
        savePending()
        touch()
        return resume()
    }

    /** Writes the pending turn's passage if it has none yet (after a failed or interrupted call). */
    fun resume(): PendingTurn {
        val t = pending ?: error("no pending turn")
        if (!t.ready) {
            t.drafts += write(t)
            t.current = 0
            savePending()
        }
        return t
    }

    /** Shows an earlier version again (reroll keeps every version). */
    fun selectDraft(i: Int): Draft {
        val t = pending ?: error("no pending turn")
        t.current = i.coerceIn(0, t.drafts.size - 1)
        savePending()
        return t.draft
    }

    /** A fresh version of the pending passage (taps on earlier versions still count). */
    fun reroll(): Draft {
        val t = pending ?: error("no pending turn")
        val d = write(t)
        t.drafts += d
        t.current = t.drafts.size - 1
        savePending()
        return d
    }

    /** The player edited the passage text: recheck it without a model call. */
    fun edit(text: String): Draft {
        val t = pending ?: error("no pending turn")
        val d = Draft(text, t.draft.used.filter { it.second in text }, t.draft.grammar, cheapChecks(text), t.draft.rewritten)
        t.drafts += d
        t.current = t.drafts.size - 1
        savePending()
        return d
    }

    /** The passage is final: the scribe records what changed, the turn is logged, chapters roll over. */
    fun commit(): List<String> {
        val t = pending ?: error("no pending turn")
        check(t.ready) { "the passage hasn't been written yet" }
        // killed after this turn was logged but before it was cleared: finish clearing, don't apply it again
        if (turnsInChapter(state.scene().chapter).any { it.optInt("turn") == t.turn }) {
            val s = state.scene()
            if (s.turn <= t.turn) { s.turn = t.turn + 1; state.save(s) }
            pending = null
            savePending()
            return listOf("already recorded")
        }
        val s = state.scene()
        val ops = StateOp.parse(call("scribe", Models.CHECKS, Prompts.fill(prompts.prompt("scribe"), records() + mapOf(
            "present" to s.present.joinToString(), "place" to s.place,
            "beat" to (s.beats.firstOrNull { !it.done }?.goal ?: "none"),
            "clocks" to state.clocks().joinToString("; ") { "${it.name} ${it.filled}/${it.size}" },
            "message" to (t.player ?: "(the player let the scene continue)"), "passage" to t.draft.passage,
        )), listOf(Msg(Role.USER, "List the changes.")), schema = StateOp.SCHEMA, maxTokens = 1500))
        val applied = state.apply(ops)
        unknownWords?.check(t.draft.passage)?.second?.let { words ->
            seenFile.parentFile.mkdirs()
            seenFile.writeAtomic((seenWords() + words).sorted().joinToString("\n") + "\n")
        }
        logFile.parentFile.mkdirs()
        logFile.appendText(JSONObject()
            .put("turn", t.turn).put("at", System.currentTimeMillis()).put("chapter", s.chapter).put("player", t.player ?: "")
            .put("passage", t.draft.passage).put("roll", t.roll?.toString() ?: "")
            .put("packet", t.packet ?: "").put("sentAs", sentAs(t, turnsInChapter(s.chapter).isEmpty()))
            .put("corrections", JSONArray(t.corrections.map { JSONObject().put("wrong", it.wrong).put("right", it.right).put("key", it.key) }))
            .put("checks", JSONArray(t.draft.checks.map { "${it.name}: ${if (it.passed) "ok" else it.detail}" }))
            .put("versions", t.drafts.size).put("ops", JSONArray(applied))
            .toString() + "\n")
        val s2 = state.scene()
        s2.turn += 1
        s2.lastActive = System.currentTimeMillis()
        state.save(s2)
        pending = null
        savePending()
        // a failed summary call is tried again before the next turn (maybeNewChapter in playerTurn)
        return applied + listOfNotNull(runCatching { maybeNewChapter() }.getOrNull())
    }

    /** Closes the chapter once it has enough turns. */
    fun maybeNewChapter(): String? =
        if (turnsInChapter(state.scene().chapter).size >= settings.chapterTurns) newChapter() else null

    /** A summary closes the chapter; the next one starts from it, keeping the writer's context short. */
    fun newChapter(): String {
        val s = state.scene()
        val text = turnsInChapter(s.chapter).joinToString("\n\n") { o ->
            (o.optString("player").takeIf { it.isNotBlank() }?.let { "Player: $it\n" } ?: "") + o.getString("passage")
        }
        val summary = call("summary", Models.WRITER, Prompts.fill(prompts.prompt("summary"), mapOf("chapter" to text)),
            listOf(Msg(Role.USER, "Summarize.")), maxTokens = 6000)
        chaptersDir.mkdirs()
        File(chaptersDir, "${s.chapter}.md").writeAtomic(summary.trim() + "\n")
        s.chapter += 1
        state.save(s)
        return "chapter ${s.chapter} begins"
    }

    // ------------------------------------------------------------------ the writer and its checks

    private fun turnsInChapter(chapter: Int): List<JSONObject> =
        logFile.jsonLines().filter { it.optInt("chapter") == chapter }

    private fun writerSystem(): List<Msg> {
        val fixed = Prompts.fill(prompts.prompt("writer"), mapOf(
            // the fixed (cached) prompt must not change from turn to turn: no clocks here, they're in the packet
            "plot" to prompts.config("plot.md"), "world" to state.world().substringBefore("## Clocks").trim() + "\n\n## The player's character\n" + playerText(),
            "style_sample" to prompts.config("style_sample.md"),
        ))
        val prev = File(chaptersDir, "${state.scene().chapter - 1}.md")
        val summary = if (prev.exists()) listOf(Msg(Role.SYSTEM, "## The story so far (previous chapter)\n" + prev.readText(), cache = true)) else emptyList()
        return listOf(Msg(Role.SYSTEM, fixed, cache = true)) + summary
    }

    private fun writerMessages(t: PendingTurn, packet: String): List<Msg> {
        // append-only: each earlier turn is exactly what was sent then (message, facts, passage), so
        // the next call reads all of it from the cache
        val history = turnsInChapter(state.scene().chapter).flatMap { o ->
            listOfNotNull(Msg(Role.USER, o.optString("player").ifBlank { o.optString("sentAs").ifBlank { "（続けて）" } }),
                o.optString("packet").takeIf { it.isNotBlank() }?.let { Msg(Role.SYSTEM, it) },
                Msg(Role.ASSISTANT, o.getString("passage")))
        }
        val now = sentAs(t, history.isEmpty())
        return history + Msg(Role.USER, now) + Msg(Role.SYSTEM, packet)
    }

    private fun sentAs(t: PendingTurn, first: Boolean) = t.player?.takeIf { it.isNotBlank() } ?: if (first) "（物語の始まり）" else "（続けて）"

    private fun turnPacket(t: PendingTurn): String {
        val s = state.scene()
        val beat = s.beats.firstOrNull { !it.done }
        val names = s.present.toSet()
        val characters = s.present.mapNotNull { n -> state.character(n)?.let { "#### $n\n$it" } } +
            (state.characterNames() - names).map { n -> "- $n (not here)" }
        val tags = names + s.place
        return Prompts.fill(prompts.prompt("turn"), mapOf(
            "player" to playerText(),
            "place" to s.place, "present" to s.present.joinToString().ifEmpty { "nobody but the player" },
            "turn" to s.turn.toString(), "chapter" to s.chapter.toString(),
            "beat" to (beat?.let { "${it.goal} Pressure: ${it.pressure}" } ?: "Let the scene breathe; follow what the player does."),
            "roll" to (t.roll?.let { "$it. ${t.rollNote ?: it.outcome.instruction}" } ?: "No roll this turn."),
            "complication" to (t.complication ?: "none"),
            "characters" to characters.joinToString("\n\n"),
            "secrets" to state.secrets().filter { sec -> sec.knows.any { it in names } || sec.knows.isEmpty() }
                .joinToString("\n") { "- ${it.id}: ${it.text} (known to ${it.knows.joinToString()})" }.ifEmpty { "none" },
            "consequences" to state.consequences().filter { it.open }.joinToString("\n") { c ->
                "- ${c.id}: ${c.text}" + (c.dueTurn?.let { d -> if (d <= s.turn) " (DUE NOW)" else " (due turn $d)" } ?: "")
            }.ifEmpty { "nothing" },
            "clocks" to state.clocks().joinToString("\n") { "- ${it.name}: ${it.filled}/${it.size} (when full: ${it.whenFull})" }.ifEmpty { "none" },
            "chronicle" to state.chronicle().filter { e -> e.tags.any { it in tags } }.takeLast(8).joinToString("\n") { "- ${it.text}" }.ifEmpty { "nothing yet" },
            "due_words" to t.pool.words.joinToString("、") { it.word }.ifEmpty { "none this turn" },
            "due_aim" to if (t.pool.words.isEmpty()) "" else
                "Aim to fit in about ${minOf(settings.aimWords, t.pool.words.size)} of them, earlier ones in the list first, wherever they " +
                    "belong naturally. Fewer is fine when the scene doesn't take them; never bend the story to fit one.",
            "due_grammar" to (t.pool.grammar.map { "${it.id} (${it.meaning})" } +
                t.pool.weakPoints.map { "correct form of: $it" }).joinToString("\n") { "- $it" }.ifEmpty { "none this turn" },
            "opening" to if (s.openingsThisSession > 0 && beat != null) "\n(This beat was planned so a practised point can come up naturally. Don't force it.)\n" else "",
            "fixes" to "",
            "length" to settings.length,
            "style_rules" to prompts.config(if (settings.firmStyleRules) "style_rules_firm.md" else "style_rules_positive.md").orEmpty().trim(),
            "style_line" to (prompts.config("style_line.txt")?.trim()?.takeIf { it.isNotEmpty() } ?: "Plain, concrete prose."),
        ))
    }

    private fun write(t: PendingTurn): Draft {
        val packet = t.packet ?: turnPacket(t).also { t.packet = it }
        val system = writerSystem()
        val messages = writerMessages(t, packet)
        val first = retry(3) { parseDraft(call("writer", Models.WRITER, system.first().text, messages, schema = WRITER_SCHEMA,
            maxTokens = 10000, cacheSystem = true, extraSystem = system.drop(1)), t) }
        val problems = problems(first, t)
        if (problems.isEmpty()) return first.copy(checks = first.checks + CheckResult("all checks", true, ""))
        val raw = JSONObject().put("passage", first.passage).put("used", JSONArray(first.used.map { JSONObject().put("word", it.first).put("form", it.second) }))
            .put("grammar", JSONArray(first.grammar)).toString()
        val fixed = runCatching { parseDraft(call("rewrite", Models.WRITER, system.first().text,
            messages + Msg(Role.ASSISTANT, raw) + Msg(Role.USER, Prompts.fill(prompts.prompt("rewrite"), mapOf(
                "problems" to problems.joinToString("\n") { "- ${it.detail}" }))),
            schema = WRITER_SCHEMA, maxTokens = 10000, cacheSystem = true, extraSystem = system.drop(1)), t) }
            .getOrElse { return first.copy(checks = problems + CheckResult("rewrite", false, "rewrite failed: ${it.message}")) }
        // one retry, then accept: what's still wrong is logged, not retried again
        return fixed.copy(checks = problems.map { it.copy(detail = "fixed by rewrite: " + it.detail) } + cheapChecks(fixed.passage), rewritten = true)
    }

    private fun parseDraft(text: String, t: PendingTurn): Draft {
        val o = json(text)
        check(o.optString("passage").isNotBlank()) { "the writer returned an empty passage" }
        val passage = o.getString("passage").trim()
        val used = o.optJSONArray("used")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("word") to it.optString("form") }
        }.orEmpty().filter { (w, f) -> f.isNotBlank() && f in passage && t.pool.words.any { it.word == w } }
        val grammar = o.optJSONArray("grammar")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            .filter { g -> t.pool.grammar.any { it.id == g } }
        return Draft(passage, used, grammar, emptyList(), false)
    }

    private fun cheapChecks(passage: String): List<CheckResult> {
        val out = ArrayList<CheckResult>()
        unknownCheck(passage)?.let { (rate, words) ->
            out += CheckResult("unknown words", rate <= settings.unknownLimit, "%.1f%% unknown: %s".format(rate * 100, words.joinToString("、")))
        }
        val hits = bannedHits(passage)
        out += CheckResult("banned phrases", hits.isEmpty(), hits.joinToString(" / "))
        return out
    }

    private val seenFile get() = File(dir, "state/seen_words.txt")

    /** Words the story already used: you've had the chance to tap them, so they don't send passages back again. */
    private fun seenWords(): Set<String> = if (seenFile.exists()) seenFile.readLines().filter { it.isNotBlank() }.toSet() else emptySet()

    /** Unknown share not counting words the story already introduced, and those unknown lemmas. */
    private fun unknownCheck(passage: String): Pair<Double, List<String>>? {
        val (total, occurrences) = unknownWords?.check(passage) ?: return null
        val seen = seenWords()
        // the cast's names aren't vocabulary (タム in グエン・ヴァン・タム, 村井 in 村井 早苗)
        val names = state.castNameParts()
        val fresh = occurrences.filter { w -> w !in seen && names.none { it.contains(w) || w.contains(it) } }
        return (if (total == 0) 0.0 else fresh.size.toDouble() / total) to fresh.distinct()
    }

    private fun bannedHits(passage: String): List<String> {
        val patterns = prompts.config("banned.txt").orEmpty().lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { runCatching { Regex(it) }.getOrNull() }
        return sentences(passage).filter { s -> patterns.any { it.containsMatchIn(s) } }
    }

    private fun sentences(text: String) = Regex("[^。！？!?\\n]+[。！？!?」』]*").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()

    /** Everything that sends a passage back for its one rewrite. */
    private fun problems(d: Draft, t: PendingTurn): List<CheckResult> {
        val out = ArrayList<CheckResult>()
        unknownCheck(d.passage)?.let { (rate, words) ->
            if (rate > settings.unknownLimit) out += CheckResult("unknown words", false,
                "A reader with about 5,000 words may not know: ${words.joinToString("、")}. Where an everyday word fits naturally, use it.")
        }
        bannedHits(d.passage).forEach { out += CheckResult("banned phrase", false, "Rewrite this sentence without its stock phrase: $it") }
        leaks(d, t).forEach { out += CheckResult("leak", false, "This sentence makes the word 「${it.second}」 too easy to guess from context; keep the word, make the context less leading: ${it.first}") }
        val judged = json(call("judge", Models.CHECKS, Prompts.fill(prompts.prompt("judge"), mapOf(
            "player" to playerText(),
            "message" to (t.player ?: "(none: the scene continues)"), "passage" to d.passage,
        )), listOf(Msg(Role.USER, "Check.")), schema = JUDGE_SCHEMA, maxTokens = 800))
        judged.optJSONArray("violations")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { v ->
                val q = v.optString("quote").trim().trim('「', '」')
                // a quote that isn't in the passage is the checker's mistake, not the writer's
                if (q.isEmpty() || q !in d.passage) return@let
                out += CheckResult(v.optString("rule"), false, "${v.optString("rule")}: 「$q」. ${v.optString("fix")}")
            }
        }
        return out
    }

    /** Leak check: Haiku guesses each blanked review word; a correct guess means the context gives it away. */
    private fun leaks(d: Draft, t: PendingTurn): List<Pair<String, String>> {
        val reviewed = d.used.filter { (w, _) -> t.pool.words.any { it.word == w && !it.isNew } }
        if (reviewed.isEmpty()) return emptyList()
        var blanked = d.passage
        val order = ArrayList<Pair<String, String>>()
        for ((w, form) in reviewed.sortedBy { d.passage.indexOf(it.second) }) {
            val i = blanked.indexOf(form)
            if (i < 0) continue
            blanked = blanked.substring(0, i) + "□□" + blanked.substring(i + form.length)
            order += w to form
        }
        val guesses = json(call("leak", Models.CHECKS, Prompts.fill(prompts.prompt("leak"), mapOf("passage" to blanked)),
            listOf(Msg(Role.USER, "Guess.")), schema = LEAK_SCHEMA, maxTokens = 300)).optJSONArray("guesses")
        return order.mapIndexedNotNull { i, (w, form) ->
            val g = guesses?.optString(i).orEmpty()
            if (g == w || g == form) sentences(d.passage).firstOrNull { form in it }?.let { it to w } else null
        }
    }

    /** Known mistake keys the last checked message used correctly (for "fixed" tracking). */
    var lastCorrectKeys: List<String> = emptyList()
        private set

    /** Definite errors in the player's Japanese (prompts/mistakes.md). */
    fun checkMistakes(message: String, knownKeys: List<String>): List<Correction> {
        val s = state.scene()
        val context = turnsInChapter(s.chapter).lastOrNull()?.optString("passage").orEmpty()
        val o = json(call("mistakes", Models.CHECKS, Prompts.fill(prompts.prompt("mistakes"), mapOf(
            "context" to context.ifBlank { "(start of the story)" }, "known_keys" to knownKeys.joinToString("\n").ifBlank { "(none yet)" },
            "message" to message,
        )), listOf(Msg(Role.USER, "Check the message.")), schema = MISTAKES_SCHEMA, maxTokens = 1200))
        lastCorrectKeys = o.optJSONArray("correct_keys")?.let { k -> (0 until k.length()).map { k.getString(it) } }.orEmpty()
            .filter { it in knownKeys }
        val a = o.optJSONArray("errors") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Correction(it.getString("wrong"), it.getString("right"), it.optString("type"), it.optString("reason"), it.getString("key")) }
            // the span must be real text from the message, and actually change something
            .filter { it.wrong.isNotBlank() && it.wrong in message && it.wrong != it.right && it.key.isNotBlank() }
    }

    /** The popup's one-line meaning of a word in its sentence. */
    fun meaning(word: String, sentence: String): String =
        call("meaning", Models.CHECKS, Prompts.fill(prompts.prompt("meaning"), mapOf("word" to word, "sentence" to sentence)),
            listOf(Msg(Role.USER, "Meaning?")), maxTokens = 120).trim()

    // ------------------------------------------------------------------ helpers

    private fun playerText() = File(dir, "state/player.md").takeIf { it.exists() }?.readText()?.trim().orEmpty().ifEmpty { "the protagonist" }

    private fun sceneSummary(s: StoryState.Scene) =
        "Place: ${s.place}. Present: ${s.present.joinToString()}. Beat: ${s.beats.firstOrNull { !it.done }?.goal ?: "none"}.\n" +
            "Last passage: " + turnsInChapter(s.chapter).lastOrNull()?.optString("passage").orEmpty()

    private fun records(): Map<String, String> = mapOf(
        "plot" to prompts.config("plot.md").orEmpty(),
        "world" to state.world(),
        "characters" to state.characterNames().joinToString("\n\n") { n -> "### $n\n" + state.character(n) },
        "secrets" to state.secrets().joinToString("\n") { "- ${it.id}: ${it.text} [knows: ${it.knows.joinToString()}]" }.ifEmpty { "none" },
        "consequences" to state.consequences().filter { it.open }.joinToString("\n") { "- ${it.id}: ${it.text}" }.ifEmpty { "none" },
        "chronicle" to state.chronicle().takeLast(20).joinToString("\n") { "- ${it.id}: ${it.text}" }.ifEmpty { "none" },
    )

    private fun <T> retry(times: Int, block: () -> T): T {
        var last: Throwable? = null
        repeat(times) { runCatching(block).onSuccess { return it }.onFailure { last = it } }
        throw last!!
    }

    companion object {
        const val ONE_SHOT_SYSTEM = "You assist the game master of a Japanese roleplaying game. Do the job in the user's message and answer in the requested format, with every field filled in properly."

        val WRITER_SCHEMA = """{"type":"object","additionalProperties":false,"required":["passage","used","grammar"],"properties":{
            "passage":{"type":"string"},
            "used":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["word","form"],"properties":{"word":{"type":"string"},"form":{"type":"string"}}}},
            "grammar":{"type":"array","items":{"type":"string"}}}}"""
        val MISTAKES_SCHEMA = """{"type":"object","additionalProperties":false,"required":["errors","correct_keys"],"properties":{
            "correct_keys":{"type":"array","items":{"type":"string"}},"errors":{"type":"array","items":{
            "type":"object","additionalProperties":false,"required":["wrong","right","type","reason","key"],"properties":{
            "wrong":{"type":"string"},"right":{"type":"string"},
            "type":{"type":"string","enum":["conjugation","particle","word choice","grammar pattern","spelling","other"]},
            "reason":{"type":"string"},"key":{"type":"string"}}}}}}"""
        val ADJUDICATOR_SCHEMA = """{"type":"object","additionalProperties":false,"required":["roll","action","difficulty","stakes"],"properties":{
            "roll":{"type":"boolean"},"action":{"type":"string"},"difficulty":{"type":"integer"},"stakes":{"type":"string"}}}"""
        val JUDGE_SCHEMA = """{"type":"object","additionalProperties":false,"required":["violations"],"properties":{"violations":{"type":"array","items":{
            "type":"object","additionalProperties":false,"required":["rule","quote","fix"],"properties":{
            "rule":{"type":"string","enum":["player_voice","echo","explains","no_handoff"]},"quote":{"type":"string"},"fix":{"type":"string"}}}}}}"""
        val LEAK_SCHEMA = """{"type":"object","additionalProperties":false,"required":["guesses"],"properties":{"guesses":{"type":"array","items":{"type":"string"}}}}"""
        val DIRECTOR_SCHEMA = """{"type":"object","additionalProperties":false,"required":["beats","opening"],"properties":{
            "beats":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["goal","place","characters","pressure"],"properties":{
              "goal":{"type":"string"},"place":{"type":"string"},"characters":{"type":"array","items":{"type":"string"}},"pressure":{"type":"string"}}}},
            "opening":{"type":"string"}}}"""
        val STYLES_SCHEMA = """{"type":"object","additionalProperties":false,"required":["styles"],"properties":{"styles":{"type":"array","items":{
            "type":"object","additionalProperties":false,"required":["name","line","passage"],"properties":{
            "name":{"type":"string"},"line":{"type":"string"},"passage":{"type":"string"}}}}}}"""
        val SETUP_SCHEMA = """{"type":"object","additionalProperties":false,"required":["world","player","characters","secrets","clocks","complications","opening"],"properties":{
            "world":{"type":"string"},"player":{"type":"string"},
            "characters":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["name","file"],"properties":{"name":{"type":"string"},"file":{"type":"string"}}}},
            "secrets":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["text","knows"],"properties":{"text":{"type":"string"},"knows":{"type":"array","items":{"type":"string"}}}}},
            "clocks":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["name","size","when_full"],"properties":{"name":{"type":"string"},"size":{"type":"integer"},"when_full":{"type":"string"}}}},
            "complications":{"type":"array","items":{"type":"string"}},
            "opening":{"type":"object","additionalProperties":false,"required":["place","present","situation"],"properties":{"place":{"type":"string"},"present":{"type":"array","items":{"type":"string"}},"situation":{"type":"string"}}}}}"""
    }
}
