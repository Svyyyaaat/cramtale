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

    /** The same JSON-lines shape, so a deck read from AnkiDroid can be cached and reloaded. */
    fun write(notes: List<RawNote>, out: Appendable) {
        for (n in notes) {
            val o = JSONObject()
            o.put("noteId", n.noteId).put("deck", n.deck).put("noteType", n.noteType).put("tags", n.tags)
            o.put("fields", JSONObject(n.fields))
            o.put("cards", org.json.JSONArray(n.cards.map {
                JSONObject().put("ord", it.ord).put("template", it.template).put("type", it.type).put("queue", it.queue)
            }))
            out.append(o.toString()).append('\n')
        }
    }
}
