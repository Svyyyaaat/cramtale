package com.cramtale.core.match

/**
 * The story's character names as single spans. The tokenizer splits a name it doesn't know
 * (さかきともえ → さ + かき + ともえ), which made its pieces tappable words; where a name lines up with
 * span boundaries, those spans become one name span (not graded, never carded).
 */
object Names {
    fun merge(text: String, spans: List<Span>, names: Collection<String>): List<Span> {
        if (names.isEmpty() || spans.isEmpty()) return spans
        val starts = spans.map { it.start }.toSet()
        val ends = spans.map { it.end }.toSet()
        val taken = ArrayList<IntRange>()
        for (name in names.filter { it.length >= 2 }.sortedByDescending { it.length }) {
            var i = text.indexOf(name)
            while (i >= 0) {
                val end = i + name.length
                // only whole spans: a name inside a longer word stays that word
                if (i in starts && end in ends && taken.none { i < it.last + 1 && end > it.first }) taken += i until end
                i = text.indexOf(name, i + 1)
            }
        }
        if (taken.isEmpty()) return spans
        val out = ArrayList<Span>()
        for (s in spans) {
            val r = taken.firstOrNull { s.start >= it.first && s.end <= it.last + 1 }
            when {
                r == null -> out += s
                s.start == r.first -> {
                    val inside = spans.filter { it.start >= r.first && it.end <= r.last + 1 }
                    val surface = text.substring(r.first, r.last + 1)
                    out += Span(r.first, r.last + 1, surface, surface, emptySet(), null, emptyList(), "name", inside.sumOf { it.tokens })
                }
            }
        }
        return out
    }
}
