package com.cramtale.core.match

import java.io.InputStream

/**
 * Word frequency ranks from jpdb.io, keyed by JMdict entry id (jpdb's vocabulary ids are JMdict
 * sequence numbers) and spelling. The rank is the median of jpdb's Anime, Visual Novel, Books,
 * Web Novel and Live Action ranks: how common a word is in fiction and dialogue, not in news.
 * Collected by tools/jpdb_ranks.py; see freq/SOURCES.md.
 */
class FrequencyList(
    private val bySpelling: Map<Long, Map<String, Int>>,
) {
    val size get() = bySpelling.size

    data class Ranked(val entry: Long, val spelling: String, val rank: Int)

    /** Every (entry, spelling) ranked in [from, to). */
    fun inRange(from: Int, to: Int): List<Ranked> = bySpelling.flatMap { (id, m) ->
        m.filterValues { it in from until to }.map { (sp, r) -> Ranked(id, sp, r) }
    }

    /** The rank of this spelling of one of [entries], else the entry's best rank. */
    fun rank(entries: Collection<Long>, spelling: String): Int? {
        val maps = entries.mapNotNull { bySpelling[it] }
        if (maps.isEmpty()) return null
        maps.mapNotNull { it[spelling] }.minOrNull()?.let { return it }
        return maps.flatMap { it.values }.minOrNull()
    }

    companion object {
        const val RESOURCE = "/freq/jpdb_ranks.tsv"

        fun parse(input: InputStream): FrequencyList {
            val map = HashMap<Long, HashMap<String, Int>>(65_536)
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val f = line.split('\t')
                    if (f.size < 3) continue
                    val id = f[0].toLongOrNull() ?: continue
                    val rank = f[2].toIntOrNull() ?: continue
                    map.getOrPut(id) { HashMap(2) }.merge(f[1], rank, ::minOf)
                }
            }
            return FrequencyList(map)
        }

        /** The list shipped in core's resources, or null when it isn't there. */
        fun bundled(): FrequencyList? = FrequencyList::class.java.getResourceAsStream(RESOURCE)?.use(::parse)
    }
}
