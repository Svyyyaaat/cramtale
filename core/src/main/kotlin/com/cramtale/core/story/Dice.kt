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
