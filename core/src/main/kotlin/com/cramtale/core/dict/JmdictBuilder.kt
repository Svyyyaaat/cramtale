package com.cramtale.core.dict

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.Reader
import java.util.zip.GZIPInputStream

/**
 * Streams JMdict (JMdict_e or JMdict_e.gz, EDRDG, CC BY-SA 4.0) into SQLite.
 * Works the same on Android and the JVM: only XmlPullParser and [Sql].
 *
 * JMdict writes part-of-speech and misc codes as DTD entities (&v5k;). The parser
 * never sees the DTD: a filtering reader turns each `&code;` into the bare code first,
 * so `<pos>&v5k;</pos>` reads as "v5k".
 */
object JmdictBuilder {
    const val SCHEMA_VERSION = "1"

    private val SCHEMA = listOf(
        "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)",
        // rank: approximate frequency rank from the priority tags (lower = more frequent), null if none
        "CREATE TABLE entry(id INTEGER PRIMARY KEY, rank INTEGER, uk INTEGER NOT NULL, has_kanji INTEGER NOT NULL)",
        "CREATE TABLE form(text TEXT NOT NULL, entry INTEGER NOT NULL, kana INTEGER NOT NULL, rank INTEGER, nokanji INTEGER NOT NULL)",
        "CREATE TABLE sense(entry INTEGER NOT NULL, n INTEGER NOT NULL, pos TEXT NOT NULL, misc TEXT NOT NULL, gloss TEXT NOT NULL, stagk TEXT NOT NULL, stagr TEXT NOT NULL)",
    )
    private val INDEXES = listOf(
        "CREATE INDEX form_text ON form(text)",
        "CREATE INDEX form_entry ON form(entry)",
        "CREATE INDEX sense_entry ON sense(entry)",
    )

    /**
     * Frequency rank from JMdict's priority tags: nfXX is the XXth block of 500 words in
     * newspaper frequency; the other "1" lists are roughly the top 10k–12k; the "2" lists below that.
     */
    fun rankOf(pri: Collection<String>): Int? {
        pri.firstOrNull { it.startsWith("nf") }?.drop(2)?.toIntOrNull()?.let { return it * 500 }
        if (pri.any { it in setOf("news1", "ichi1", "spec1", "spec2", "gai1") }) return 12_000
        if (pri.any { it in setOf("news2", "ichi2", "gai2") }) return 24_000
        return null
    }

    fun open(input: InputStream, gzipped: Boolean): Reader {
        val raw = if (gzipped) GZIPInputStream(input, 1 shl 16) else input
        return EntityStrippingReader(raw.bufferedReader(Charsets.UTF_8))
    }

    /** Builds the tables into an empty database; returns the number of entries. */
    fun build(reader: Reader, db: Sql, progress: (Int) -> Unit = {}): Int {
        SCHEMA.forEach { db.exec(it) }
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        // off by default; some parsers (kxml2) refuse even to be asked
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        parser.setInput(reader)
        var count = 0
        db.transaction {
            var entry: Entry? = null
            var kEle: Ele? = null
            var rEle: Ele? = null
            var sense: Sense? = null
            var lastPos: List<String> = emptyList()
            val text = StringBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        text.setLength(0)
                        when (parser.name) {
                            "entry" -> { entry = Entry(); lastPos = emptyList() }
                            "k_ele" -> kEle = Ele()
                            "r_ele" -> rEle = Ele()
                            "sense" -> sense = Sense()
                        }
                    }
                    XmlPullParser.TEXT -> text.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        val t = text.toString().trim()
                        val e = entry
                        when (parser.name) {
                            "ent_seq" -> e?.id = t.toLong()
                            "keb" -> kEle?.text = t
                            "reb" -> rEle?.text = t
                            "ke_pri", "re_pri" -> (if (parser.name == "ke_pri") kEle else rEle)?.pri?.add(t)
                            "re_nokanji" -> rEle?.nokanji = true
                            "k_ele" -> { kEle?.let { e?.kanji?.add(it) }; kEle = null }
                            "r_ele" -> { rEle?.let { e?.kana?.add(it) }; rEle = null }
                            "pos" -> sense?.pos?.add(t)
                            "misc" -> sense?.misc?.add(t)
                            "gloss" -> sense?.gloss?.add(t)
                            "stagk" -> sense?.stagk?.add(t)
                            "stagr" -> sense?.stagr?.add(t)
                            "sense" -> {
                                sense?.let { s ->
                                    // a sense without <pos> keeps the previous sense's
                                    if (s.pos.isEmpty()) s.pos.addAll(lastPos) else lastPos = s.pos.toList()
                                    e?.senses?.add(s)
                                }
                                sense = null
                            }
                            "entry" -> {
                                e?.let { write(it, db); count++ }
                                if (count % 5000 == 0) progress(count)
                                entry = null
                            }
                        }
                        text.setLength(0)
                    }
                }
                event = parser.next()
            }
        }
        INDEXES.forEach { db.exec(it) }
        db.exec("INSERT INTO meta(key, value) VALUES(?, ?)", listOf("schema", SCHEMA_VERSION))
        db.exec("INSERT INTO meta(key, value) VALUES(?, ?)", listOf("entries", count.toString()))
        progress(count)
        return count
    }

    private fun write(e: Entry, db: Sql) {
        val uk = e.senses.any { "uk" in it.misc }
        val ranks = (e.kanji + e.kana).mapNotNull { rankOf(it.pri) }
        db.exec(
            "INSERT INTO entry(id, rank, uk, has_kanji) VALUES(?, ?, ?, ?)",
            listOf(e.id, ranks.minOrNull(), uk, e.kanji.isNotEmpty()),
        )
        for (k in e.kanji) {
            db.exec("INSERT INTO form(text, entry, kana, rank, nokanji) VALUES(?, ?, 0, ?, 0)", listOf(k.text, e.id, rankOf(k.pri)))
        }
        for (r in e.kana) {
            db.exec("INSERT INTO form(text, entry, kana, rank, nokanji) VALUES(?, ?, 1, ?, ?)", listOf(r.text, e.id, rankOf(r.pri), r.nokanji))
        }
        e.senses.forEachIndexed { i, s ->
            db.exec(
                "INSERT INTO sense(entry, n, pos, misc, gloss, stagk, stagr) VALUES(?, ?, ?, ?, ?, ?, ?)",
                listOf(e.id, i + 1, s.pos.joinToString(","), s.misc.joinToString(","), s.gloss.joinToString("; "),
                    s.stagk.joinToString(","), s.stagr.joinToString(",")),
            )
        }
    }

    private class Entry {
        var id = 0L
        val kanji = ArrayList<Ele>()
        val kana = ArrayList<Ele>()
        val senses = ArrayList<Sense>()
    }

    private class Ele {
        var text = ""
        val pri = ArrayList<String>()
        var nokanji = false
    }

    private class Sense {
        val pos = ArrayList<String>()
        val misc = ArrayList<String>()
        val gloss = ArrayList<String>()
        val stagk = ArrayList<String>()
        val stagr = ArrayList<String>()
    }
}

/**
 * Drops the DOCTYPE (its internal DTD trips up pull parsers) and replaces JMdict's custom
 * entities (&v5k;) with their names; keeps the five XML ones.
 */
internal class EntityStrippingReader(private val source: BufferedReader) : Reader() {
    private var buf = ""
    private var pos = 0
    private var inDoctype = false
    private val entity = Regex("&([A-Za-z][\\w.-]*);")
    private val xml = setOf("amp", "lt", "gt", "quot", "apos")

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        while (pos >= buf.length) {
            val line = source.readLine() ?: return -1
            if (!inDoctype && line.startsWith("<!DOCTYPE")) inDoctype = true
            if (inDoctype) {
                if (line.startsWith("]>")) inDoctype = false
                continue
            }
            buf = entity.replace(line) { m -> if (m.groupValues[1] in xml) m.value else m.groupValues[1] } + "\n"
            pos = 0
        }
        val n = minOf(len, buf.length - pos)
        buf.toCharArray(cbuf, off, pos, pos + n)
        pos += n
        return n
    }

    override fun close() = source.close()
}
