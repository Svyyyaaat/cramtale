package com.cramtale.app.anki

import android.content.Context
import android.os.Build
import com.cramtale.core.match.DeckDefaults

/**
 * Read-only checks of what this AnkiDroid's provider actually offers, as a text report to
 * copy and send back. Settles the open points in PLAN.md (§4.6, §10) on the real phone.
 */
object Probe {
    private val CARD_COLUMNS = listOf(
        "note_id", "ord", "card_name", "deck_id", "type", "queue", "due", "reps", "ivl", "lapses", "left", "odue", "factor", "flags",
    )

    fun run(context: Context, target: AnkiTarget): String = buildString {
        fun line(s: String = "") = appendLine(s)
        fun step(name: String, block: () -> Unit) {
            try { block() } catch (e: Throwable) { line("$name: FAILED ${e.javaClass.simpleName}: ${e.message}") }
        }
        line("Cramtale probe  ${java.time.LocalDateTime.now()}")
        line("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        line()
        line("Installed AnkiDroid builds:")
        for (i in AnkiReader.installed(context)) {
            line("  ${i.target.packageName}  v${i.versionName}  provider ${if (i.providerFound) "found" else "MISSING"} " +
                "(${i.target.authority})  permission ${if (i.granted) "granted" else "not granted"}")
        }
        line()
        line("Target: ${target.packageName}${if (target.isReal) "  (REAL collection)" else "  (copy)"}")
        val r = AnkiReader(context, target)
        if (!r.hasPermission()) { line("No permission for ${target.permission}: grant it first."); return@buildString }

        var decks = emptyList<Deck>()
        step("decks") {
            decks = r.decks()
            line("Decks (${decks.size}), learn/review/new due now:")
            decks.forEach { line("  ${it.name}  ${it.learn}/${it.review}/${it.new}  id ${it.id}") }
            val marker = decks.any { it.name == AnkiTarget.SANDBOX_MARKER_DECK }
            line("Sandbox marker deck ${AnkiTarget.SANDBOX_MARKER_DECK}: ${if (marker) "PRESENT" else "absent"}")
        }
        line()
        step("note types") {
            val types = r.noteTypes()
            line("Note types (${types.size}):")
            for (t in types) {
                val tm = runCatching { r.templates(t.id) }.getOrNull()
                line("  ${t.name}  fields ${t.fields}  templates ${tm?.toSortedMap()?.values ?: "?"}")
            }
        }
        line()
        for (rule in DeckDefaults.rules) step("search ${rule.noteType}") {
            val q = "note:\"${rule.noteType}\""
            line("${rule.noteType}: ${r.search(q).size} notes, ${r.search("$q is:new").size} with a new card, " +
                "${r.search("$q is:suspended").size} with a suspended card, ${r.search("$q is:due").size} with a due card")
        }
        line()
        step("card columns") {
            val noteId = r.search("note:\"Japanese Core 5k\" -is:new").firstOrNull()
                ?: r.search("-is:new").firstOrNull() ?: error("no studied note")
            val uri = r.cardUri(noteId, 0)
            val defaults = r.query(uri, null, null) { c -> c.columnNames.toList() }.firstOrNull()
            line("Card $noteId/0 default columns: $defaults")
            val ok = ArrayList<String>()
            val missing = ArrayList<String>()
            for (col in CARD_COLUMNS) {
                val v = runCatching { r.query(uri, arrayOf(col), null) { c -> c.getString(0) }.firstOrNull() }
                if (v.isSuccess) ok += "$col=${v.getOrNull()}" else missing += col
            }
            line("  readable: ${ok.joinToString("  ")}")
            line("  not readable: $missing")
        }
        line()
        step("schedule") {
            val deck = decks.firstOrNull { it.name.endsWith("Core 5k") } ?: decks.maxByOrNull { it.review + it.learn + it.new }
                ?: error("no deck")
            val q = r.query(r.scheduleUri, null, "limit=?,deckID=?", arrayOf("5", deck.id.toString())) { c ->
                c.columnNames.joinToString(",") + " | " + (0 until c.columnCount).joinToString(",") { i -> runCatching { c.getString(i) }.getOrDefault("?") }
            }
            line("Schedule queue sample for ${deck.name} (${q.size} rows):")
            q.forEach { line("  $it") }
        }
        line()
        line("Not probed (needs a write, step 3, sandbox only): note delete support.")
    }
}
