package com.cramtale.core.review

import org.json.JSONObject

/**
 * Read-only mode's check on itself (PLAN.md §4.5): for each answer the app would have sent, what
 * did you actually press in AnkiDroid afterwards? Inferred from how the card's state moved:
 * a review card that drops into relearning was failed; one whose due date moved on, passed.
 * (The exact version reads the revlog from an exported collection: tools/dryrun_report.py.)
 */
object DryRunReport {
    enum class Real { AGAIN, PASSED, STUDIED_NEW, STILL_LEARNING, NOT_YET, UNKNOWN }

    data class Row(val word: String, val appEase: Int, val real: Real, val reason: String)

    data class Summary(val rows: List<Row>) {
        private fun count(app: Int, vararg real: Real) = rows.count { it.appEase == app && it.real in real }
        val agreeGood get() = count(3, Real.PASSED)
        val agreeAgain get() = count(1, Real.AGAIN)
        /** The costly one: the app would have said Good where you said Again (a leak, or an over-easy context). */
        val appGoodRealAgain get() = count(3, Real.AGAIN)
        val appAgainRealPassed get() = count(1, Real.PASSED)
        val undecided get() = rows.count { it.real in setOf(Real.NOT_YET, Real.UNKNOWN, Real.STILL_LEARNING, Real.STUDIED_NEW) }
    }

    fun infer(before: CardInfo, now: CardInfo?): Real = when {
        now == null -> Real.UNKNOWN
        now == before -> Real.NOT_YET
        before.reps >= 0 && now.reps >= 0 && now.reps == before.reps -> Real.NOT_YET
        before.type == 0 -> Real.STUDIED_NEW
        before.type == 2 && now.type == 3 -> Real.AGAIN
        before.type == 2 && now.type == 2 && now.due > before.due -> Real.PASSED
        before.type in setOf(1, 3) && now.type == 2 -> Real.PASSED
        before.type in setOf(1, 3) && now.type in setOf(1, 3) -> Real.STILL_LEARNING
        else -> Real.UNKNOWN
    }

    /** One row per card: the first would-be answer logged for it in read-only mode. */
    fun build(entries: List<JSONObject>, current: (CardRef) -> CardInfo?): Summary {
        val firsts = entries.filter { !it.optBoolean("sent") && it.optString("note") == "dry run" }
            .distinctBy { "${it.getLong("noteId")}:${it.getInt("ord")}" }
        return Summary(firsts.map { e ->
            val b = e.getJSONObject("before")
            val before = CardInfo(b.getInt("type"), b.getInt("queue"), b.getLong("due"), b.optInt("reps", -1), b.optInt("ivl", -1))
            val now = current(CardRef(e.getLong("noteId"), e.getInt("ord")))
            Row(e.getString("word"), e.getInt("ease"), infer(before, now), e.getString("reason"))
        })
    }
}
