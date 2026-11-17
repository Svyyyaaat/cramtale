package com.cramtale.core

import com.cramtale.core.match.DeckExport
import com.cramtale.core.match.DeckIndex
import com.cramtale.core.match.DeckReader
import com.cramtale.core.match.Matcher
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real passages Claude wrote in earlier live runs (local/smoke*), with the review words it said it used:
 * does the matcher credit each of those words' deck notes? A miss is a review that silently wouldn't count.
 * Runs only where local/ has the data.
 */
class WriterUsedTest {
    @Test
    fun `review words the writer used are credited to their deck notes`() {
        val data = TestDict.dataDir ?: return
        val dict = TestDict.full ?: return
        val deckFile = File(data, "deck.jsonl").takeIf { it.exists() } ?: return
        val words = DeckReader.words(deckFile.useLines { DeckExport.parse(it) })
        val index = DeckIndex(words, dict)
        val m = Matcher(dict, index)
        val calls = data.listFiles { f -> f.name.startsWith("smoke") && f.isDirectory }.orEmpty()
            .flatMap { File(it, "log/calls.jsonl").takeIf { f -> f.exists() }?.readLines().orEmpty() }
            .map(::JSONObject).filter { it.optString("purpose") in setOf("writer", "rewrite") }
        var used = 0
        val misses = ArrayList<String>()
        for (c in calls) {
            val o = runCatching { JSONObject(c.getString("text")) }.getOrNull() ?: continue
            val passage = o.getString("passage")
            val credited = m.analyze(passage).spans.flatMap { s -> s.deck.map { it.noteId } }.toSet()
            val a = o.optJSONArray("used") ?: continue
            for (i in 0 until a.length()) {
                val u = a.getJSONObject(i)
                val w = u.getString("word")
                if (u.getString("form") !in passage) continue   // the engine drops these before grading
                val notes = words.filter { w in it.variants }.map { it.noteId }
                if (notes.isEmpty()) continue                    // not a deck word (the smoke runs used a sample pool)
                used++
                if (notes.none { it in credited }) misses += "$w (as 「${u.getString("form")}」)"
            }
        }
        println("writer-used review words: $used, not credited: ${misses.size}")
        misses.forEach { println("  miss: $it") }
        assertTrue(used == 0 || misses.size <= used / 10, "too many uncredited review words: $misses")
    }
}
