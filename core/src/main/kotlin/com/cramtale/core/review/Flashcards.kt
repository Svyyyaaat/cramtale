package com.cramtale.core.review

import com.cramtale.core.writeAtomic
import org.json.JSONObject
import java.io.File

/**
 * Cards answered as flashcards while a passage was being prepared. Each is one answer (the would-be grade
 * goes to the grade log), so it stays out of the story's reviews for the rest of Anki's day.
 */
class Flashcards(private val file: File?, private val dayStart: () -> Long = { GradeLog.ankiDayStart() }) {
    private val answered = HashMap<String, Long>()

    init {
        runCatching { file?.takeIf { it.exists() }?.readText()?.let { JSONObject(it) }?.let { o -> o.keys().forEach { answered[it] = o.getLong(it) } } }
    }

    @Synchronized fun answeredToday(ref: CardRef): Boolean = (answered[ref.key] ?: 0) >= dayStart()

    /** Any card of this note answered as a flashcard today (a tap on it in a passage then doesn't count either). */
    @Synchronized fun noteAnsweredToday(noteId: Long): Boolean {
        val start = dayStart()
        return answered.any { (k, at) -> at >= start && k.substringBefore(':').toLongOrNull() == noteId }
    }

    /** The queue minus what was answered today and what's already in the passage being written. */
    @Synchronized fun pick(snapshot: DueSnapshot, inPassage: Set<Long>, limit: Int = 30): List<DueCard> =
        snapshot.cards.filter { !answeredToday(it.ref) && it.ref.noteId !in inPassage }.take(limit)

    /** Records the answer: [good] (or Again) for a review; new cards are only triaged, not graded. */
    @Synchronized fun answer(card: DueCard, good: Boolean, log: GradeLog?) {
        val start = dayStart()
        answered.entries.removeIf { it.value < start }
        answered[card.ref.key] = System.currentTimeMillis()
        file?.writeAtomic(JSONObject(answered as Map<*, *>).toString())
        if (!card.isNew) log?.record(GradingEngine.Outcome(
            Grade(card.ref, card.word.word, if (good) 3 else 1, if (good) "flashcard: Good" else "flashcard: Again", card.info, 0),
            sent = false, note = "flashcard while a passage was written (read-only: not sent)",
        ), Mode.READ_ONLY)
    }
}
