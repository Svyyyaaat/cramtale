package com.cramtale.cli

import com.cramtale.core.dict.JdbcSql
import com.cramtale.core.dict.Jmdict
import com.cramtale.core.dict.JmdictBuilder
import com.cramtale.core.match.Calibration
import com.cramtale.core.match.CardState
import com.cramtale.core.match.DeckExport
import com.cramtale.core.match.DeckIndex
import com.cramtale.core.match.DeckReader
import com.cramtale.core.match.FrequencyList
import com.cramtale.core.match.KnownLists
import com.cramtale.core.match.KnownListsStore
import com.cramtale.core.match.KnownPolicy
import com.cramtale.core.match.Matcher
import com.cramtale.core.match.Status
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """cramtale CLI (matcher calibration)

  build-jmdict <JMdict_e.gz> <out.sqlite>
  build-freq   --jmdict <db> --out <lemmas.tsv> [--min N] <sentences.txt>...
               (Leipzig "-sentences.txt" files: id<TAB>sentence)
  deck-report  --jmdict <db> --deck <deck.jsonl>
  analyze      --jmdict <db> --deck <deck.jsonl> [--lists <dir>] [--threshold N]
               [--new-suspended-known] [--no-studied-suspended] <text file | ->
"""

fun main(args: Array<String>) {
    if (args.isEmpty()) { print(USAGE); exitProcess(1) }
    when (args[0]) {
        "build-jmdict" -> buildJmdict(args.getOrNull(1), args.getOrNull(2))
        "build-freq" -> buildFreq(Opts(args.drop(1)))
        "deck-report" -> deckReport(Opts(args.drop(1)))
        "analyze" -> analyze(Opts(args.drop(1)))
        else -> { print(USAGE); exitProcess(1) }
    }
}

private class Opts(args: List<String>) {
    val named = HashMap<String, String>()
    val flags = HashSet<String>()
    val positional = ArrayList<String>()

    init {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a in setOf("--new-suspended-known", "--no-studied-suspended") -> flags += a
                a.startsWith("--") -> { named[a] = args.getOrNull(i + 1) ?: error("$a needs a value"); i++ }
                else -> positional += a
            }
            i++
        }
    }

    fun need(k: String) = named[k] ?: run { System.err.println("missing $k"); exitProcess(1) }
}

private fun buildJmdict(src: String?, out: String?) {
    if (src == null || out == null) { print(USAGE); exitProcess(1) }
    File(out).delete()
    val db = JdbcSql(out)
    val t0 = System.currentTimeMillis()
    val n = File(src).inputStream().use { input ->
        JmdictBuilder.build(JmdictBuilder.open(input, src.endsWith(".gz")), db) { System.err.print("\r$it entries") }
    }
    db.close()
    System.err.println("\r$n entries in ${(System.currentTimeMillis() - t0) / 1000} s -> $out")
}

/** Counts the matcher's own lemmas over a corpus, so ranks line up exactly with what it looks up. */
private fun buildFreq(o: Opts) {
    val dict = Jmdict(JdbcSql(o.need("--jmdict")))
    val matcher = Matcher(dict, DeckIndex(emptyList(), dict))
    val counts = HashMap<String, Int>(200_000)
    var n = 0
    for (path in o.positional) {
        File(path).useLines { lines ->
            for (line in lines) {
                val sentence = line.substringAfter('\t')
                for (span in matcher.analyze(sentence).words()) counts.merge(span.lemma, 1, Int::plus)
                if (++n % 20_000 == 0) System.err.print("\r$n sentences")
            }
        }
    }
    val min = o.named["--min"]?.toInt() ?: 3
    val ranked = counts.entries.filter { it.value >= min }.sortedByDescending { it.value }
    File(o.need("--out")).bufferedWriter().use { w ->
        w.write("# lemma<TAB>count, most frequent first. Built by `cli build-freq` from the Leipzig Corpora Collection\n")
        w.write("# (jpn_newscrawl_2019_300K, jpn_news_2011_300K, jpn_news_2010_300K; CC BY 4.0, https://wortschatz.uni-leipzig.de),\n")
        w.write("# lemmatised with Kuromoji IPADIC and JMdict (EDRDG, CC BY-SA 4.0).\n")
        for (e in ranked) w.write("${e.key}\t${e.value}\n")
    }
    System.err.println("\r$n sentences, ${counts.size} lemmas, ${ranked.size} kept (count >= $min)")
}

private fun load(o: Opts): Triple<Jmdict, DeckIndex, List<com.cramtale.core.match.DeckWord>> {
    val dict = Jmdict(JdbcSql(o.need("--jmdict")))
    val notes = File(o.need("--deck")).useLines { DeckExport.parse(it) }
    val words = DeckReader.words(notes)
    return Triple(dict, DeckIndex(words, dict), words)
}

private val frequency by lazy {
    FrequencyList.bundled().also { if (it == null) System.err.println("(no bundled frequency list: JMdict priority ranks only)") }
}

private fun deckReport(o: Opts) {
    val (_, index, words) = load(o)
    println("Deck words: ${words.size}")
    words.groupBy { it.deck }.forEach { (deck, ws) ->
        val states = ws.groupingBy { it.state }.eachCount()
        println("  $deck: ${ws.size}  " + CardState.entries.joinToString("  ") { "${it.name.lowercase()} ${states[it] ?: 0}" })
    }
    println("Not found in JMdict (${index.unresolved.size}):")
    println("  " + index.unresolved.joinToString("  ") { it.word })
}

private fun analyze(o: Opts) {
    val (dict, index, _) = load(o)
    val lists = o.named["--lists"]?.let { KnownListsStore.load(File(it)) } ?: KnownLists()
    val policy = KnownPolicy(
        frequencyThreshold = o.named["--threshold"]?.toInt() ?: KnownPolicy().frequencyThreshold,
        studiedSuspendedKnown = "--no-studied-suspended" !in o.flags,
        newSuspendedKnown = "--new-suspended-known" in o.flags,
    )
    val path = o.positional.firstOrNull() ?: "-"
    val text = if (path == "-") generateSequence(::readLine).joinToString("\n") else File(path).readText()
    val analysis = Matcher(dict, index, frequency).analyze(text)

    // annotated text: [[unknown]]  {{in deck, never studied}}
    val sb = StringBuilder()
    var pos = 0
    for (s in analysis.spans) {
        sb.append(text, pos, s.start)
        when (s.status(policy, lists)) {
            Status.UNKNOWN -> sb.append("[[").append(s.surface).append("]]")
            Status.DECK_NEW -> sb.append("{{").append(s.surface).append("}}")
            else -> sb.append(s.surface)
        }
        pos = s.end
    }
    sb.append(text, pos, text.length)
    println(sb)
    println()
    val st = analysis.stats(policy, lists)
    println("Words counted: ${st.counted}   unknown: ${st.unknown}   rate: ${"%.1f".format(st.unknownRate * 100)}%   (threshold ${policy.frequencyThreshold})")
    println("By status: " + st.byStatus.entries.sortedBy { it.key }.joinToString("  ") { "${it.key.name.lowercase()} ${it.value}" })
    println("Unknown rate by frequency threshold:")
    for ((t, s) in Calibration.ratesByThreshold(analysis, policy, lists)) {
        println("  %6d  %5.1f%%  (%d of %d)".format(t, s.unknownRate * 100, s.unknown, s.counted))
    }
    println("Flagged words (lemma, times, rank, deck, gloss):")
    for (f in Calibration.flagged(analysis, policy, lists, dict)) {
        val deck = if (f.deck.isEmpty()) "-" else f.deck.joinToString(",") { "${it.deck.substringAfterLast("::")}/${it.state.name.lowercase()}" }
        println("  ${f.lemma}\t${f.count}\t${f.rank ?: "-"}\t$deck\t${f.gloss ?: ""}")
    }
}
