package com.cramtale.core

import com.cramtale.core.match.DeckExport
import com.cramtale.core.match.DeckIndex
import com.cramtale.core.match.DeckReader
import com.cramtale.core.match.Matcher
import java.io.File
import kotlin.test.Test

/** Where startup time goes with the real deck (prints; runs only where local/ has the data). */
class StartupTimingTest {
    @Test
    fun `startup steps`() {
        val data = TestDict.dataDir ?: return
        val dict = TestDict.full ?: return
        fun <T> t(name: String, f: () -> T): T { val s = System.nanoTime(); val r = f(); println("TIMING $name: ${(System.nanoTime() - s) / 1_000_000} ms"); return r }
        val notes = t("parse deck.jsonl") { File(data, "deck.jsonl").useLines { DeckExport.parse(it) } }
        val words = t("deck words") { DeckReader.words(notes) }
        val index = t("DeckIndex") { DeckIndex(words, dict) }
        val m = Matcher(dict, index)
        t("tokenizer first use") { com.cramtale.core.text.Tokenizer.tokenize("始め") }
        val text = "階段の一番上の板が、ぎしっと鳴った。そのとき、外から別の声がした。低い、落ち着いた声。".repeat(4)
        t("analyze one passage") { m.analyze(text) }
        t("analyze 20 passages") { repeat(20) { m.analyze(text) } }
    }
}
