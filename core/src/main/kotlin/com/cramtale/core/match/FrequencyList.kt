package com.cramtale.core.match

import java.io.InputStream

/**
 * Lemma frequency ranks (1 = most frequent), in the same lemma forms the matcher produces.
 * The bundled list is built by `cli build-freq` from the Leipzig Corpora Collection
 * (Japanese news 2010, 2011 and 2019, 300k sentences each, CC BY 4.0).
 */
class FrequencyList(private val ranks: Map<String, Int>) {
    fun rank(lemma: String): Int? = ranks[lemma]
    val size get() = ranks.size

    companion object {
        const val RESOURCE = "/freq/leipzig_lemmas.tsv"

        fun parse(input: InputStream): FrequencyList {
            val map = HashMap<String, Int>(65_536)
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) map.putIfAbsent(line.substring(0, tab), map.size + 1)
                }
            }
            return FrequencyList(map)
        }

        /** The list shipped in core's resources, or null when it isn't there. */
        fun bundled(): FrequencyList? = FrequencyList::class.java.getResourceAsStream(RESOURCE)?.use(::parse)
    }
}
