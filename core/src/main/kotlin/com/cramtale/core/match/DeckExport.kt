package com.cramtale.core.match

import org.json.JSONObject

/** Reads the JSON-lines note export written by tools/export_deck.py (same shape the app builds from AnkiDroid). */
object DeckExport {
    fun parse(lines: Sequence<String>): List<RawNote> = lines.filter { it.isNotBlank() }.map { line ->
        val o = JSONObject(line)
        val f = o.getJSONObject("fields")
        val cards = o.getJSONArray("cards")
        RawNote(
            noteId = o.getLong("noteId"),
            deck = o.getString("deck"),
            noteType = o.getString("noteType"),
            fields = f.keys().asSequence().associateWith { f.getString(it) },
            cards = List(cards.length()) { i ->
                val c = cards.getJSONObject(i)
                RawCard(c.getInt("ord"), c.getString("template"), c.getInt("type"), c.getInt("queue"))
            },
            tags = o.optString("tags"),
        )
    }.toList()
}
