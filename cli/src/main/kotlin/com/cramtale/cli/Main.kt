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
  import-freq  --jmdict <db> --out <ranks.tsv> <Yomitan frequency dictionary .zip>
               (e.g. JPDB v2.2 Frequency Kana; rows become JMdict entry id, spelling, rank)
  deck-report  --jmdict <db> --deck <deck.jsonl>
  analyze      --jmdict <db> --deck <deck.jsonl> [--lists <dir>] [--threshold N]
               [--new-suspended-known] [--no-studied-suspended] <text file | ->
"""

fun main(args: Array<String>) {
    if (args.isEmpty()) { print(USAGE); exitProcess(1) }
    when (args[0]) {
        "build-jmdict" -> buildJmdict(args.getOrNull(1), args.getOrNull(2))
        "import-freq" -> importFreq(Opts(args.drop(1)))
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

/**
 * Yomitan rank-based frequency dictionary -> "entry<TAB>spelling<TAB>rank". A row with a reading
 * names the JMdict entries that have that spelling and reading; a "㋕" rank is the word written
 * in kana, so it's stored under the kana spelling.
 */
private fun importFreq(o: Opts) {
    val dict = Jmdict(JdbcSql(o.need("--jmdict")))
    val best = HashMap<Pair<Long, String>, Int>()
    var rows = 0
    var unmatched = 0
    java.util.zip.ZipFile(o.positional.firstOrNull() ?: error("zip?")).use { zip ->
        for (e in zip.entries().asSequence().filter { it.name.startsWith("term_meta_bank") }) {
            val arr = org.json.JSONArray(zip.getInputStream(e).bufferedReader().readText())
            for (i in 0 until arr.length()) {
                val row = arr.getJSONArray(i)
                if (row.getString(1) != "freq") continue
                rows++
                val term = row.getString(0)
                val data = row.getJSONObject(2)
                val reading = data.optString("reading").takeIf { it.isNotEmpty() }
                val freq = if (data.has("frequency")) data.getJSONObject("frequency") else data
                val rank = freq.getInt("value")
                val kanaWritten = freq.optString("displayValue").contains('㋕')
                var entries = dict.lookup(term).map { it.entry }.distinct()
                if (reading != null) {
                    val r = com.cramtale.core.text.Kana.kataToHira(reading)
                    entries = entries.filter { id -> dict.forms(id).kana.any { com.cramtale.core.text.Kana.kataToHira(it) == r } }
                }
                if (entries.isEmpty()) { unmatched++; continue }
                val spelling = if (reading != null && kanaWritten) reading else term
                for (id in entries) best.merge(id to spelling, rank, ::minOf)
            }
        }
    }
    File(o.need("--out")).bufferedWriter().use { w ->
        w.write("# JMdict entry id<TAB>spelling<TAB>rank, from the JPDB v2.2 Frequency Kana Yomitan dictionary\n")
        w.write("# (jpdb.io corpus, compiled by Kuuube; https://github.com/Kuuuube/yomitan-dictionaries). Not for redistribution.\n")
        for ((k, rank) in best.entries.sortedBy { it.value }) w.write("${k.first}\t${k.second}\t$rank\n")
    }
    System.err.println("$rows rows, ${best.size} entry/spelling ranks written, $unmatched not in JMdict")
}

private fun load(o: Opts): Triple<Jmdict, DeckIndex, List<com.cramtale.core.match.DeckWord>> {
    val dict = Jmdict(JdbcSql(o.need("--jmdict")))
    val notes = File(o.need("--deck")).useLines { DeckExport.parse(it) }
    val words = DeckReader.words(notes)
    return Triple(dict, DeckIndex(words, dict), words)
}

private val frequency by lazy {
    FrequencyList.bundled().also { if (it == null) System.err.println("(no bundled jpdb list: JMdict priority ranks only)") }
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
