package com.cramtale.core.match

import com.cramtale.core.text.Tok
import java.io.InputStream

/**
 * Grammar patterns found in text, so grammar cards can be reviewed by reading like words.
 *
 * A rule is a sequence of pieces separated by spaces:
 *   `によって`      literal text, matched against the tokens' surfaces joined together; it may span
 *                   several tokens but must start and end on token boundaries (Kuromoji keeps について
 *                   as one token but splits に|よれ|ば)
 *   `@なる`         one token whose dictionary form is なる, in any conjugation (なり, なった, なりません)
 *   `a|b`           either piece
 *   `:接続助詞`      suffix on a literal: every token it covers must have this POS level 2
 * A rule may start with a condition on the word before it:
 *   `<V`  a verb, adjective or auxiliary (ても after 降って, not でも meaning "but")
 *   `<N`  a noun
 *   `<P`  a verb, adjective or auxiliary that isn't the past た/だ
 *   `<Q`  anything except the volitional う/よう
 *   `<S`  a verb, adjective, auxiliary or verbal noun (登録, 増加)
 * and may end with `>!a|b`: the next token's dictionary form or surface must not be a or b.
 * A rule starting with `~` is a regular expression over the sentence text instead, for the few
 * strings Kuromoji splits wrongly (いただけ → い|た|だけ). Use only where the string can't mean
 * anything else.
 */
class GrammarPatterns(val patterns: List<Pattern>) {

    data class Pattern(val id: String, val meaning: String, val rules: List<Rule>)

    /** [raw]: a regular expression over the sentence text, for strings Kuromoji splits wrongly. */
    data class Rule(
        val before: Char?,
        val pieces: List<List<Piece>>,
        val raw: Regex? = null,
        /** The token after the match must not have one of these dictionary forms (ため|に then not なる). */
        val notAfter: Set<String> = emptySet(),
    )

    sealed interface Piece {
        data class Literal(val text: String, val pos2: String?) : Piece
        data class Base(val base: String) : Piece
    }

    data class Found(val start: Int, val end: Int, val pattern: Pattern, val tokens: IntRange)

    /** Every pattern occurrence; where matches overlap, the longest wins (なくてはならない over ては). */
    fun find(toks: List<Tok>): List<Found> {
        val all = ArrayList<Found>()
        val text = toks.joinToString("") { it.surface }
        val offsets = toks.runningFold(0) { acc, t -> acc + t.surface.length }
        for (p in patterns) for (r in p.rules) {
            val raw = r.raw ?: continue
            for (m in raw.findAll(text)) {
                // snap to the tokens the match touches
                val first = offsets.indexOfLast { it <= m.range.first }.coerceIn(toks.indices)
                val last = offsets.indexOfFirst { it > m.range.last }.let { if (it < 0) toks.size else it } - 1
                if (last < first) continue
                all += Found(toks[first].start, toks[last].end, p, first..last)
            }
        }
        for (p in patterns) for (r in p.rules) for (i in toks.indices) {
            if (r.raw != null) continue
            if (!beforeOk(r.before, toks.getOrNull(i - 1))) continue
            val end = matchFrom(r.pieces, 0, toks, i) ?: continue
            if (r.notAfter.isNotEmpty() && toks.getOrNull(end)?.let { it.base in r.notAfter || it.surface in r.notAfter } == true) continue
            all += Found(toks[i].start, toks[end - 1].end, p, i until end)
        }
        val kept = ArrayList<Found>()
        for (f in all.sortedWith(compareByDescending<Found> { it.end - it.start }.thenBy { it.start })) {
            if (kept.none { it.start < f.end && f.start < it.end }) kept += f
        }
        return kept.sortedBy { it.start }
    }

    private fun beforeOk(cond: Char?, prev: Tok?): Boolean = when (cond) {
        null -> true
        // ということになる, ってことはない: a quoted clause counts as the verb before
        'V' -> prev != null && (prev.pos1 in setOf("動詞", "形容詞", "助動詞") || prev.surface in setOf("という", "って"))
        'N' -> prev != null && prev.pos1 == "名詞"
        // a verb, not in the past: 行くことはない (no need to) vs 行ったことはない (never did)
        'P' -> prev != null && ((prev.pos1 in setOf("動詞", "形容詞", "助動詞") && prev.surface !in setOf("た", "だ")) ||
            prev.surface in setOf("という", "って"))
        // a verb or a verbal noun: 登録にあたって, 増加につれて (not 火にあたって)
        'S' -> prev != null && (prev.pos1 in setOf("動詞", "形容詞", "助動詞") || (prev.pos1 == "名詞" && prev.pos2 == "サ変接続"))
        // anything but the volitional: 盗もうとして is "try to", not として
        'Q' -> prev == null || prev.surface !in setOf("う", "よう")
        else -> true
    }

    /** Index after the match, or null. Tries every alternative (and every literal split). */
    private fun matchFrom(pieces: List<List<Piece>>, k: Int, toks: List<Tok>, i: Int): Int? {
        if (k == pieces.size) return i
        for (alt in pieces[k]) {
            when (alt) {
                is Piece.Base -> {
                    if (i < toks.size && toks[i].base == alt.base) matchFrom(pieces, k + 1, toks, i + 1)?.let { return it }
                }
                is Piece.Literal -> {
                    var j = i
                    val sb = StringBuilder()
                    while (j < toks.size && sb.length < alt.text.length) {
                        if (alt.pos2 != null && toks[j].pos2 != alt.pos2) break
                        sb.append(toks[j].surface)
                        j++
                        if (sb.toString() == alt.text) {
                            matchFrom(pieces, k + 1, toks, j)?.let { return it }
                            break
                        }
                        if (!alt.text.startsWith(sb.toString())) break
                    }
                }
            }
        }
        return null
    }

    fun byId(id: String) = patterns.firstOrNull { it.id == id }

    companion object {
        const val RESOURCE = "/grammar/patterns.tsv"

        /** Lines: id<TAB>meaning<TAB>rule ;; rule … ('#' comments). */
        fun parse(input: InputStream): GrammarPatterns = GrammarPatterns(
            input.bufferedReader(Charsets.UTF_8).readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val cols = line.split('\t')
                    val id = cols[0].trim()
                    val meaning = cols.getOrElse(1) { "" }.trim()
                    val rules = cols.getOrElse(2) { id }
                    Pattern(id, meaning, rules.split(";;").map { parseRule(it.trim()) })
                },
        )

        fun parseRule(rule: String): Rule {
            if (rule.startsWith("~")) return Rule(null, emptyList(), Regex(rule.drop(1)))
            var parts = rule.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var before: Char? = null
            if (parts.first().startsWith("<")) { before = parts.first()[1]; parts = parts.drop(1) }
            val notAfter = parts.filter { it.startsWith(">!") }.flatMap { it.drop(2).split('|') }.toSet()
            parts = parts.filterNot { it.startsWith(">!") }
            return Rule(before, notAfter = notAfter, raw = null, pieces = parts.map { piece ->
                piece.split('|').map { alt ->
                    if (alt.startsWith("@")) Piece.Base(alt.drop(1))
                    else {
                        val colon = alt.indexOf(':')
                        if (colon > 0) Piece.Literal(alt.substring(0, colon), alt.substring(colon + 1)) else Piece.Literal(alt, null)
                    }
                }
            })
        }

        fun bundled(): GrammarPatterns? = GrammarPatterns::class.java.getResourceAsStream(RESOURCE)?.use(::parse)
    }
}
