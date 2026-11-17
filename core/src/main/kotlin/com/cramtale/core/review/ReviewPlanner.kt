package com.cramtale.core.review

import com.cramtale.core.writeAtomic
import com.cramtale.core.match.Analysis
import com.cramtale.core.match.GrammarPatterns
import com.cramtale.core.story.Correction
import com.cramtale.core.story.DueGrammar
import com.cramtale.core.story.DuePool
import com.cramtale.core.story.DueWord
import com.cramtale.core.story.Draft
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Between Anki and the writer: which due cards to offer (skipped ones first), and which cards a
 * finished passage really contains. Grammar counts only when the writer declared it AND the
 * matcher finds it there (PLAN.md §5.4); words count when the matcher finds them.
 */
class ReviewPlanner(private val grammar: GrammarPatterns?, private val skipsFile: File?) {
    private val skips: MutableMap<Long, Int> = HashMap<Long, Int>().apply {
        skipsFile?.takeIf { it.exists() }?.let { f -> val o = JSONObject(f.readText()); o.keys().forEach { put(it.toLong(), o.getInt(it)) } }
    }

    fun isGrammarCard(word: String) = grammar?.byId(word) != null

    fun pool(snapshot: DueSnapshot, weakPoints: List<String>, maxWords: Int = 15, maxGrammar: Int = 3): DuePool {
        val notes = snapshot.cards.distinctBy { it.ref.noteId }
        val (gram, words) = notes.partition { d -> d.word.variants.any { isGrammarCard(it) } }
        val order = compareByDescending<DueCard> { skips[it.ref.noteId] ?: 0 }.thenBy { if (it.info.type == 1 || it.info.type == 3) 0 else 1 }
        return DuePool(
            words = words.sortedWith(order).take(maxWords).map { DueWord(it.word.word, it.ref.noteId, it.isNew) },
            grammar = gram.sortedWith(order).take(maxGrammar).mapNotNull { d ->
                val p = d.word.variants.firstNotNullOfOrNull { grammar?.byId(it) } ?: return@mapNotNull null
                DueGrammar(p.id, p.meaning, d.ref.noteId)
            },
            weakPoints = weakPoints,
        )
    }

    /** Deck notes the passage really contains: matched words, plus grammar both declared and found. */
    fun notesInText(analysis: Analysis, draft: Draft, pool: DuePool): Set<Long> {
        val out = HashSet<Long>()
        analysis.spans.forEach { s -> s.deck.forEach { out += it.noteId } }
        val found = grammar?.find(com.cramtale.core.text.Tokenizer.tokenize(analysis.text))?.map { it.pattern.id }?.toSet().orEmpty()
        pool.grammar.filter { it.id in draft.grammar && it.id in found }.forEach { out += it.noteId }
        return out
    }

    /** Offered but not used: priority next time. Used: back to zero. */
    fun recordSkips(pool: DuePool, inText: Set<Long>) {
        for (id in pool.words.map { it.noteId } + pool.grammar.map { it.noteId }) {
            if (id in inText) skips.remove(id) else skips[id] = (skips[id] ?: 0) + 1
        }
        skipsFile?.let { f -> f.parentFile?.mkdirs(); f.writeAtomic(JSONObject(skips.mapKeys { it.key.toString() } as Map<*, *>).toString()) }
    }
}

/**
 * Your mistakes over time (PLAN.md §8): every correction by key. The second time a key comes up, a
 * card is proposed for your approval. Three correct uses without a repeat mark a key as fixed, and
 * fixed keys stop being sent to the writer as weak points.
 */
class MistakeLog(private val file: File?) {
    data class Key(val key: String, var count: Int, var correctSince: Int, var proposed: Boolean, var decided: String?, val examples: MutableList<Pair<String, Correction>>)

    private val keys = LinkedHashMap<String, Key>()

    init {
        file?.takeIf { it.exists() }?.readText()?.let { JSONArray(it) }?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                val ex = o.getJSONArray("examples")
                keys[o.getString("key")] = Key(o.getString("key"), o.getInt("count"), o.getInt("correctSince"), o.getBoolean("proposed"),
                    o.optString("decided").ifEmpty { null },
                    (0 until ex.length()).map { ex.getJSONObject(it) }.map { e ->
                        e.getString("message") to Correction(e.getString("wrong"), e.getString("right"), e.optString("type"), e.optString("reason"), o.getString("key"))
                    }.toMutableList())
            }
        }
    }

    val all get() = keys.values.toList()

    /** Keys the writer should keep in mind: seen, not fixed. */
    fun weakPoints(limit: Int = 5) = keys.values.filter { it.correctSince < 3 }.sortedByDescending { it.count }.take(limit).map { it.key }

    /** Records one message's check; returns keys that just reached their second occurrence (card proposals). */
    fun record(message: String, corrections: List<Correction>, correctKeys: List<String>): List<Key> {
        val newlyProposed = ArrayList<Key>()
        for (c in corrections.distinctBy { it.key }) {
            val k = keys.getOrPut(c.key) { Key(c.key, 0, 0, false, null, ArrayList()) }
            k.count++
            k.correctSince = 0
            k.examples += message to c
            if (k.count >= 2 && !k.proposed) { k.proposed = true; newlyProposed += k }
        }
        for (ck in correctKeys) keys[ck]?.let { if (corrections.none { c -> c.key == ck }) it.correctSince++ }
        save()
        return newlyProposed
    }

    fun decide(key: String, approved: Boolean) { keys[key]?.decided = if (approved) "approved" else "rejected"; save() }

    fun pending() = keys.values.filter { it.proposed && it.decided == null }

    private fun save() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        f.writeAtomic(JSONArray(keys.values.map { k ->
            JSONObject().put("key", k.key).put("count", k.count).put("correctSince", k.correctSince).put("proposed", k.proposed)
                .put("decided", k.decided ?: "")
                .put("examples", JSONArray(k.examples.map { (m, c) ->
                    JSONObject().put("message", m).put("wrong", c.wrong).put("right", c.right).put("type", c.type).put("reason", c.reason)
                }))
        }).toString(1))
    }
}
