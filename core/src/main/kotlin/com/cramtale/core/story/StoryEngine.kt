package com.cramtale.core.story

import com.cramtale.core.jsonLines
import com.cramtale.core.tailJson
import com.cramtale.core.writeAtomic
import com.cramtale.core.llm.CostLog
import com.cramtale.core.llm.Llm
import com.cramtale.core.llm.LlmRequest
import com.cramtale.core.llm.Models
import com.cramtale.core.llm.PartialJson
import com.cramtale.core.llm.Msg
import com.cramtale.core.llm.Role
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A definite mistake in the player's Japanese (prompts/mistakes.md). */
/**
 * One correction of the player's Japanese. [level]: "error" (definitely wrong), "unnatural" (correct but no native
 * would say it here) or "nitpick" (a teacher's small improvement). [shown]: whether the reader sees it now (its
 * level is within their setting, or they made the same mistake before); hidden ones are kept for that.
 * [repeat]: the same key came up before.
 */
data class Correction(val wrong: String, val right: String, val type: String, val reason: String, val key: String,
                      val level: String = LEVEL_ERROR, val shown: Boolean = true, val repeat: Boolean = false) {
    companion object {
        const val LEVEL_ERROR = "error"
        val LEVELS = listOf("error", "unnatural", "nitpick")
        fun rank(level: String) = LEVELS.indexOf(level).let { if (it < 0) 0 else it }
    }

    fun toJson(): JSONObject = JSONObject().put("wrong", wrong).put("right", right).put("type", type).put("reason", reason).put("key", key)
        .put("level", level).put("shown", shown).put("repeat", repeat)
}

fun correctionFrom(o: JSONObject, key: String = o.getString("key")) = Correction(o.getString("wrong"), o.getString("right"), o.optString("type"),
    o.optString("reason"), key, o.optString("level", Correction.LEVEL_ERROR).ifBlank { Correction.LEVEL_ERROR }, o.optBoolean("shown", true), o.optBoolean("repeat"))

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
    /** What each person present does next, decided from what they know (one call each); null until decided. */
    var intents: String? = null
    /** What others could notice of the player's message (the referee's neutral line); "" if nothing. */
    var observable: String = ""
    val draft get() = drafts[current]
    /** Written yet? A turn whose writer call failed has no drafts and resumes from here. */
    val ready get() = drafts.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("turn", turn).put("player", player ?: JSONObject.NULL).put("rollNote", rollNote ?: JSONObject.NULL)
        .put("complication", complication ?: JSONObject.NULL).put("packet", packet ?: JSONObject.NULL).put("current", current)
        .put("corrections", JSONArray(corrections.map { it.toJson() }))
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
        .put("correctKeys", JSONArray(correctKeys)).put("intents", intents ?: JSONObject.NULL).put("observable", observable)

    companion object {
        private fun JSONArray?.objects() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
        private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k)

        fun fromJson(o: JSONObject): PendingTurn {
            val p = o.getJSONObject("pool")
            val t = PendingTurn(
                turn = o.getInt("turn"), player = o.str("player"),
                corrections = o.optJSONArray("corrections").objects().map { correctionFrom(it) },
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
            t.intents = o.str("intents")
            t.observable = o.optString("observable")
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
        /** Which corrections show: 0 errors only, 1 also unnatural phrasing, 2 everything (nitpicks too). */
        val corrections: Int = 0,
        /** Review words to aim for in a passage (fewer is fine when the scene doesn't take them). */
        val aimWords: Int = 7,
    )

    val prompts = Prompts(dir)
    val state = StoryState(dir)
    /** Every turn, slim (no packets): the story's whole history for the app. */
    private val logFile = File(dir, "log/turns.jsonl")
    /** One chapter's turns in full (with what was sent): what the writer's history and the checks read. */
    private fun chapterLog(n: Int) = File(dir, "log/chapters/$n.jsonl")
    private val chaptersDir = File(dir, "state/chapters")
    private val sagaFile = File(dir, "state/saga.md")
    private val consolidatedFile = File(dir, "state/consolidated.txt")
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

    /**
     * The passage as it streams in: (phase, text so far, final). Phases: "writing", "rewriting"; final is
     * true once for the whole passage, so a display that skips updates still ends on the full text.
     */
    var onDraft: (String, String, Boolean) -> Unit = { _, _, _ -> }

    /** Stops background work (the early scribe): a new engine or a new story takes over. */
    fun shutdown() { scribeAhead = null; workers.shutdownNow() }

    // calls that don't depend on each other run side by side
    private val workers = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "story-call").apply { isDaemon = true } }

    /** Several independent calls at once, results in order. */
    private fun <T> all(tasks: List<() -> T>): List<T> {
        val fs = tasks.drop(1).map { workers.submit(java.util.concurrent.Callable { it() }) }
        val first = tasks.firstOrNull()?.let { t -> try { t() } catch (e: Throwable) { fs.forEach { it.cancel(true) }; throw e } }
        return listOfNotNull(first) + fs.map { f -> try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e } }
    }

    private fun <A, B> both(a: () -> A, b: () -> B): Pair<A, B> {
        val fb = workers.submit(java.util.concurrent.Callable { b() })
        val ra = try { a() } catch (e: Throwable) { fb.cancel(true); throw e }
        val rb = try { fb.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
        return ra to rb
    }

    private val logLock = Any()

    private fun call(purpose: String, model: String, system: String, messages: List<Msg>, schema: String? = null,
                     maxTokens: Int = 4096, cacheSystem: Boolean = false, extraSystem: List<Msg> = emptyList(),
                     /** streams the reply, handing over its "passage" field as it grows */
                     onPassage: ((String) -> Unit)? = null,
                     /** a background call: no progress line */
                     quiet: Boolean = false,
                     /** streams the reply, handing over all of its text so far */
                     onText: ((String) -> Unit)? = null): String {
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
        if (!quiet) onCall(purpose)
        fun send(r: LlmRequest) = if (onPassage == null && onText == null) llm.call(r) else {
            val sb = StringBuilder()
            llm.stream(r) { piece ->
                sb.append(piece)
                onText?.invoke(sb.toString())
                onPassage?.let { f -> PartialJson.stringField(sb.toString(), "passage")?.let(f) }
            }
        }
        var resp = send(req)
        costs.record(session, req, resp)
        if (resp.stopReason == "max_tokens") {
            // thinking counts against max_tokens and can't be turned off on Opus 5.5: one retry with room to spare
            val bigger = req.copy(maxTokens = req.maxTokens * 2)
            resp = send(bigger)
            costs.record(session, bigger, resp)
        }
        // every reply, for tuning prompts (log/calls.jsonl); calls can run in parallel
        synchronized(logLock) {
            File(dir, "log").mkdirs()
            // a debug log: kept to its last ~20 MB, not the whole of a long game
            File(dir, "log/calls.jsonl").takeIf { it.length() > 20_000_000 }?.let { it.renameTo(File(dir, "log/calls.old.jsonl").apply { delete() }) }
            File(dir, "log/calls.jsonl").appendText(JSONObject().put("purpose", purpose).put("model", resp.model)
                .put("out", resp.usage.output).put("text", resp.text).toString() + "\n")
        }
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
                "plot" to prompts.config("plot.md"),
                // what the player asked for is the premise, first; "Surprise me" gets a spark picked by code
                "seed" to if (seed.isBlank()) "\n## A spark to build from (the player asked to be surprised)\n${surprise()}\n" +
                    "Take it somewhere of your own: invent the specifics, keep the genre.\n" else
                    "\n## What the player asked for\n$seed\n\nThis is the premise: the story is set where it says and is about what it says, " +
                        "and if it says who the player plays, that is the player's character. Everything below serves it.\n",
            )), listOf(Msg(Role.USER, "Create the game now, with every field filled in.")), schema = SETUP_SCHEMA, maxTokens = 16000)
        }
        val stateDir = File(dir, "state").apply { mkdirs() }
        val clocks = o.optJSONArray("clocks")
        File(stateDir, "world.md").writeAtomic(o.getString("world").trimEnd() + "\n\n## Clocks\n" +
            (0 until (clocks?.length() ?: 0)).joinToString("\n") { i ->
                val c = clocks!!.getJSONObject(i)
                "- ${c.getString("name")}: 0/${c.optInt("size", 6)} — when full: ${c.optString("when_full")}"
            } + "\n")
        File(stateDir, "player.md").writeAtomic(o.optString("player").trim() + "\n")
        o.optString("title").takeIf { it.isNotBlank() }?.let { File(stateDir, "title.txt").writeAtomic(it.trim() + "\n") }
        // the backstory goes into the record as witnessed events: everyone's memory, the player's included, starts here
        o.optJSONArray("history")?.let { h ->
            for (i in 0 until h.length()) h.getJSONObject(i).let { e ->
                val w = e.optJSONArray("witnesses")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()
                if (e.optString("text").isNotBlank() && w.isNotEmpty()) state.addEvent(-1, "before the story", e.getString("text"), w)
            }
        }
        File(stateDir, "player_public.md").writeAtomic(o.optString("player_public").ifBlank { o.optString("player") } + "\n")
        val chars = o.getJSONArray("characters")
        for (i in 0 until chars.length()) chars.getJSONObject(i).let { state.writeCharacter(it.getString("name"), it.getString("file")) }
        o.optJSONArray("extras")?.let { a ->
            state.writeExtras((0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("name").isNotBlank() }
                .associate { it.getString("name") to it.optString("line") })
        }
        checkKnows()
        val secrets = o.optJSONArray("secrets")
        File(stateDir, "secrets.md").writeAtomic("# Secrets (game master only)\n\n" + (0 until (secrets?.length() ?: 0)).joinToString("\n") { i ->
            val s = secrets!!.getJSONObject(i)
            "- S${i + 1}: ${s.getString("text")} [knows: ${(0 until s.getJSONArray("knows").length()).joinToString(", ") { s.getJSONArray("knows").getString(it) }}]"
        } + "\n")
        o.optJSONArray("complications")?.takeIf { it.length() > 0 }?.let { a ->
            val f = File(dir, "config/complications.md")
            // the world's own replace the generic ones (phones and trains don't belong in every world)
            f.parentFile.mkdirs(); f.writeAtomic(defaultsHeader(f))
            f.appendText((0 until a.length()).joinToString("") { "- ${a.getString(it).removePrefix("- ")}\n" })
        }
        o.optJSONArray("ambience")?.takeIf { it.length() > 0 }?.let { a ->
            val f = File(dir, "config/ambience.md")
            f.parentFile.mkdirs(); f.writeAtomic(defaultsHeader(f))
            f.appendText((0 until a.length()).joinToString("") { "- ${a.getString(it).removePrefix("- ")}\n" })
        }
        val opening = o.getJSONObject("opening")
        state.save(state.scene().apply {
            place = opening.getString("place")
            present = (0 until opening.getJSONArray("present").length()).map { opening.getJSONArray("present").getString(it) }.toMutableList()
            beats = mutableListOf(StoryState.Beat(opening.getString("situation"), place, present.toList(), ""))
        })
    }

    /** A config file's comment lines (how it works), without its generic entries. */
    private fun defaultsHeader(f: File): String =
        (f.takeIf { it.exists() }?.readText() ?: prompts.config(f.name).orEmpty()).lines().takeWhile { it.startsWith("#") || it.isBlank() }
            .joinToString("\n").trimEnd() + "\n"

    /** "Surprise me": a spark from a wide range of genres, picked by code (left alone, a model drifts to the same kind of story). */
    private fun surprise(): String = SPARKS[dice.random.nextInt(SPARKS.size)]

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

    fun pickStyle(o: StyleOption) = StylePresets.write(dir, o)

    fun hasStyle() = !prompts.config("style_sample.md").isNullOrBlank()

    // ------------------------------------------------------------------ sessions

    /** Between sessions the world moves (off-screen), then the director plans three beats. */
    /**
     * Each character's Knows, checked once per story for the game master's knowledge leaking in ("doesn't know
     * what he is"): whoever plays them reads every word, and a stated unknown makes the player a mystery to solve.
     * Stories made before this check get it at their next session. A failed check leaves the file as it was.
     */
    fun checkKnows() {
        val done = File(dir, "state/knows_checked")
        if (done.exists()) return
        all(state.characterNames().map { name -> {
            runCatching {
                val file = state.character(name) ?: return@runCatching
                if ("## Knows" !in file) return@runCatching
                val o = json(call("knows_check", Models.CHARACTERS, Prompts.fill(prompts.prompt("knows_check"), mapOf(
                    "player" to playerText(), "player_public" to playerPublic(), "file" to file,
                )), listOf(Msg(Role.USER, "Check the Knows section.")), schema = KNOWS_SCHEMA, maxTokens = 3000, quiet = true))
                val knows = o.optString("knows").trim()
                if (knows.isNotEmpty()) state.writeCharacter(name, StoryState.replaceSection(file, "Knows", knows))
            }
        } })
        done.parentFile.mkdirs(); done.writeText("1\n")
    }

    fun startSession(pool: DuePool): List<String> {
        runCatching { checkKnows() }
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
    fun playerTurn(message: String?, pool: DuePool, knownMistakeKeys: List<String> = emptyList(),
                   /** Whether a mistake key came up before (all of history; [knownMistakeKeys] is only what the checker is shown). */
                   seenMistake: (String) -> Boolean = { it in knownMistakeKeys }): PendingTurn {
        pending?.let { p ->
            check(!p.ready) { "a passage is already waiting for its reply" }
            return resume()
        }
        maybeNewChapter()
        val scene = state.scene()
        // the mistakes check and the referee don't depend on each other: side by side
        val (corrections, adjudication) = if (message.isNullOrBlank()) emptyList<Correction>() to null else both(
            { checkMistakes(message, knownMistakeKeys, seenMistake) },
            { json(call("adjudicator", Models.CHECKS, Prompts.fill(prompts.prompt("adjudicator"), mapOf(
                "scene" to sceneSummary(scene), "message" to message,
            )), listOf(Msg(Role.USER, "Decide.")), schema = ADJUDICATOR_SCHEMA, maxTokens = 400)) },
        )
        val correctKeys = lastCorrectKeys
        var roll: Dice.Roll? = null
        var rollNote: String? = null
        if (adjudication != null) {
            val a = adjudication
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
            val table = Complications.parse(prompts.config("complications.md").orEmpty())
            // a long game uses every one: the oldest come back (not the last few)
            if (s.usedComplications.isNotEmpty() && table.unused(s.usedComplications) == 0)
                s.usedComplications = s.usedComplications.takeLast(5).toMutableList()
            val c = table.draw(s.place, s.usedComplications)
            s.complication = c
            s.complicationFor = beat?.goal
            if (c != null) s.usedComplications += c
            state.save(s)
        }
        val turn = PendingTurn(s.turn, message, corrections, roll, rollNote, s.complication, pool)
        turn.correctKeys = correctKeys
        turn.observable = adjudication?.optString("observable").orEmpty()
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
            // who does what, decided per person from what they know, before the writer sees anything
            if (t.intents == null) { t.intents = decideIntents(t); savePending() }
            val d = write(t)
            synchronized(this) { t.drafts += d; t.current = 0 }
            savePending()
        }
        return t
    }

    /** Shows an earlier version again (reroll keeps every version). */
    fun selectDraft(i: Int): Draft {
        val t = pending ?: error("no pending turn")
        synchronized(this) { t.current = i.coerceIn(0, t.drafts.size - 1) }
        savePending()
        return t.draft
    }

    /** A fresh version of the pending passage (taps on earlier versions still count). */
    fun reroll(): Draft {
        val t = pending ?: error("no pending turn")
        val d = write(t)
        synchronized(this) { t.drafts += d; t.current = t.drafts.size - 1 }
        savePending()
        return d
    }

    /** The player edited the passage text: recheck it without a model call. */
    fun edit(text: String): Draft {
        val t = pending ?: error("no pending turn")
        val d = Draft(text, t.draft.used.filter { it.second in text }, t.draft.grammar, cheapChecks(text), t.draft.rewritten)
        synchronized(this) { t.drafts += d; t.current = t.drafts.size - 1 }
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
        // the scribe usually ran already, in the background while the passage was read
        val passage = t.draft.passage
        val ahead = scribeAhead?.takeIf { it.first == passage }?.second
        scribeAhead = null
        val ops = StateOp.parse(ahead?.let { f -> runCatching { f.get() }.getOrNull() } ?: scribe(t, passage, quiet = false))
        val applied = state.apply(ops)
        unknownWords?.check(t.draft.passage)?.second?.let { words ->
            seenFile.parentFile.mkdirs()
            val all = seenWords() + words
            if (all.size != seenWords().size) seenFile.writeAtomic(all.sorted().joinToString("\n") + "\n")
        }
        val entry = JSONObject()
            .put("turn", t.turn).put("at", System.currentTimeMillis()).put("chapter", s.chapter).put("player", t.player ?: "")
            .put("passage", t.draft.passage).put("roll", t.roll?.toString() ?: "")
            .put("place", s.place).put("present", JSONArray(s.present)).put("observable", t.observable)
            .put("corrections", JSONArray(t.corrections.map { it.toJson() }))
            .put("checks", JSONArray(t.draft.checks.map { "${it.name}: ${if (it.passed) "ok" else it.detail}" }))
            .put("versions", t.drafts.size)
        // the chapter's file first: it is what "already recorded" checks; then the slim line in the whole log
        val full = JSONObject(entry.toString()).put("packet", t.packet ?: "").put("sentAs", sentAs(t, turnsInChapter(s.chapter).isEmpty()))
            .put("ops", JSONArray(applied))
        chapterLog(s.chapter).also { it.parentFile.mkdirs() }.appendText(full.toString() + "\n")
        logFile.appendText(entry.toString() + "\n")
        val s2 = state.scene()
        s2.turn += 1
        s2.lastActive = System.currentTimeMillis()
        state.save(s2)
        pending = null
        savePending()
        // a failed summary call is tried again before the next turn (maybeNewChapter in playerTurn)
        return applied + listOfNotNull(runCatching { maybeNewChapter() }.getOrNull())
    }

    private fun scribe(t: PendingTurn, passage: String, quiet: Boolean): String {
        val s = state.scene()
        return call("scribe", Models.CHARACTERS, Prompts.fill(prompts.prompt("scribe"), records() + mapOf(
            "present" to s.present.joinToString(), "place" to s.place,
            "beat" to (s.beats.firstOrNull { !it.done }?.goal ?: "none"),
            // the latest few and anyone here (code refuses a second entry for someone already known anyway)
            "others" to state.extras().entries.toList().let { x -> x.filter { it.key in s.present } + x.takeLast(15) }.distinct()
                .joinToString("; ") { "${it.key}: ${it.value}" }.ifEmpty { "none" },
            "clocks" to state.clocks().joinToString("; ") { "${it.name} ${it.filled}/${it.size}" },
            "message" to (t.player ?: "(the player let the scene continue)"), "passage" to passage,
        )), listOf(Msg(Role.USER, "List the changes.")), schema = StateOp.SCHEMA, maxTokens = 4000, quiet = quiet)
    }

    @Volatile private var scribeAhead: Pair<String, java.util.concurrent.Future<String>>? = null

    /**
     * Starts the scribe on the shown version in the background (it only reads the state), so sending a
     * reply doesn't wait for it. A reroll or edit makes it start again on the new text.
     */
    fun prepareCommit() {
        val t = pending ?: return
        if (!t.ready) return
        val passage = t.draft.passage
        if (scribeAhead?.first == passage) return
        scribeAhead = passage to workers.submit(java.util.concurrent.Callable { scribe(t, passage, quiet = true) })
    }

    /** Closes the chapter once it has enough turns. */
    fun maybeNewChapter(): String? {
        // a chapter that closed but whose long memory failed to update is caught up first
        runCatching { consolidatePending() }
        return if (turnsInChapter(state.scene().chapter).size >= settings.chapterTurns) newChapter() else null
    }

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
        runCatching { consolidatePending() }
        return "chapter ${s.chapter} begins"
    }

    // ------------------------------------------------------------------ long memory

    /** The whole story compressed (rewritten at every chapter close), or null before the first one closes. */
    fun saga(): String? = sagaFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /** Chapters closed but not yet folded into the long memory (a failed call is caught up at the next chance). */
    private fun consolidatePending() {
        val done = consolidatedFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        for (n in (done + 1) until state.scene().chapter) {
            if (!File(chaptersDir, "$n.md").exists()) continue
            consolidate(n)
            consolidatedFile.writeAtomic("$n\n")
        }
    }

    /**
     * Folds closed chapter [n] into the long memory, so a game of hundreds of hours stays whole while every prompt
     * stays the same size: the story so far is rewritten with it; everyone who perceived something in it gets their
     * "Remembers" rewritten from what they perceived (their "Learned" notes folded in); the facts established about
     * the player are merged when they pile up; and threads long overdue are closed.
     */
    internal fun consolidate(n: Int) {
        onCall("saga")
        val chapter = File(chaptersDir, "$n.md").readText().trim()
        val saga = call("saga", Models.WRITER, Prompts.fill(prompts.prompt("saga"), mapOf(
            "saga" to (saga() ?: "(this is the first chapter)"), "chapter" to chapter, "chapter_number" to n.toString(),
        )), listOf(Msg(Role.USER, "Rewrite the story so far.")), maxTokens = 6000, quiet = true).trim()
        check(saga.isNotBlank()) { "the story so far came back empty" }
        sagaFile.writeAtomic(saga + "\n")

        val turns = turnsInChapter(n).map { it.optInt("turn") }
        val events = if (turns.isEmpty()) emptyList() else state.events().filter { it.turn in turns.min()..turns.max() }
        all(state.characterNames().filter { name -> events.any { name in it.witnesses } }.map { name -> {
            runCatching {
                val file = state.character(name) ?: return@runCatching
                val o = json(call("remember", Models.CHARACTERS, Prompts.fill(prompts.prompt("remember"), mapOf(
                    "name" to name, "file" to StoryState.removeSection(file, "Remembers"),
                    "remembers" to (StoryState.section(file, "Remembers") ?: "(nothing yet)"),
                    "events" to events.filter { name in it.witnesses }.joinToString("\n") { memoryLine(it) },
                )), listOf(Msg(Role.USER, "Rewrite what $name remembers.")), schema = REMEMBER_SCHEMA, maxTokens = 3000, quiet = true))
                val r = o.optString("remembers").trim()
                // read again: a turn may have changed the file meanwhile
                val now = state.character(name) ?: return@runCatching
                if (r.isNotEmpty()) state.writeCharacter(name, StoryState.replaceSection(StoryState.removeSection(now, "Learned"), "Remembers", r))
            }
        } })

        runCatching {
            val f = File(dir, "state/player.md")
            val sheet = f.takeIf { it.exists() }?.readText() ?: return@runCatching
            val facts = StoryState.section(sheet, "Established in the story") ?: return@runCatching
            if (facts.lines().count { it.isNotBlank() } <= 25) return@runCatching
            val merged = call("condense", Models.CHARACTERS, Prompts.fill(prompts.prompt("condense"), mapOf("facts" to facts)),
                listOf(Msg(Role.USER, "Rewrite the list.")), maxTokens = 3000, quiet = true).trim()
            if (merged.lines().any { it.startsWith("- ") }) f.writeAtomic(StoryState.replaceSection(sheet, "Established in the story", merged))
        }

        // threads long overdue (and the oldest of the undated ones, past a few dozen) are closed: they stopped mattering
        val turn = state.scene().turn
        val open = state.consequences().filter { it.open }
        val stale = open.filter { c -> c.dueTurn != null && c.dueTurn < turn - 40 } + open.filter { it.dueTurn == null }.dropLast(30)
        if (stale.isNotEmpty()) state.apply(stale.map { StateOp.ResolveConsequence(it.id) })
    }

    // ------------------------------------------------------------------ keeping prompts the same size in a long game

    /** Who is in play: here now, in a planned beat, or seen in the record lately. Everyone else gets one line. */
    private fun activeCast(): Set<String> {
        val s = state.scene()
        val recent = state.events().filter { it.turn >= s.turn - 60 }.flatMap { it.witnesses }
        return (s.present + s.beats.flatMap { it.characters } + recent).toSet()
    }

    /** Someone not in play lately, in one line: where they stand, else their goals' first line. */
    private fun brief(name: String): String {
        val f = state.character(name).orEmpty()
        val line = (StoryState.section(f, "Now") ?: StoryState.section(f, "Goals")).orEmpty().lines().firstOrNull { it.isNotBlank() }.orEmpty()
        return "- $name (not seen lately): ${line.take(200)}"
    }

    private fun castBlock(): String {
        val names = state.characterNames()
        val active = if (names.size <= 8) names.toSet() else activeCast()
        val full = names.filter { it in active }.joinToString("\n\n") { n -> "### $n\n" + state.character(n) }
        // the dormant: the most recently made two dozen in a line each, the rest by name only
        val dormant = names.filter { it !in active }
        val rest = (dormant.takeLast(24).map { brief(it) } + listOfNotNull(dormant.dropLast(24).takeIf { it.isNotEmpty() }
            ?.let { "- also, long out of the story: " + it.take(60).joinToString() + if (it.size > 60) " and ${it.size - 60} more" else "" })).joinToString("\n")
        return full + if (rest.isEmpty()) "" else "\n\n#### Others in the story, not in play lately\n$rest"
    }

    private fun openConsequences(): List<StoryState.Consequence> {
        val open = state.consequences().filter { it.open }
        return open.filter { it.dueTurn != null } + open.filter { it.dueTurn == null }.takeLast(15)
    }

    private fun boundedSecrets(): List<StoryState.Secret> {
        val all = state.secrets()
        if (all.size <= 20) return all
        val active = activeCast()
        return all.filter { s -> s.knows.isEmpty() || s.knows.any { it in active } }.takeLast(20).let { (it + all.takeLast(5)).distinct() }
    }

    // ------------------------------------------------------------------ the writer and its checks

    internal fun turnsInChapter(chapter: Int): List<JSONObject> {
        val f = chapterLog(chapter)
        if (f.exists()) return f.jsonLines()
        // stories from before the chapter files: their whole log had everything
        return logFile.jsonLines().filter { it.optInt("chapter") == chapter }
    }

    private fun writerSystem(): List<Msg> {
        val fixed = Prompts.fill(prompts.prompt("writer"), mapOf(
            // the fixed (cached) prompt must not change from turn to turn: no clocks here, they're in the packet
            "plot" to prompts.config("plot.md"), "world" to state.world().substringBefore("## Clocks").trim() + "\n\n## The player's character\n" + playerText(),
            "style_sample" to prompts.config("style_sample.md"),
        ))
        val prev = File(chaptersDir, "${state.scene().chapter - 1}.md")
        // the long view: the whole story so far, rewritten each chapter, then the previous chapter in more detail
        val earlier = saga()?.let { "## The story so far, from the beginning\n$it\n\n" }.orEmpty()
        val summary = if (prev.exists()) listOf(Msg(Role.SYSTEM, earlier + "## The story so far (previous chapter)\n" + prev.readText(), cache = true)) else emptyList()
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
            // who else is in play (by name), not everyone the story ever met
            (state.characterNames() - names).let { away -> val inPlay = if (away.size <= 20) away else away.filter { it in activeCast() }.takeLast(20)
                inPlay.map { n -> "- $n (not here)" } + listOfNotNull((away.size - inPlay.size).takeIf { it > 0 }?.let { "- ($it others not in play lately)" }) } +
            listOfNotNull(extrasBlock(names).trim().takeIf { it.isNotEmpty() })
        val tags = names + s.place
        return Prompts.fill(prompts.prompt("turn"), mapOf(
            "player" to playerText(), "player_public" to playerPublic(),
            "intents" to (t.intents ?: "not decided"),
            "record" to sceneRecord(s),
            "place" to s.place, "present" to s.present.joinToString().ifEmpty { "nobody but the player" },
            "turn" to s.turn.toString(), "chapter" to s.chapter.toString(),
            "beat" to (beat?.let { "${it.goal} Pressure: ${it.pressure}" } ?: "Let the scene breathe; follow what the player does."),
            "roll" to (t.roll?.let { "$it. ${t.rollNote ?: it.outcome.instruction}" } ?: "No roll this turn."),
            "complication" to (t.complication ?: "none"),
            "characters" to characters.joinToString("\n\n"),
            "secrets" to state.secrets().filter { sec -> sec.knows.any { it in names } || sec.knows.isEmpty() }.takeLast(25)
                .joinToString("\n") { "- ${it.id}: ${it.text} (known to ${it.knows.joinToString()})" }.ifEmpty { "none" },
            "consequences" to openConsequences().joinToString("\n") { c ->
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
            "fixes" to recentSlips(),
            "length" to settings.length,
            "style_rules" to prompts.config(if (settings.firmStyleRules) "style_rules_firm.md" else "style_rules_positive.md").orEmpty().trim(),
            "style_line" to (prompts.config("style_line.txt")?.trim()?.takeIf { it.isNotEmpty() } ?: "Plain, concrete prose."),
        ))
    }

    /**
     * The rules the checks caught in the last few turns, restated at the very end of the packet (the end of the
     * prompt is what the model weighs most, like SillyTavern's depth-0 injections). Only rules actually broken
     * recently, most frequent first, so the reminder stays short and changes with what goes wrong.
     */
    internal fun recentSlips(turns: Int = 10, max: Int = 3): String {
        val counts = HashMap<String, Int>()
        val ch = state.scene().chapter
        for (o in (turnsInChapter(ch - 1) + turnsInChapter(ch)).takeLast(turns)) {
            val a = o.optJSONArray("checks") ?: continue
            val rules = (0 until a.length()).map { a.optString(it) }
                .filter { !it.endsWith(": ok") && !it.endsWith(":ok") }
                .map { it.substringBefore(':').trim().let { r -> if (r == "banned phrases") "banned phrase" else r } }
                .filter { it in SLIPS }.toSet()
            rules.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        if (counts.isEmpty()) return ""
        return "\n### Slipped in recent turns: not again\n" + counts.entries.sortedByDescending { it.value }.take(max)
            .joinToString("\n") { "- ${SLIPS.getValue(it.key)}" }
    }

    private fun write(t: PendingTurn): Draft {
        val packet = t.packet ?: turnPacket(t).also { t.packet = it }
        val system = writerSystem()
        val messages = writerMessages(t, packet)
        val first = retry(3) { parseDraft(call("writer", Models.WRITER, system.first().text, messages, schema = WRITER_SCHEMA,
            maxTokens = 10000, cacheSystem = true, extraSystem = system.drop(1), onPassage = { onDraft("writing", it, false) }), t) }
        onDraft("writing", first.passage, true)
        val problems = problems(first, t)
        if (problems.isEmpty()) return first.copy(checks = first.checks + CheckResult("all checks", true, ""))
        val raw = JSONObject().put("passage", first.passage).put("used", JSONArray(first.used.map { JSONObject().put("word", it.first).put("form", it.second) }))
            .put("grammar", JSONArray(first.grammar)).toString()
        val fixed = runCatching { parseDraft(call("rewrite", Models.WRITER, system.first().text,
            messages + Msg(Role.ASSISTANT, raw) + Msg(Role.USER, Prompts.fill(prompts.prompt("rewrite"), mapOf(
                "problems" to problems.joinToString("\n") { "- ${it.detail}" }))),
            schema = WRITER_SCHEMA, maxTokens = 10000, cacheSystem = true, extraSystem = system.drop(1),
            onPassage = { onDraft("rewriting", it, false) }), t).also { onDraft("rewriting", it.passage, true) } }
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
    // read on every check: kept in memory, re-read only when the file changed (it's bounded by your vocabulary, not by turns)
    @Volatile private var seenCache: Pair<Long, Set<String>> = -1L to emptySet()
    private fun seenWords(): Set<String> {
        val stamp = if (seenFile.exists()) seenFile.lastModified() xor seenFile.length() else 0L
        seenCache.takeIf { it.first == stamp }?.let { return it.second }
        val words = if (seenFile.exists()) seenFile.readLines().filter { it.isNotBlank() }.toSet() else emptySet()
        seenCache = stamp to words
        return words
    }

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
        // the leak check and the judge read the same draft independently: side by side
        val (leaked, judged) = both(
            { leaks(d, t) },
            { json(call("judge", Models.CHECKS, Prompts.fill(prompts.prompt("judge"), mapOf(
                "player" to playerText(), "player_public" to playerPublic(), "record" to sceneRecord(state.scene()),
                "people" to state.scene().present.mapNotNull { n -> state.character(n)?.let { "### $n\n$it" } }.joinToString("\n\n")
                    .ifEmpty { "nobody but the player" } + "\n\nWhat they were meant to do:\n" + (t.intents ?: "not decided"),
                "message" to (t.player ?: "(none: the scene continues)"), "passage" to d.passage,
            )), listOf(Msg(Role.USER, "Check.")), schema = JUDGE_SCHEMA, maxTokens = 800)) },
        )
        leaked.forEach { out += CheckResult("leak", false, "This sentence makes the word 「${it.second}」 too easy to guess from context; keep the word, make the context less leading: ${it.first}") }
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
    fun checkMistakes(message: String, knownKeys: List<String>, seen: (String) -> Boolean = { it in knownKeys }): List<Correction> {
        val s = state.scene()
        val context = turnsInChapter(s.chapter).lastOrNull()?.optString("passage").orEmpty()
        val o = json(call("mistakes", Models.CHECKS, Prompts.fill(prompts.prompt("mistakes"), mapOf(
            "context" to context.ifBlank { "(start of the story)" }, "known_keys" to knownKeys.joinToString("\n").ifBlank { "(none yet)" },
            // who is speaking, to whom and where: what sounds natural depends on it
            "player" to playerText(), "setting" to "Place: ${s.place}. Present: ${s.present.joinToString().ifEmpty { "nobody else" }}.",
            "message" to message,
        )), listOf(Msg(Role.USER, "Check the message.")), schema = MISTAKES_SCHEMA, maxTokens = 1200))
        lastCorrectKeys = o.optJSONArray("correct_keys")?.let { k -> (0 until k.length()).map { k.getString(it) } }.orEmpty()
            .filter { it in knownKeys }
        val a = o.optJSONArray("errors") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { correctionFrom(it) }
            // the span must be real text from the message, and actually change something
            .filter { it.wrong.isNotBlank() && it.wrong in message && it.wrong != it.right && it.key.isNotBlank() }
            .map { c ->
                // above the reader's setting it stays hidden, unless it's a mistake they've made before
                val before = seen(c.key)
                c.copy(shown = Correction.rank(c.level) <= settings.corrections || before, repeat = before)
            }
    }

    /**
     * What each person present does next. One cheap call per person that sees only their own file, the
     * place, the player's public side and what just happened in front of them: what the narrator and the
     * player know can't leak into what they do (code-level separation, not a "don't know this" line).
     */
    private fun decideIntents(t: PendingTurn): String {
        // stories older than the Knows check get it here once, before anyone decides from a leaky file
        runCatching { checkKnows() }
        val s = state.scene()
        val cast = state.characterNames().toSet()
        val people = s.present.filter { it in cast }
        if (people.isEmpty()) return "nobody but the player"
        val turns = turnsInChapter(s.chapter)
        // background happenings, so what the player does is one thing among several, not the only stimulus
        // three new background things a turn; some last a few turns (rain), and none comes back right away
        val background = Ambience.parse(prompts.config("ambience.md").orEmpty(), dice.random)
            .draw(s.place, s.turn, 3, s.ambienceGoing, s.ambienceResting)
        state.save(s)
        // the player's message never reaches them as such: only what an onlooker could notice of it, unlabelled
        val around = (background + listOfNotNull(t.observable.takeIf { it.isNotBlank() })).shuffled(dice.random)
        val effect = t.roll?.let {
            "If something just said or done around you was meant to fool, persuade or slip past someone here, the dice say it was a " +
                "${it.outcome.label} (success: it works on whoever it was aimed at; failure: they see through it or notice)."
        } ?: ""
        val replies = all(people.map { name -> {
            val o = runCatching { json(call("intent", Models.CHARACTERS, Prompts.fill(prompts.prompt("intent"), mapOf(
                "name" to name, "file" to state.character(name).orEmpty(), "place" to s.place,
                "present" to s.present.joinToString().ifEmpty { "nobody else" },
                "memory" to state.memoryOf(name, s.present + s.place).joinToString("\n") { memoryLine(it) }.ifEmpty { "nothing yet" },
                "recent" to sceneSoFar(turns, name, s.place), "around" to around.joinToString("\n") { "- $it" }.ifEmpty { "- nothing in particular" },
                "attempt" to effect,
            )), listOf(Msg(Role.USER, "What does $name do next?")), schema = INTENT_SCHEMA, maxTokens = 3000)) }.getOrNull()
            // a failed call leaves that person to the writer's judgement rather than failing the turn
            if (o == null) "- $name: (undecided)" else
                "- $name: notices: ${o.optString("notices")}; wants: ${o.optString("wants")}; does: ${o.optString("does")}"
        } })
        return (if (background.isEmpty()) "" else "Going on around them (use what fits): ${background.joinToString(" / ")}\n") + replies.joinToString("\n")
    }

    /**
     * What [name] saw and heard of this chapter, word for word: the passages they were there for, each led by
     * what could be seen or heard of the player's character that turn (never the raw message). Without this a
     * character only has the record's one-line summaries and forgets what was said two turns ago.
     */
    private fun sceneSoFar(turns: List<JSONObject>, name: String, place: String, keep: Int = 10): String {
        val mine = turns.filter { t ->
            val present = t.optJSONArray("present")
            // turns logged before presence was kept: those at this place
            if (present == null) t.optString("place", place) == place else (0 until present.length()).any { present.optString(it) == name }
        }.takeLast(keep)
        if (mine.isEmpty()) return "(the scene is just starting)"
        return mine.joinToString("\n\n") { t ->
            val seen = t.optString("observable")
            (if (seen.isBlank()) "" else "[the player's character: $seen]\n") + t.optString("passage")
        }
    }

    /** The popup's one-line meaning of a word in its sentence. */
    fun meaning(word: String, sentence: String, onText: ((String) -> Unit)? = null): String =
        call("meaning", Models.CHECKS, Prompts.fill(prompts.prompt("meaning"), mapOf("word" to word, "sentence" to sentence)),
            listOf(Msg(Role.USER, "Meaning?")), maxTokens = 80, quiet = true, onText = onText).trim()

    // ------------------------------------------------------------------ helpers

    private fun memoryLine(e: StoryState.Event) =
        "- " + (if (e.turn < 0) "(before the story) " else "(turn ${e.turn}, ${e.place}) ") + e.text

    /** The record for the people in a scene and the player: what any of them perceived (writer and checker). */
    private fun sceneRecord(s: StoryState.Scene) =
        state.recordFor(s.present + StoryState.PLAYER, s.present + s.place).joinToString("\n") { e ->
            memoryLine(e) + "  [perceived by: ${e.witnesses.joinToString { if (it == StoryState.PLAYER) "the player" else it }}]"
        }.ifEmpty { "nothing yet" }

    /** What other people can see of the player's character (falls back to the full text for older stories). */
    private fun playerPublic() = File(dir, "state/player_public.md").takeIf { it.exists() }?.readText()?.trim().orEmpty().ifEmpty { playerText() }

    private fun playerText() = File(dir, "state/player.md").takeIf { it.exists() }?.readText()?.trim().orEmpty().ifEmpty { "the protagonist" }

    private fun sceneSummary(s: StoryState.Scene) =
        "Place: ${s.place}. Present: ${s.present.joinToString()}. Beat: ${s.beats.firstOrNull { !it.done }?.goal ?: "none"}.\n" +
            "Last passage: " + turnsInChapter(s.chapter).lastOrNull()?.optString("passage").orEmpty()

    /** The one-line people; [here] marks who is in the scene. They know only ordinary things and what they see. */
    private fun extrasBlock(here: Set<String>? = null): String {
        val all = state.extras()
        if (all.isEmpty()) return ""
        // a long game meets many: those here, and the latest dozen
        val keep = all.keys.toList().takeLast(12).toSet() + (here ?: state.scene().present.toSet())
        val x = if (all.size <= 15) all else all.filterKeys { it in keep }
        return "\n\n#### Others (one line each: no secrets, they know only ordinary local things and what happens in front of them)\n" +
            x.entries.joinToString("\n") { (n, l) -> "- $n: $l" + if (here != null && n !in here) " (not here)" else "" }
    }

    private fun records(): Map<String, String> = mapOf(
        "plot" to prompts.config("plot.md").orEmpty(),
        "world" to state.world(),
        "characters" to castBlock() + extrasBlock(),
        "secrets" to boundedSecrets().joinToString("\n") { "- ${it.id}: ${it.text} [knows: ${it.knows.joinToString()}]" }.ifEmpty { "none" },
        "consequences" to openConsequences().joinToString("\n") { "- ${it.id}: ${it.text}" }.ifEmpty { "none" },
        "saga" to (saga() ?: "(the story has just begun)"),
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
            "type":"object","additionalProperties":false,"required":["wrong","right","type","reason","key","level"],"properties":{
            "wrong":{"type":"string"},"right":{"type":"string"},"level":{"type":"string","enum":["error","unnatural","nitpick"]},
            "type":{"type":"string","enum":["conjugation","particle","word choice","grammar pattern","spelling","other"]},
            "reason":{"type":"string"},"key":{"type":"string"}}}}}}"""
        val ADJUDICATOR_SCHEMA = """{"type":"object","additionalProperties":false,"required":["roll","action","difficulty","stakes","observable"],"properties":{
            "roll":{"type":"boolean"},"action":{"type":"string"},"difficulty":{"type":"integer"},"stakes":{"type":"string"},"observable":{"type":"string"}}}"""
        val JUDGE_SCHEMA = """{"type":"object","additionalProperties":false,"required":["violations"],"properties":{"violations":{"type":"array","items":{
            "type":"object","additionalProperties":false,"required":["rule","quote","fix"],"properties":{
            "rule":{"type":"string","enum":["player_voice","echo","explains","no_handoff","npc_knowledge","motive","contradiction"]},"quote":{"type":"string"},"fix":{"type":"string"}}}}}}"""
        val INTENT_SCHEMA = """{"type":"object","additionalProperties":false,"required":["notices","wants","does"],"properties":{
            "notices":{"type":"string"},"wants":{"type":"string"},"does":{"type":"string"}}}"""
        val LEAK_SCHEMA = """{"type":"object","additionalProperties":false,"required":["guesses"],"properties":{"guesses":{"type":"array","items":{"type":"string"}}}}"""
        val DIRECTOR_SCHEMA = """{"type":"object","additionalProperties":false,"required":["beats","opening"],"properties":{
            "beats":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["goal","place","characters","pressure"],"properties":{
              "goal":{"type":"string"},"place":{"type":"string"},"characters":{"type":"array","items":{"type":"string"}},"pressure":{"type":"string"}}}},
            "opening":{"type":"string"}}}"""
        val STYLES_SCHEMA = """{"type":"object","additionalProperties":false,"required":["styles"],"properties":{"styles":{"type":"array","items":{
            "type":"object","additionalProperties":false,"required":["name","line","passage"],"properties":{
            "name":{"type":"string"},"line":{"type":"string"},"passage":{"type":"string"}}}}}}"""
        /** What to say again at the end of the packet when a check caught this recently (rule → reminder). */
        val SLIPS = mapOf(
            "player_voice" to "The player's character says, thinks, decides and does only what the player wrote. No lines, nods or glances for them.",
            "echo" to "Don't repeat or paraphrase the player's message; go on from it.",
            "explains" to "Don't explain what a line meant or how it landed; show what people do next.",
            "no_handoff" to "End on something the player has to answer: a question, a demand, a deadline, someone waiting.",
            "npc_knowledge" to "People know only what they perceived (the record). No hints at hidden facts about the player's character.",
            "motive" to "People keep their own goals and attitude; a change needs a reason shown on the page. Ordinary behaviour isn't suspicious, and an answer given is taken as given.",
            "contradiction" to "Nobody contradicts what's established about the player's character or the story so far.",
            "banned phrase" to "Avoid stock phrases (see the style rules); say the plain concrete thing.",
            "unknown words" to "Stay inside a 5,000-word vocabulary plus the review words; prefer the everyday word.",
            "leak" to "Don't make review words guessable from the sentence around them.",
        )

        /** Sparks for "Surprise me": genre and a hook, the specifics left to setup. */
        val SPARKS = listOf(
            "Fantasy: a dragon's egg hatches in the kitchen of a monastery that swore never to keep one.",
            "Fantasy: the player is an apprentice to a mapmaker whose maps change the land they draw.",
            "Science fiction: a mining station on a moon where the miners have started dreaming the same dream.",
            "Science fiction: a smuggler's ship picks up a stowaway who claims to be the heir to a dead empire.",
            "Horror: the night porter's first week at a hotel where some guests never check out.",
            "Horror: a village where the dead come back for one night each winter, and this year one of them stays.",
            "Mystery: a locked-room death aboard a sleeper train crossing the mountains in a snowstorm.",
            "Noir with magic: a private investigator in a city where memories are bought and sold.",
            "Myth: a hero summoned by a god who has been forgotten by everyone but the hero.",
            "Pirates: a crew that stole the wrong ship, and whatever is in its hold wants to go home.",
            "Gothic: a governess arrives at a cliff-top house where the portraits change overnight.",
            "Cyberpunk: a courier carrying data in their own head that three corporations want back.",
            "Post-apocalyptic: the last working radio tower, and a voice on it no one can place.",
            "Heist: a crew planning to rob a sorcerer's vault on the one night the wards are down.",
            "Wild West with the weird: a frontier town where the new railroad is digging up something old.",
            "Fairy tale: a deal with the fae that the player's family made three generations ago comes due.",
            "Space opera: a diplomat on a generation ship where two factions are one vote from war.",
            "Supernatural: a medium who has always faked it, until a client's ghost actually answers.",
            "Historical adventure: Edo-period Japan, a ronin hired to escort a girl who is more than she seems.",
            "Yokai: a small mountain town where a fox spirit runs the only inn, and a guest has gone missing.",
            "Survival: a research team cut off in the Antarctic winter, and the ice core holds something alive.",
            "Steampunk: an airship courier race across a continent of floating cities, with sabotage aboard.",
            "Urban fantasy: the player can see the hidden market under Tokyo, and someone there knows it.",
            "Sword and sorcery: a mercenary company hired to guard a caravan carrying a sealed coffin.",
            "School mystery: the student council of a mountain boarding school, and a club room that shouldn't exist.",
            "Slice of life with a secret: a new job at a seaside café whose regulars all seem to be waiting for something.",
        )

        val REMEMBER_SCHEMA = """{"type":"object","additionalProperties":false,"required":["remembers"],"properties":{"remembers":{"type":"string"}}}"""
        val KNOWS_SCHEMA = """{"type":"object","additionalProperties":false,"required":["knows"],"properties":{"knows":{"type":"string"}}}"""

        val SETUP_SCHEMA = """{"type":"object","additionalProperties":false,"required":["title","world","player","history","player_public","characters","extras","secrets","clocks","complications","ambience","opening"],"properties":{
            "title":{"type":"string"},
            "world":{"type":"string"},"player":{"type":"string"},"history":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["text","witnesses"],"properties":{"text":{"type":"string"},"witnesses":{"type":"array","items":{"type":"string"}}}}},"player_public":{"type":"string"},
            "characters":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["name","file"],"properties":{"name":{"type":"string"},"file":{"type":"string"}}}},
            "extras":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["name","line"],"properties":{"name":{"type":"string"},"line":{"type":"string"}}}},
            "secrets":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["text","knows"],"properties":{"text":{"type":"string"},"knows":{"type":"array","items":{"type":"string"}}}}},
            "clocks":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["name","size","when_full"],"properties":{"name":{"type":"string"},"size":{"type":"integer"},"when_full":{"type":"string"}}}},
            "complications":{"type":"array","items":{"type":"string"}},
            "ambience":{"type":"array","items":{"type":"string"}},
            "opening":{"type":"object","additionalProperties":false,"required":["place","present","situation"],"properties":{"place":{"type":"string"},"present":{"type":"array","items":{"type":"string"}},"situation":{"type":"string"}}}}}"""
    }
}
