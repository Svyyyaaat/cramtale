package com.cramtale.core.story

import com.cramtale.core.writeAtomic
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything the story knows, in plain files under one directory (PLAN.md §6.2). Code owns it:
 * models only propose changes ([StateOp]), which [apply] checks before writing.
 *
 *   state/characters/c1.md…     "# name" then goals, limits, secrets, what they want, attitude (free text)
 *   state/world.md               places, factions; "## Clocks" lines: "- name: 3/6 — when full: …"
 *   state/secrets.md             "- S1: text [knows: 佐伯, 北村]"
 *   state/consequences.md        "- C1: text [due: turn 12] [open]"
 *   state/chronicle.md           "- T7: text [tags: 倉庫, 佐伯]"
 *   state/scene.json             code-only: turn, chapter, place, who's present, beats, used complications
 */
class StoryState(val dir: File) {
    private val stateDir get() = File(dir, "state")
    private val charDir get() = File(stateDir, "characters")

    data class Clock(val name: String, val filled: Int, val size: Int, val whenFull: String)
    data class Secret(val id: String, val text: String, val knows: Set<String>)
    data class Consequence(val id: String, val text: String, val dueTurn: Int?, val open: Boolean)
    data class ChronicleEntry(val id: String, val text: String, val tags: Set<String>)

    data class Beat(val goal: String, val place: String, val characters: List<String>, val pressure: String, var done: Boolean = false)

    data class Scene(
        var turn: Int = 0,
        var chapter: Int = 1,
        var session: Int = 0,
        var place: String = "",
        var present: MutableList<String> = ArrayList(),
        var beats: MutableList<Beat> = ArrayList(),
        var complication: String? = null,
        /** The beat the complication was drawn for; a new beat draws a new one. */
        var complicationFor: String? = null,
        var usedComplications: MutableList<String> = ArrayList(),
        /** Grammar or weak-point openings the director planned this session (at most one). */
        var openingsThisSession: Int = 0,
        /** When the player last did anything (ms): a long gap ends the session. */
        var lastActive: Long = 0,
        /** The session whose off-screen moves are done (so a retry after a failed plan doesn't move the world twice). */
        var movedForSession: Int = 0,
        /** A new session was decided (the player came back after a break) but its plan isn't made yet. */
        var sessionDue: Boolean = false,
    )

    // ---------- characters (free text) ----------

    // files are c1.md, c2.md…; the name is the first line ("# 佐伯"), so file names stay ASCII
    private fun characterFiles(): Map<String, File> = charDir.listFiles { f -> f.extension == "md" }.orEmpty()
        .associateBy { f -> f.useLines { it.firstOrNull() }.orEmpty().removePrefix("#").trim() }

    fun characterNames(): List<String> = characterFiles().keys.sorted()

    /** The cast's names as they appear in text: タム and グエン in "グエン・ヴァン・タム", 村井 in "村井 早苗 (clerk)". */
    fun castNameParts(): Set<String> = characterNames().flatMap { n ->
        val parts = n.split(' ', '　', '・', '(', ')', '（', '）').map { it.trim() }.filter { it.isNotEmpty() }
        val whole = parts.joinToString("")
        // the writer often spells a name in kana (榊 ともえ → さかきともえ): readings count too
        val readings = parts.map(::kanaReading)
        listOf(n, whole, readings.joinToString("")) + parts + readings
    }.map { it.trim() }.filter { it.length >= 2 }.toSet()

    /** A name's reading in hiragana (from the tokenizer); kana stays as it is. */
    private fun kanaReading(s: String): String =
        com.cramtale.core.text.Tokenizer.tokenize(s).joinToString("") { t -> t.reading.ifBlank { t.surface } }
            .map { if (it in '\u30A1'..'\u30F6') it - 0x60 else it }.joinToString("")

    /** The character's file without its name line. */
    fun character(name: String): String? = characterFiles()[name]?.readLines()?.drop(1)?.joinToString("\n")?.trim()

    fun writeCharacter(name: String, text: String) {
        charDir.mkdirs()
        val f = characterFiles()[name] ?: File(charDir, "c${(charDir.list()?.size ?: 0) + 1}.md")
        f.writeAtomic("# $name\n" + text.trim() + "\n")
    }

    // ---------- world and clocks ----------

    private val clockLine = Regex("^- (.+?): (\\d+)/(\\d+)(?: — when full: (.*))?$")

    fun world(): String = File(stateDir, "world.md").takeIf { it.exists() }?.readText().orEmpty()

    fun clocks(): List<Clock> = world().lines().mapNotNull { l ->
        clockLine.find(l.trim())?.let { m -> Clock(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4]) }
    }

    private fun writeClock(c: Clock) {
        val f = File(stateDir, "world.md")
        val lines = world().lines().toMutableList()
        val line = "- ${c.name}: ${c.filled}/${c.size}" + if (c.whenFull.isNotEmpty()) " — when full: ${c.whenFull}" else ""
        val i = lines.indexOfFirst { clockLine.find(it.trim())?.groupValues?.get(1) == c.name }
        if (i >= 0) lines[i] = line else {
            if (lines.none { it.trim() == "## Clocks" }) lines += listOf("", "## Clocks")
            lines += line
        }
        f.parentFile.mkdirs()
        f.writeAtomic(lines.joinToString("\n").trimEnd() + "\n")
    }

    // ---------- secrets, consequences, chronicle (one item per line) ----------

    private fun listFile(name: String) = File(stateDir, name)
    private fun items(name: String) = listFile(name).takeIf { it.exists() }?.readLines()?.filter { it.trimStart().startsWith("- ") }.orEmpty()
    private val itemLine = Regex("^- (\\w+): (.*?)((?: \\[[^\\]]*\\])*)$")
    private val bracket = Regex("\\[([^\\]:]+)(?::\\s*([^\\]]*))?\\]")

    private fun parseItem(line: String): Triple<String, String, Map<String, String>>? {
        val m = itemLine.find(line.trim()) ?: return null
        val attrs = bracket.findAll(m.groupValues[3]).associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
        return Triple(m.groupValues[1], m.groupValues[2].trim(), attrs)
    }

    fun secrets(): List<Secret> = items("secrets.md").mapNotNull { parseItem(it) }.map { (id, text, a) ->
        Secret(id, text, a["knows"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }

    fun consequences(): List<Consequence> = items("consequences.md").mapNotNull { parseItem(it) }.map { (id, text, a) ->
        Consequence(id, text, a["due"]?.removePrefix("turn")?.trim()?.toIntOrNull(), "closed" !in a)
    }

    fun chronicle(): List<ChronicleEntry> = items("chronicle.md").mapNotNull { parseItem(it) }.map { (id, text, a) ->
        ChronicleEntry(id, text, a["tags"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }

    private fun writeSecrets(list: List<Secret>) = writeItems("secrets.md", "# Secrets (game master only)",
        list.map { "- ${it.id}: ${it.text} [knows: ${it.knows.joinToString(", ")}]" })

    private fun writeConsequences(list: List<Consequence>) = writeItems("consequences.md", "# Pending consequences and debts",
        list.map { "- ${it.id}: ${it.text}" + (it.dueTurn?.let { t -> " [due: turn $t]" } ?: "") + if (it.open) " [open]" else " [closed]" })

    private fun writeChronicle(list: List<ChronicleEntry>) = writeItems("chronicle.md", "# Chronicle: outcomes that still matter",
        list.map { "- ${it.id}: ${it.text} [tags: ${it.tags.joinToString(", ")}]" })

    private fun writeItems(name: String, title: String, lines: List<String>) {
        stateDir.mkdirs()
        listFile(name).writeAtomic("$title\n\n" + lines.joinToString("\n") + "\n")
    }

    // ---------- scene (code-owned JSON) ----------

    private val sceneFile get() = File(stateDir, "scene.json")

    fun scene(): Scene {
        if (!sceneFile.exists()) return Scene()
        val o = JSONObject(sceneFile.readText())
        return Scene(
            turn = o.optInt("turn"), chapter = o.optInt("chapter", 1), session = o.optInt("session"),
            place = o.optString("place"),
            present = o.optJSONArray("present").strings().toMutableList(),
            beats = o.optJSONArray("beats")?.let { a ->
                (0 until a.length()).map { i ->
                    val b = a.getJSONObject(i)
                    Beat(b.getString("goal"), b.optString("place"), b.optJSONArray("characters").strings(), b.optString("pressure"), b.optBoolean("done"))
                }
            }.orEmpty().toMutableList(),
            complication = o.optString("complication").ifEmpty { null },
            complicationFor = o.optString("complicationFor").ifEmpty { null },
            usedComplications = o.optJSONArray("usedComplications").strings().toMutableList(),
            openingsThisSession = o.optInt("openingsThisSession"),
            lastActive = o.optLong("lastActive"), movedForSession = o.optInt("movedForSession"),
            sessionDue = o.optBoolean("sessionDue"),
        )
    }

    fun save(s: Scene) {
        stateDir.mkdirs()
        val o = JSONObject()
            .put("turn", s.turn).put("chapter", s.chapter).put("session", s.session).put("place", s.place)
            .put("present", JSONArray(s.present))
            .put("beats", JSONArray(s.beats.map {
                JSONObject().put("goal", it.goal).put("place", it.place).put("characters", JSONArray(it.characters))
                    .put("pressure", it.pressure).put("done", it.done)
            }))
            .put("complication", s.complication ?: "")
            .put("complicationFor", s.complicationFor ?: "")
            .put("usedComplications", JSONArray(s.usedComplications))
            .put("openingsThisSession", s.openingsThisSession)
            .put("lastActive", s.lastActive).put("movedForSession", s.movedForSession).put("sessionDue", s.sessionDue)
        sceneFile.writeAtomic(o.toString(2))
    }

    // ---------- applying proposed changes ----------

    /**
     * Applies the ops a scribe or off-screen call proposed, refusing the ones that break the
     * rules. Returns what happened to each, for the log.
     */
    fun apply(ops: List<StateOp>, offscreen: Boolean = false): List<String> {
        val scene = scene()
        val out = ArrayList<String>()
        var secrets = secrets()
        var consequences = consequences()
        var chronicle = chronicle()
        val clockMoves = HashMap<String, Int>()
        for (op in ops) {
            out += when (op) {
                is StateOp.Chronicle -> {
                    val id = "T${scene.turn}" + if (chronicle.any { it.id == "T${scene.turn}" }) "-${chronicle.count { it.id.startsWith("T${scene.turn}") } + 1}" else ""
                    chronicle = chronicle + ChronicleEntry(id, op.text, op.tags.toSet()); "chronicle $id: ${op.text}"
                }
                is StateOp.Drop -> {
                    chronicle = chronicle.filterNot { it.id == op.id }; "dropped ${op.id}"
                }
                is StateOp.Reveal -> {
                    val s = secrets.firstOrNull { it.id == op.secret }
                    when {
                        s == null -> "refused: no secret ${op.secret}"
                        !offscreen && op.to !in scene.present -> "refused: ${op.to} wasn't in the scene to learn ${op.secret}"
                        else -> { secrets = secrets.map { if (it.id == s.id) it.copy(knows = it.knows + op.to) else it }; "${op.to} now knows ${s.id}" }
                    }
                }
                is StateOp.NewSecret -> {
                    val id = "S${(secrets.mapNotNull { it.id.drop(1).toIntOrNull() }.maxOrNull() ?: 0) + 1}"
                    secrets = secrets + Secret(id, op.text, op.knows.toSet()); "secret $id"
                }
                is StateOp.AddConsequence -> {
                    val id = "C${(consequences.mapNotNull { it.id.drop(1).toIntOrNull() }.maxOrNull() ?: 0) + 1}"
                    consequences = consequences + Consequence(id, op.text, op.dueInTurns?.let { scene.turn + it }, true); "consequence $id"
                }
                is StateOp.ResolveConsequence -> {
                    if (consequences.none { it.id == op.id && it.open }) "refused: no open ${op.id}"
                    else { consequences = consequences.map { if (it.id == op.id) it.copy(open = false) else it }; "closed ${op.id}" }
                }
                is StateOp.AdvanceClock -> {
                    val c = clocks().firstOrNull { it.name == op.clock }
                    val moved = clockMoves.getOrDefault(op.clock, 0)
                    when {
                        c == null -> "refused: no clock ${op.clock}"
                        // a clock moves at most one step per turn (two between sessions)
                        moved + op.by > (if (offscreen) 2 else 1) -> "refused: ${op.clock} already moved"
                        else -> {
                            clockMoves[op.clock] = moved + op.by
                            val n = (c.filled + op.by).coerceIn(0, c.size)
                            writeClock(c.copy(filled = n))
                            "${c.name} ${n}/${c.size}" + if (n == c.size) " FULL: ${c.whenFull}" else ""
                        }
                    }
                }
                is StateOp.NewClock -> { writeClock(Clock(op.name, 0, op.size.coerceIn(2, 12), op.whenFull)); "clock ${op.name} 0/${op.size}" }
                is StateOp.Move -> {
                    if (op.place.isNotBlank()) scene.place = op.place
                    scene.present = op.present.toMutableList(); "scene: ${scene.place} with ${scene.present.joinToString()}"
                }
                is StateOp.Character -> {
                    val old = character(op.name)
                    if (old == null) "refused: no character ${op.name}"
                    else { writeCharacter(op.name, old.trimEnd() + "\n- (turn ${scene.turn}) ${op.note}\n"); "${op.name}: ${op.note}" }
                }
                is StateOp.Learns -> {
                    val old = character(op.name)
                    when {
                        old == null -> "refused: no character ${op.name}"
                        // knowledge travels through the scene: only someone there can learn it there
                        !offscreen && op.name !in scene.present -> "refused: ${op.name} wasn't there to learn that"
                        else -> {
                            val body = if ("## Learned" in old) old.trimEnd() else old.trimEnd() + "\n\n## Learned"
                            writeCharacter(op.name, body + "\n- (turn ${scene.turn}) ${op.fact}\n"); "${op.name} learned: ${op.fact}"
                        }
                    }
                }
                is StateOp.PlayerFact -> {
                    val f = java.io.File(stateDir, "player.md")
                    val old = if (f.exists()) f.readText().trimEnd() else ""
                    val body = if ("## Established in the story" in old) old else "$old\n\n## Established in the story"
                    f.writeAtomic("$body\n- (turn ${scene.turn}) ${op.fact}\n"); "about the player: ${op.fact}"
                }
                is StateOp.BeatDone -> {
                    scene.beats.firstOrNull { !it.done }?.done = true; "beat done"
                }
            }
        }
        writeSecrets(secrets)
        writeConsequences(consequences)
        writeChronicle(chronicle)
        save(scene)
        return out
    }

    companion object {
        internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
    }
}

/** A change a model proposes to the story state; [StoryState.apply] decides whether it happens. */
sealed interface StateOp {
    data class Chronicle(val text: String, val tags: List<String>) : StateOp
    data class Drop(val id: String) : StateOp
    data class Reveal(val secret: String, val to: String) : StateOp
    data class NewSecret(val text: String, val knows: List<String>) : StateOp
    data class AddConsequence(val text: String, val dueInTurns: Int?) : StateOp
    data class ResolveConsequence(val id: String) : StateOp
    data class AdvanceClock(val clock: String, val by: Int) : StateOp
    data class NewClock(val name: String, val size: Int, val whenFull: String) : StateOp
    data class Move(val place: String, val present: List<String>) : StateOp
    data class Character(val name: String, val note: String) : StateOp
    /** Someone present saw, heard or was told something: it goes into what they know. */
    data class Learns(val name: String, val fact: String) : StateOp
    /** Something established about the player's character: it goes into their sheet, so later passages agree with it. */
    data class PlayerFact(val fact: String) : StateOp
    data object BeatDone : StateOp

    companion object {
        /**
         * The JSON the scribe and off-screen prompts return: {"ops": [{"op", "a", "b", "list", "n"}]},
         * every field present (structured output rejects a schema with many optional fields):
         *   chronicle: a = text, list = tags        drop: a = id
         *   reveal: a = secret id, b = who           new_secret: a = text, list = who knows
         *   consequence: a = text, n = due in turns (0 = no date)   resolve: a = id
         *   clock: a = clock name, n = steps         new_clock: a = name, b = when full, n = size
         *   move: a = place, list = people present   character: a = name, b = note
         *   beat_done
         */
        fun parse(json: String): List<StateOp> {
            val a = JSONObject(json).optJSONArray("ops") ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val x = o.optString("a")
                val y = o.optString("b")
                val list = o.optJSONArray("list").strings()
                val n = o.optInt("n")
                runCatching {
                    when (o.getString("op")) {
                        "chronicle" -> Chronicle(x, list)
                        "drop" -> Drop(x)
                        "reveal" -> Reveal(x, y)
                        "new_secret" -> NewSecret(x, list)
                        "consequence" -> AddConsequence(x, n.takeIf { it > 0 })
                        "resolve" -> ResolveConsequence(x)
                        "clock" -> AdvanceClock(x, n.coerceIn(-1, 2).takeIf { it != 0 } ?: 1)
                        "new_clock" -> NewClock(x, if (n > 0) n else 6, y)
                        "move" -> Move(x, list)
                        "character" -> Character(x, y)
                        "learns" -> Learns(x, y)
                        "player_fact" -> PlayerFact(x)
                        "beat_done" -> BeatDone
                        else -> null
                    }
                }.getOrNull()
            }
        }

        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

        /** JSON schema for structured output of the ops list. */
        val SCHEMA = """
        {"type":"object","additionalProperties":false,"required":["ops"],"properties":{"ops":{"type":"array","items":{
          "type":"object","additionalProperties":false,"required":["op","a","b","list","n"],"properties":{
            "op":{"type":"string","enum":["chronicle","drop","reveal","new_secret","consequence","resolve","clock","new_clock","move","character","learns","player_fact","beat_done"]},
            "a":{"type":"string"},"b":{"type":"string"},"list":{"type":"array","items":{"type":"string"}},"n":{"type":"integer"}}}}}}
        """.trimIndent()
    }
}
