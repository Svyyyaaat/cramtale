package com.cramtale.core.story

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
    val draft get() = drafts[current]
}

/** Measures a passage against what the reader knows (backed by the matcher in the app). */
fun interface UnknownWords {
    /** Unknown share of counted words, and the unknown lemmas. */
    fun check(text: String): Pair<Double, List<String>>
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
    )

    val prompts = Prompts(dir)
    val state = StoryState(dir)
    private val logFile = File(dir, "log/turns.jsonl")
    private val chaptersDir = File(dir, "state/chapters")
    var pending: PendingTurn? = null
        private set

    init {
        prompts.installDefaults(Prompts.PROMPTS + Prompts.CONFIG)
    }

    // ------------------------------------------------------------------ calls

    private fun call(purpose: String, model: String, system: String, messages: List<Msg>, schema: String? = null,
                     maxTokens: Int = 4096, cacheSystem: Boolean = false, extraSystem: List<Msg> = emptyList()): String {
        val req = LlmRequest(
            purpose = purpose, model = model,
            system = listOf(Msg(Role.SYSTEM, system, cache = cacheSystem)) + extraSystem,
            messages = messages, maxTokens = maxTokens, effort = "low", jsonSchema = schema,
            thinking = model != Models.CHECKS,
        )
        val resp = llm.call(req)
        costs.record(session, req, resp)
        return resp.text
    }

    private fun json(text: String) = JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())

    // ------------------------------------------------------------------ setup and style

    fun isSetUp() = File(dir, "state/world.md").exists()

    /** Creates the world, cast, secrets, clocks and opening situation from the plot taste. */
    fun setup(seed: String = "") {
        val text = call("setup", Models.WRITER, Prompts.fill(prompts.prompt("setup"), mapOf(
            "plot" to prompts.config("plot.md"), "seed" to if (seed.isBlank()) "" else "Seed from the player: $seed",
        )), listOf(Msg(Role.USER, "Create the game.")), schema = SETUP_SCHEMA, maxTokens = 8000)
        val o = json(text)
        val stateDir = File(dir, "state").apply { mkdirs() }
        val clocks = o.optJSONArray("clocks")
        File(stateDir, "world.md").writeText(o.getString("world").trimEnd() + "\n\n## Clocks\n" +
            (0 until (clocks?.length() ?: 0)).joinToString("\n") { i ->
                val c = clocks!!.getJSONObject(i)
                "- ${c.getString("name")}: 0/${c.optInt("size", 6)} — when full: ${c.optString("when_full")}"
            } + "\n")
        File(stateDir, "player.md").writeText(o.optString("player") + "\n")
        val chars = o.getJSONArray("characters")
        for (i in 0 until chars.length()) chars.getJSONObject(i).let { state.writeCharacter(it.getString("name"), it.getString("file")) }
        val secrets = o.optJSONArray("secrets")
        File(stateDir, "secrets.md").writeText("# Secrets (game master only)\n\n" + (0 until (secrets?.length() ?: 0)).joinToString("\n") { i ->
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
        val text = call("style_picker", Models.WRITER, Prompts.fill(prompts.prompt("style_picker"), mapOf("scene" to scene)),
            listOf(Msg(Role.USER, "Write the four versions.")), schema = STYLES_SCHEMA, maxTokens = 6000)
        val a = json(text).getJSONArray("styles")
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { StyleOption(it.getString("name"), it.getString("line"), it.getString("passage")) } }
    }

    fun pickStyle(o: StyleOption) {
        File(dir, "config").mkdirs()
        File(dir, "config/style_sample.md").writeText(o.passage.trim() + "\n")
        File(dir, "config/style_line.txt").writeText(o.line.trim() + "\n")
    }

    fun hasStyle() = !prompts.config("style_sample.md").isNullOrBlank()

    // ------------------------------------------------------------------ sessions

    /** Between sessions the world moves (off-screen), then the director plans three beats. */
    fun startSession(pool: DuePool): List<String> {
        val scene = state.scene()
        val log = ArrayList<String>()
        if (scene.session > 0 && scene.turn > 0) {
            val text = call("offscreen", Models.WRITER, Prompts.fill(prompts.prompt("offscreen"), records()),
                listOf(Msg(Role.USER, "What happened off-screen?")), schema = StateOp.SCHEMA)
            log += state.apply(StateOp.parse(text), offscreen = true)
        }
        val s = state.scene()
        val weak = (pool.weakPoints + pool.grammar.map { "${it.id} (${it.meaning})" }).distinct()
        val openingRule = if (weak.isEmpty()) "" else
            "The player is practising these points: ${weak.joinToString("; ")}. You may plan ONE beat where one of them " +
                "would come up naturally in what the player's character has to say or do, only if it fits a beat you would " +
                "plan anyway. Never make it a quiz or a prompt to use it. Usually plan none."
        val text = call("director", Models.WRITER, Prompts.fill(prompts.prompt("director"), records() + mapOf(
            "opening_rule" to openingRule,
            "situation" to "Place: ${s.place}. Present: ${s.present.joinToString()}. Turn ${s.turn}. " +
                "Unfinished beats: ${s.beats.filter { !it.done }.joinToString("; ") { it.goal }.ifEmpty { "none" }}",
        )), listOf(Msg(Role.USER, "Plan the session.")), schema = DIRECTOR_SCHEMA)
        val o = json(text)
        val beats = o.getJSONArray("beats")
        s.beats = (0 until beats.length()).map { i ->
            val b = beats.getJSONObject(i)
            StoryState.Beat(b.getString("goal"), b.optString("place"),
                (0 until (b.optJSONArray("characters")?.length() ?: 0)).map { b.getJSONArray("characters").getString(it) }, b.optString("pressure"))
        }.toMutableList()
        s.session += 1
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
        val scene = state.scene()
        val corrections = if (message.isNullOrBlank()) emptyList() else checkMistakes(message, knownMistakeKeys)
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
        turn.drafts += write(turn)
        pending = turn
        return turn
    }

    /** A fresh version of the pending passage (taps on earlier versions still count). */
    fun reroll(): Draft {
        val t = pending ?: error("no pending turn")
        val d = write(t)
        t.drafts += d
        t.current = t.drafts.size - 1
        return d
    }

    /** The player edited the passage text: recheck it without a model call. */
    fun edit(text: String): Draft {
        val t = pending ?: error("no pending turn")
        val d = Draft(text, t.draft.used.filter { it.second in text }, t.draft.grammar, cheapChecks(text), t.draft.rewritten)
        t.drafts += d
        t.current = t.drafts.size - 1
        return d
    }

    /** The passage is final: the scribe records what changed, the turn is logged, chapters roll over. */
    fun commit(): List<String> {
        val t = pending ?: error("no pending turn")
        val s = state.scene()
        val ops = StateOp.parse(call("scribe", Models.CHECKS, Prompts.fill(prompts.prompt("scribe"), records() + mapOf(
            "present" to s.present.joinToString(), "place" to s.place,
            "beat" to (s.beats.firstOrNull { !it.done }?.goal ?: "none"),
            "clocks" to state.clocks().joinToString("; ") { "${it.name} ${it.filled}/${it.size}" },
            "message" to (t.player ?: "(the player let the scene continue)"), "passage" to t.draft.passage,
        )), listOf(Msg(Role.USER, "List the changes.")), schema = StateOp.SCHEMA, maxTokens = 1500))
        val applied = state.apply(ops)
        logFile.parentFile.mkdirs()
        logFile.appendText(JSONObject()
            .put("turn", t.turn).put("chapter", s.chapter).put("player", t.player ?: "")
            .put("passage", t.draft.passage).put("roll", t.roll?.toString() ?: "")
            .put("corrections", JSONArray(t.corrections.map { JSONObject().put("wrong", it.wrong).put("right", it.right).put("key", it.key) }))
            .put("checks", JSONArray(t.draft.checks.map { "${it.name}: ${if (it.passed) "ok" else it.detail}" }))
            .put("versions", t.drafts.size).put("ops", JSONArray(applied))
            .toString() + "\n")
        val s2 = state.scene()
        s2.turn += 1
        state.save(s2)
        pending = null
        if (turnsInChapter(s2.chapter).size >= settings.chapterTurns) return applied + newChapter()
        return applied
    }

    /** A summary closes the chapter; the next one starts from it, keeping the writer's context short. */
    fun newChapter(): String {
        val s = state.scene()
        val text = turnsInChapter(s.chapter).joinToString("\n\n") { o ->
            (o.optString("player").takeIf { it.isNotBlank() }?.let { "Player: $it\n" } ?: "") + o.getString("passage")
        }
        val summary = call("summary", Models.WRITER, Prompts.fill(prompts.prompt("summary"), mapOf("chapter" to text)),
            listOf(Msg(Role.USER, "Summarize.")), maxTokens = 1200)
        chaptersDir.mkdirs()
        File(chaptersDir, "${s.chapter}.md").writeText(summary.trim() + "\n")
        s.chapter += 1
        state.save(s)
        return "chapter ${s.chapter} begins"
    }

    // ------------------------------------------------------------------ the writer and its checks

    private fun turnsInChapter(chapter: Int): List<JSONObject> =
        if (!logFile.exists()) emptyList() else logFile.readLines().filter { it.isNotBlank() }.map(::JSONObject).filter { it.optInt("chapter") == chapter }

    private fun writerSystem(): List<Msg> {
        val fixed = Prompts.fill(prompts.prompt("writer"), mapOf(
            "plot" to prompts.config("plot.md"), "world" to state.world() + "\n\n## The player's character\n" + File(dir, "state/player.md").let { if (it.exists()) it.readText() else "" },
            "style_sample" to prompts.config("style_sample.md"),
        ))
        val prev = File(chaptersDir, "${state.scene().chapter - 1}.md")
        val summary = if (prev.exists()) listOf(Msg(Role.SYSTEM, "## The story so far (previous chapter)\n" + prev.readText(), cache = true)) else emptyList()
        return listOf(Msg(Role.SYSTEM, fixed, cache = true)) + summary
    }

    private fun writerMessages(t: PendingTurn, packet: String): List<Msg> {
        val history = turnsInChapter(state.scene().chapter).flatMap { o ->
            listOf(Msg(Role.USER, o.optString("player").ifBlank { "（続けて）" }), Msg(Role.ASSISTANT, o.getString("passage")))
        }
        val now = t.player?.takeIf { it.isNotBlank() } ?: if (history.isEmpty()) "（物語の始まり）" else "（続けて）"
        return history + Msg(Role.USER, now) + Msg(Role.SYSTEM, packet)
    }

    private fun turnPacket(t: PendingTurn): String {
        val s = state.scene()
        val beat = s.beats.firstOrNull { !it.done }
        val names = s.present.toSet()
        val characters = s.present.mapNotNull { n -> state.character(n)?.let { "#### $n\n$it" } } +
            (state.characterNames() - names).map { n -> "- $n (not here)" }
        val tags = names + s.place
        return Prompts.fill(prompts.prompt("turn"), mapOf(
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
            "chronicle" to state.chronicle().filter { e -> e.tags.any { it in tags } }.takeLast(8).joinToString("\n") { "- ${it.text}" }.ifEmpty { "nothing yet" },
            "due_words" to t.pool.words.joinToString("、") { it.word }.ifEmpty { "none this turn" },
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
        val packet = turnPacket(t)
        val system = writerSystem()
        val messages = writerMessages(t, packet)
        val first = parseDraft(call("writer", Models.WRITER, system.first().text, messages, schema = WRITER_SCHEMA,
            maxTokens = 4000, cacheSystem = true, extraSystem = system.drop(1)), t)
        val problems = problems(first, t)
        if (problems.isEmpty()) return first.copy(checks = first.checks + CheckResult("all checks", true, ""))
        val raw = JSONObject().put("passage", first.passage).put("used", JSONArray(first.used.map { JSONObject().put("word", it.first).put("form", it.second) }))
            .put("grammar", JSONArray(first.grammar)).toString()
        val fixed = parseDraft(call("rewrite", Models.WRITER, system.first().text,
            messages + Msg(Role.ASSISTANT, raw) + Msg(Role.USER, Prompts.fill(prompts.prompt("rewrite"), mapOf(
                "problems" to problems.joinToString("\n") { "- ${it.detail}" }))),
            schema = WRITER_SCHEMA, maxTokens = 4000, cacheSystem = true, extraSystem = system.drop(1)), t)
        // one retry, then accept: what's still wrong is logged, not retried again
        return fixed.copy(checks = problems.map { it.copy(detail = "fixed by rewrite: " + it.detail) } + cheapChecks(fixed.passage), rewritten = true)
    }

    private fun parseDraft(text: String, t: PendingTurn): Draft {
        val o = json(text)
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
        unknownWords?.check(passage)?.let { (rate, words) ->
            out += CheckResult("unknown words", rate <= settings.unknownLimit, "%.1f%% unknown: %s".format(rate * 100, words.joinToString("、")))
        }
        val hits = bannedHits(passage)
        out += CheckResult("banned phrases", hits.isEmpty(), hits.joinToString(" / "))
        return out
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
        unknownWords?.check(d.passage)?.let { (rate, words) ->
            if (rate > settings.unknownLimit) out += CheckResult("unknown words", false,
                "These words are beyond a reader with about 5,000 words: ${words.joinToString("、")}. Use plainer words.")
        }
        bannedHits(d.passage).forEach { out += CheckResult("banned phrase", false, "Rewrite this sentence without its stock phrase: $it") }
        leaks(d, t).forEach { out += CheckResult("leak", false, "This sentence makes the word 「${it.second}」 too easy to guess from context; keep the word, make the context less leading: ${it.first}") }
        val judged = json(call("judge", Models.CHECKS, Prompts.fill(prompts.prompt("judge"), mapOf(
            "message" to (t.player ?: "(none: the scene continues)"), "passage" to d.passage,
        )), listOf(Msg(Role.USER, "Check.")), schema = JUDGE_SCHEMA, maxTokens = 800))
        judged.optJSONArray("violations")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { v ->
                out += CheckResult(v.optString("rule"), false, "${v.optString("rule")}: 「${v.optString("quote")}」. ${v.optString("fix")}")
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
            .filter { it.wrong in message } // the span must be real text from the message
    }

    /** The popup's one-line meaning of a word in its sentence. */
    fun meaning(word: String, sentence: String): String =
        call("meaning", Models.CHECKS, Prompts.fill(prompts.prompt("meaning"), mapOf("word" to word, "sentence" to sentence)),
            listOf(Msg(Role.USER, "Meaning?")), maxTokens = 120).trim()

    // ------------------------------------------------------------------ helpers

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

    companion object {
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
