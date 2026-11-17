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
  vocab-check  --jmdict <db> --deck <deck.jsonl>   (asks y/n per word, suggests a cut-off)
  deck-report  --jmdict <db> --deck <deck.jsonl>
  deck-selftest --jmdict <db> --deck <deck.jsonl>   (finds each note's word in its own example sentence)
  analyze      --jmdict <db> --deck <deck.jsonl> [--lists <dir>] [--threshold N]
               [--new-suspended-unknown] [--no-studied-suspended] <text file | ->
"""

fun main(args: Array<String>) {
    if (args.isEmpty()) { print(USAGE); exitProcess(1) }
    when (args[0]) {
        "build-jmdict" -> buildJmdict(args.getOrNull(1), args.getOrNull(2))
        "import-freq" -> importFreq(Opts(args.drop(1)))
        "vocab-check" -> vocabCheck(Opts(args.drop(1)))
        "deck-report" -> deckReport(Opts(args.drop(1)))
        "story-smoke" -> storySmoke(Opts(args.drop(1)))
        "tokens" -> generateSequence(::readLine).forEach { line ->
            println(com.cramtale.core.text.Tokenizer.tokenize(line).joinToString(" ") { "${it.surface}/${it.base}/${it.pos1}-${it.pos2}" })
        }
        "deck-selftest" -> deckSelftest(Opts(args.drop(1)))
        "grammar-selftest" -> grammarSelftest(Opts(args.drop(1)))
        "grammar-scan" -> grammarScan(Opts(args.drop(1)))
        "grammar-tag" -> File(Opts(args.drop(1)).positional.first()).forEachLine { line ->
            val s = line.trim()
            println(s + "\t" + grammar.find(com.cramtale.core.text.Tokenizer.tokenize(s)).joinToString("|") { "${it.pattern.id}@${it.start}-${it.end}" })
        }
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
                a in setOf("--new-suspended-unknown", "--no-studied-suspended") -> flags += a
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

private fun vocabCheck(o: Opts) {
    val (dict, index, _) = load(o)
    val freq = frequency ?: run { System.err.println("needs the jpdb frequency list"); exitProcess(1) }
    val items = com.cramtale.core.match.VocabCheck.items(dict, freq, index)
    val answers = LinkedHashMap<com.cramtale.core.match.VocabCheck.Item, Boolean>()
    for (it in items) {
        print("[${it.band}+] ${it.spelling}   know it? (y/n) ")
        val a = readLine() ?: break
        answers[it] = a.trim().startsWith("y")
        println("    = ${it.gloss}")
    }
    val results = com.cramtale.core.match.VocabCheck.results(answers)
    for (r in results) println("%6d-%-6d  %d/%d known".format(r.from, r.to, r.known, r.asked))
    println("Suggested cut-off: ${com.cramtale.core.match.VocabCheck.suggestedThreshold(results)}")
}

/** Matching check on real data: is each note's word found, as that note, in the note's own example sentence? */
private fun deckSelftest(o: Opts) {
    val dict = Jmdict(JdbcSql(o.need("--jmdict")))
    val notes = File(o.need("--deck")).useLines { DeckExport.parse(it) }.associateBy { it.noteId }
    val words = DeckReader.words(notes.values.toList())
    val matcher = Matcher(dict, DeckIndex(words, dict), frequency)
    val sentenceField = mapOf("Japanese Core 5k" to "Sentence", "基本" to "表")
    val tag = Regex("<[^>]*>")
    var tested = 0
    var found = 0
    val misses = ArrayList<String>()
    for (w in words) {
        val raw = notes[w.noteId]?.fields?.get(sentenceField[w.noteType] ?: continue) ?: continue
        val sentence = raw.replace(tag, "").replace("&nbsp;", " ").trim()
        if (sentence.isEmpty()) continue
        tested++
        val spans = matcher.analyze(sentence).spans
        if (spans.any { s -> s.deck.any { it.noteId == w.noteId } }) found++
        else misses += "${why(w.word, sentence)}\t${w.word}\t${sentence}\t" + spans.filter { it.isWord }.joinToString(" ") { it.lemma }
    }
    println("Notes with an example sentence: $tested   word found as that note: $found (${"%.1f".format(100.0 * found / tested)}%)")
    println("Misses by cause:")
    misses.groupingBy { it.substringBefore('\t') }.eachCount().entries.sortedByDescending { it.value }
        .forEach { println("  %-40s %d".format(it.key, it.value)) }
    println("Misses (cause, word, sentence, what the matcher saw):")
    misses.sorted().forEach { println("  $it") }
}

private val grammar by lazy { com.cramtale.core.match.GrammarPatterns.bundled() ?: error("no grammar/patterns.tsv") }

/** Each grammar card found in its own example sentence? */
private fun grammarSelftest(o: Opts) {
    val notes = File(o.need("--deck")).useLines { DeckExport.parse(it) }.associateBy { it.noteId }
    val words = DeckReader.words(notes.values.toList())
    val sentenceField = mapOf("Japanese Core 5k" to "Sentence", "基本" to "表")
    val tag = Regex("<[^>]*>")
    var tested = 0
    var found = 0
    for (w in words) {
        val p = w.variants.firstNotNullOfOrNull { grammar.byId(it) } ?: continue
        val raw = notes[w.noteId]?.fields?.get(sentenceField[w.noteType] ?: continue) ?: continue
        val sentence = raw.replace(tag, "").replace("&nbsp;", " ").trim()
        if (sentence.isEmpty()) continue
        tested++
        val hits = grammar.find(com.cramtale.core.text.Tokenizer.tokenize(sentence))
        val ok = hits.any { it.pattern.id == p.id }
        if (ok) found++
        println("${if (ok) "OK  " else "MISS"} ${p.id}\t$sentence\t" + hits.joinToString(" ") { "[${sentence.substring(it.start, it.end)}=${it.pattern.id}]" })
    }
    println("Grammar cards with a sentence: $tested   found: $found")
}

/** Every pattern match in a text file (one sentence per line, last tab field), for checking false matches. */
private fun grammarScan(o: Opts) {
    val max = o.named["--per-pattern"]?.toInt() ?: 5
    val seen = HashMap<String, Int>()
    val counts = HashMap<String, Int>()
    var lines = 0
    File(o.positional.first()).useLines { all ->
        for (line in all) {
            val s = line.substringAfterLast('\t').trim()
            if (s.isEmpty()) continue
            lines++
            for (f in grammar.find(com.cramtale.core.text.Tokenizer.tokenize(s))) {
                counts.merge(f.pattern.id, 1, Int::plus)
                if (seen.merge(f.pattern.id, 1, Int::plus)!! <= max) {
                    println("${f.pattern.id}\t${s.substring(0, f.start)}【${s.substring(f.start, f.end)}】${s.substring(f.end)}")
                }
            }
        }
    }
    System.err.println("$lines lines; matches per pattern: " + counts.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}=${it.value}" })
}

/**
 * A real run of the story loop against the API: setup, style picker, a session, three turns.
 * The key comes from ANTHROPIC_API_KEY, or a network secret the proxy injects. --dir must be a new
 * or empty folder.
 */
private fun storySmoke(o: Opts) {
    val (dict, index, words) = load(o)
    val out = File(o.need("--dir"))
    require(!out.exists() || out.list().isNullOrEmpty()) { "--dir must be a new or empty folder" }
    out.mkdirs()
    val matcher = Matcher(dict, index, frequency)
    val policy = KnownPolicy()
    val lists = KnownLists()
    val llm = com.cramtale.core.llm.AnthropicLlm(System.getenv("ANTHROPIC_API_KEY") ?: "sk-ant-injected-by-proxy")
    val costs = com.cramtale.core.llm.CostLog(File(out, "costs.jsonl"))
    val rnd = kotlin.random.Random(7)
    val studied = words.filter { it.state == CardState.STUDIED && it.noteType == "Japanese Core 5k" && it.word.none { c -> c.isDigit() } }
        .filter { w -> frequency?.rank(com.cramtale.core.match.Normalize.entriesFor(dict, w.word, null), w.word)?.let { it in 1500..8000 } == true }
        .shuffled(rnd)
    val newOnes = words.filter { it.state == CardState.NEW }.shuffled(rnd).take(2)
    val pool = com.cramtale.core.story.DuePool(
        words = studied.take(12).map { com.cramtale.core.story.DueWord(it.word, it.noteId, false) } +
            newOnes.map { com.cramtale.core.story.DueWord(it.word, it.noteId, true) },
        grammar = listOf("ことにする", "にもかかわらず").mapNotNull { grammar.byId(it) }
            .mapIndexed { i, p -> com.cramtale.core.story.DueGrammar(p.id, p.meaning, -1L - i) },
        weakPoints = listOf("い-adjective past: 〜いでした → 〜かったです"),
    )
    val unknown = com.cramtale.core.story.UnknownWords { text ->
        val a = matcher.analyze(text)
        val reviewing = pool.words.map { it.word }.toSet()
        val counted = a.words().filter { s -> s.status(policy, lists).let { it != Status.IGNORED && it != Status.NAME } }
        val unk = counted.filter { s ->
            s.status(policy, lists) in setOf(Status.UNKNOWN, Status.DECK_NEW) && s.deck.none { it.word in reviewing } && s.lemma !in reviewing
        }
        counted.size to unk.map { it.lemma }
    }
    val engine = com.cramtale.core.story.StoryEngine(out, llm, costs, "smoke", unknown)
    fun step(name: String) = println("\n======== $name  (spent so far $${"%.3f".format(costs.total("smoke"))})")

    step("setup")
    engine.setup()
    println(File(out, "state/world.md").readText())
    println(File(out, "state/player.md").readText())
    engine.state.characterNames().forEach { println("--- $it\n" + engine.state.character(it)) }

    step("style picker")
    val rated = engine.styleOptions().map { o -> o to unknown.check(o.passage).let { (n, u) -> if (n == 0) 0.0 else u.size.toDouble() / n } }
    rated.forEach { (s, r) -> println("--- ${s.name} (${"%.1f".format(r * 100)}% unknown): ${s.line}\n${s.passage}") }
    engine.pickStyle(rated.minByOrNull { it.second }!!.first)

    step("session")
    engine.startSession(pool).forEach(::println)
    println("Review words offered: " + pool.words.joinToString("、") { it.word + if (it.isNew) "(new)" else "" })

    val replies = listOf<String?>(
        null,
        "すみません、もう少し待ってくれませんか。お金はまだ準備するができません。昨日は忙しいでした。",
        "わかった。明日の朝、倉庫に行く。",
    )
    for ((i, msg) in replies.withIndex()) {
        step("turn ${i + 1}" + (msg?.let { ": $it" } ?: ""))
        val t = engine.playerTurn(msg, pool, emptyList())
        t.corrections.forEach { println("✎ ${it.wrong} → ${it.right} [${it.key}] ${it.reason}") }
        t.roll?.let { println("Roll: $it") }
        t.complication?.let { println("Complication: $it") }
        println(t.draft.passage)
        println("used: " + t.draft.used.joinToString { "${it.first}=${it.second}" } + "  grammar: " + t.draft.grammar + "  rewritten: " + t.draft.rewritten)
        t.draft.checks.forEach { println("  check ${it.name}: ${if (it.passed) "ok" else it.detail}") }
        engine.commit().forEach { println("  state: $it") }
    }
    step("costs by call")
    costs.byPurpose("smoke").forEach { (k, v) -> println("  $k: $${"%.4f".format(v)}") }
    costs.entries.filter { it.purpose == "writer" }.forEach {
        println("  writer call: in ${it.usage.input}, cache read ${it.usage.cacheRead}, cache write ${it.usage.cacheWrite}, out ${it.usage.output}")
    }
}

/** Rough cause of a self-test miss, from how Kuromoji reads the deck word on its own. */
private fun why(word: String, sentence: String): String {
    val toks = com.cramtale.core.text.Tokenizer.tokenize(word).filterNot { it.isSymbol }
    val t = toks.singleOrNull()
    return when {
        toks.any { it.pos1 == "名詞" && it.pos2 == "数" } || word.any { it.isDigit() } -> "number / counter (skipped by rule)"
        t != null && t.pos1 == "名詞" && t.pos2 == "固有名詞" -> "name / place (skipped by rule)"
        t != null && t.pos1 in setOf("助詞", "助動詞", "感動詞", "フィラー") -> "particle / auxiliary / interjection (skipped by rule)"
        toks.size > 1 && toks.any { it.pos1 == "助詞" || it.pos1 == "助動詞" } -> "grammar pattern (によれば, こともある)"
        word !in sentence && toks.none { it.base in sentence || it.surface in sentence } -> "sentence doesn't contain the word as written"
        else -> "other"
    }
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
        newSuspendedKnown = "--new-suspended-unknown" !in o.flags,
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
