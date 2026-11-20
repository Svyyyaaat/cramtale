package com.cramtale.core.story

import java.io.File
import kotlin.random.Random

/**
 * Code decides uncertain outcomes; the model only narrates them (PLAN.md §6.2).
 * 2d6 + modifier: 10+ success, 7–9 success at a cost, 6 or less failure. Failure is real:
 * the caller writes its consequence into the state before the writer sees the result.
 */
class Dice(val random: Random = Random.Default) {
    enum class Outcome(val label: String, val instruction: String) {
        SUCCESS("success", "The action works as intended."),
        COST("success at a cost", "The action works, but something is lost, spent, noticed or made worse. Choose a cost that fits the scene and keep it."),
        FAILURE("failure", "The action fails. Something closes: a door, a chance, someone's patience. The story moves on from it; it is not undone or retried for free."),
    }

    data class Roll(val dice: List<Int>, val modifier: Int, val outcome: Outcome) {
        val total get() = dice.sum() + modifier
        override fun toString() = "2d6 ${dice.joinToString("+")}${if (modifier >= 0) "+" else ""}$modifier = $total: ${outcome.label}"
    }

    /** [difficulty]: -2 (easy) … +2 (hard), from the adjudicator; [bonus]: from the player's situation. */
    fun roll(difficulty: Int, bonus: Int = 0): Roll {
        val d = listOf(random.nextInt(1, 7), random.nextInt(1, 7))
        val mod = (bonus - difficulty).coerceIn(-3, 3)
        val total = d.sum() + mod
        val outcome = when {
            total >= 10 -> Outcome.SUCCESS
            total >= 7 -> Outcome.COST
            else -> Outcome.FAILURE
        }
        return Roll(d, mod, outcome)
    }
}

/**
 * config/complications.md: one grounded complication per line, "- [tag, tag] text" (tags optional).
 * One is drawn per scene (beat), preferring ones tagged for the current place, and none repeats
 * until the table is used up.
 */
class Complications(private val lines: List<Pair<Set<String>, String>>, private val random: Random = Random.Default) {
    /** How many lines haven't been used yet. */
    fun unused(used: Collection<String>) = lines.count { it.second !in used }

    fun draw(place: String, used: Collection<String>): String? {
        if (lines.isEmpty()) return null
        var pool = lines.filter { it.second !in used }
        if (pool.isEmpty()) pool = lines
        val local = pool.filter { (tags, _) -> tags.any { it.isNotEmpty() && place.contains(it) } }
        val untagged = pool.filter { it.first.isEmpty() }
        return (local.ifEmpty { untagged.ifEmpty { pool } }).random(random).second
    }

    companion object {
        private val line = Regex("^-\\s*(?:\\[([^\\]]*)\\]\\s*)?(.+)$")

        fun parse(text: String, random: Random = Random.Default) = Complications(
            text.lines().mapNotNull { l -> line.find(l.trim())?.let { m ->
                m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() to m.groupValues[2].trim()
            } },
            random,
        )

        fun load(file: File, random: Random = Random.Default) =
            parse(if (file.exists()) file.readText() else "", random)
    }
}

/**
 * config/ambience.md, the background going on around people, with SillyTavern-style timed effects. Options go in
 * the brackets next to place tags: `- [street; sticky 4; group weather] Rain starts, light, on the roof.`
 * - sticky N: once drawn, it keeps going for N more turns (rain doesn't stop the next turn);
 * - cooldown N: not drawn again for N turns after it ends (default 6, so the dog doesn't bark every turn);
 * - group G: only one line of a group is going on at a time (one kind of weather).
 * The state (what is going on and what is resting) lives in the scene, so it survives restarts.
 */
class Ambience(private val lines: List<Line>, private val random: Random = Random.Default) {
    data class Line(val text: String, val places: Set<String>, val sticky: Int, val cooldown: Int, val group: String?)

    /** Draws [count] lines for [turn]. [going]: text → last turn it lasts; [resting]: text → first turn it may come back. */
    fun draw(place: String, turn: Int, count: Int, going: MutableMap<String, Int>, resting: MutableMap<String, Int>): List<String> {
        // ended effects start their cooldown
        for ((text, until) in going.toList()) if (until < turn) {
            going.remove(text)
            resting[text] = turn + (lines.firstOrNull { it.text == text }?.cooldown ?: DEFAULT_COOLDOWN)
        }
        resting.entries.removeAll { it.value <= turn }
        val ongoing = going.keys.toList()
        val busyGroups = lines.filter { it.text in going }.mapNotNull { it.group }.toMutableSet()
        val picked = ArrayList<String>()
        var pool = lines.filter { it.text !in going && it.text !in resting && (it.group == null || it.group !in busyGroups) }
        val fits = { l: Line -> l.places.isEmpty() || l.places.any { place.contains(it) } }
        while (picked.size < count) {
            val local = pool.filter(fits)
            if (local.isEmpty()) break
            val l = local.random(random)
            picked += l.text
            going[l.text] = turn + l.sticky
            l.group?.let { g -> busyGroups += g; pool = pool.filter { it.group != g } }
            pool = pool.filter { it !== l }
        }
        // what's still going on from earlier turns comes first, marked so it reads as continuing
        return ongoing.map { "(still) $it" } + picked
    }

    companion object {
        const val DEFAULT_COOLDOWN = 6
        private val line = Regex("^-\\s*(?:\\[([^\\]]*)\\]\\s*)?(.+)$")

        fun parse(text: String, random: Random = Random.Default) = Ambience(
            text.lines().mapNotNull { l -> line.find(l.trim())?.let { m ->
                var sticky = 0; var cooldown = DEFAULT_COOLDOWN; var group: String? = null
                val places = HashSet<String>()
                for (part in m.groupValues[1].split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }) {
                    val w = part.split(Regex("\\s+"), limit = 2)
                    when (w[0].lowercase()) {
                        "sticky" -> sticky = w.getOrNull(1)?.toIntOrNull() ?: sticky
                        "cooldown" -> cooldown = w.getOrNull(1)?.toIntOrNull() ?: cooldown
                        "group" -> group = w.getOrNull(1)?.trim()
                        else -> places += part
                    }
                }
                Line(m.groupValues[2].trim(), places, sticky, cooldown, group)
            } },
            random,
        )
    }
}
