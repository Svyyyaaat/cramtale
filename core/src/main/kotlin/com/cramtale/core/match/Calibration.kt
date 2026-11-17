package com.cramtale.core.match

import com.cramtale.core.dict.Jmdict

/** What the calibration screen and CLI show for a text: flagged words and unknown rates per threshold. */
object Calibration {
    val THRESHOLDS = listOf(0, 1000, 2000, 4000, 6000, 8000, 10_000, 12_000, 24_000)

    data class Flagged(
        val lemma: String,
        val status: Status,
        val rank: Int?,
        val count: Int,
        val surfaces: Set<String>,
        val deck: List<DeckWord>,
        val gloss: String?,
    )

    /** Words that count as unknown under [policy], most frequent in the text first. */
    fun flagged(analysis: Analysis, policy: KnownPolicy, lists: KnownLists, dict: Jmdict?): List<Flagged> =
        analysis.spans
            .filter { it.status(policy, lists).let { s -> s == Status.UNKNOWN || s == Status.DECK_NEW } }
            .groupBy { it.lemma }
            .map { (lemma, spans) ->
                val first = spans.first()
                Flagged(
                    lemma, first.status(policy, lists), first.rank, spans.size, spans.map { it.surface }.toSet(),
                    first.deck,
                    dict?.let { d -> first.entries.firstOrNull()?.let { d.senses(it).firstOrNull()?.gloss } },
                )
            }
            .sortedWith(compareByDescending<Flagged> { it.count }.thenBy { it.rank ?: Int.MAX_VALUE })

    fun ratesByThreshold(analysis: Analysis, policy: KnownPolicy, lists: KnownLists): List<Pair<Int, Analysis.Stats>> =
        THRESHOLDS.map { t -> t to analysis.stats(policy.copy(frequencyThreshold = t), lists) }
}
