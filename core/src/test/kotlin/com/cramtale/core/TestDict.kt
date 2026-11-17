package com.cramtale.core

import com.cramtale.core.dict.JdbcSql
import com.cramtale.core.dict.Jmdict
import com.cramtale.core.dict.JmdictBuilder
import com.cramtale.core.match.CardState
import com.cramtale.core.match.DeckWord
import com.cramtale.core.match.RawCard
import java.io.File

object TestDict {
    /** JMdict built from the checked-in fixture (real entries, CC BY-SA 4.0, EDRDG). */
    val fixture: Jmdict by lazy {
        val f = File.createTempFile("jmdict-fixture", ".sqlite").apply { delete(); deleteOnExit() }
        val db = JdbcSql(f.path)
        val stream = TestDict::class.java.getResourceAsStream("/jmdict_fixture.xml")!!
        JmdictBuilder.build(JmdictBuilder.open(stream, gzipped = false), db)
        Jmdict(db)
    }

    /** Real data, only on a machine that has it (local/ is gitignored). */
    val dataDir: File? = System.getProperty("cramtale.data")?.let(::File)?.takeIf { it.isDirectory }

    val full: Jmdict? by lazy {
        dataDir?.let { File(it, "jmdict.sqlite") }?.takeIf { it.exists() }?.let { Jmdict(JdbcSql(it.path)) }
    }

    fun text(name: String) = TestDict::class.java.getResource("/texts/$name")!!.readText()

    fun word(word: String, state: CardState = CardState.STUDIED, reading: String? = null, id: Long = word.hashCode().toLong()) =
        DeckWord(id, "test", "Japanese Core 5k", word, reading, state, listOf(RawCard(0, "Recognition", 2, 2)))
}
