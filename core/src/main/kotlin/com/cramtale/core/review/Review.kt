package com.cramtale.core.review

import com.cramtale.core.writeAtomic
import com.cramtale.core.match.DeckWord
import org.json.JSONObject
import java.io.File

/** One recognition card. */
data class CardRef(val noteId: Long, val ord: Int) {
    val key get() = "$noteId:$ord"
}

/**
 * A card as AnkiDroid reports it. type: 0 new, 1 learning, 2 review, 3 relearning.
 * queue: 0 new, 1 learning, 2 review, 3 day-learning, <0 suspended/buried.
 * due: epoch seconds for intraday learning (queue 1), a day number otherwise.
 * reps / ivl are -1 when this AnkiDroid doesn't report them.
 */
data class CardInfo(val type: Int, val queue: Int, val due: Long, val reps: Int = -1, val ivl: Int = -1) {
    val isNew get() = type == 0
    val suspendedOrBuried get() = queue < 0
}

/** What the review engine needs from AnkiDroid. Reads only; answers go through [AnkiAnswers]. */
interface AnkiSource {
    /** The scheduler's queue for a deck (Anki applies its own limits). */
    fun queue(deckId: Long, limit: Int): List<CardRef>
    fun card(ref: CardRef): CardInfo?
    fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}

/** Sends real answers (grading mode, step 4). Read-only mode never gets one. */
fun interface AnkiAnswers {
    fun answer(ref: CardRef, ease: Int, timeMs: Long): Boolean
}

enum class Mode { READ_ONLY, ADD_ONLY, GRADING }

/** A card Anki says is due right now, with the word it tests. */
data class DueCard(val ref: CardRef, val word: DeckWord, val info: CardInfo) {
    val isNew get() = info.isNew
}

/**
 * Taken before every passage (PLAN.md §4.2): the scheduler's queue for each review deck, kept to
 * recognition cards, with learning cards counted only once their step delay has really passed
 * (no learn-ahead).
 */
class DueSnapshot(val cards: List<DueCard>, val takenAt: Long) {
    val byNote: Map<Long, List<DueCard>> = cards.groupBy { it.ref.noteId }

    companion object {
        const val QUEUE_LIMIT = 2000

        fun take(source: AnkiSource, deckIds: List<Long>, words: List<DeckWord>): DueSnapshot {
            val byNote = words.associateBy { it.noteId }
            val now = source.nowSeconds()
            val out = LinkedHashMap<String, DueCard>()
            for (deck in deckIds) for (ref in source.queue(deck, QUEUE_LIMIT)) {
                val w = byNote[ref.noteId] ?: continue
                if (w.cards.none { it.ord == ref.ord }) continue // not a recognition card
                val info = source.card(ref) ?: continue
                if (info.suspendedOrBuried) continue
                if (info.queue == 1 && info.due > now) continue // learning step not over yet
                out.putIfAbsent(ref.key, DueCard(ref, w, info))
            }
            return DueSnapshot(out.values.toList(), now)
        }
    }
}

/** What happened in one committed passage, as far as grading cares. */
data class PassageRead(
    val passageId: String,
    /** Notes whose word the matcher found in the passage (words, and grammar the writer also declared). */
    val notesInText: Set<Long>,
    /** Notes of deck words the reader tapped and didn't cancel with "I know it", in any version of this turn. */
    val tappedNotes: Set<Long>,
    /** Every deck word tapped, due or not, with its cards (for "tapped but not due = Again"). */
    val tappedWords: List<DeckWord>,
    val readMs: Long,
)

/** One answer the app sends, or would send in read-only mode. */
data class Grade(
    val ref: CardRef,
    val word: String,
    /** 1 Again, 3 Good. */
    val ease: Int,
    val reason: String,
    val before: CardInfo,
    val timeMs: Long,
)

/**
 * The grading rules of PLAN.md §4.3. Pure decisions: [decide] says what to send; [commit] re-reads
 * every card, drops the ones that changed since the snapshot, and sends or logs the rest.
 */
class GradingEngine(
    private val twoReads: Boolean = false,
    private val pendingReads: PendingReads = PendingReads(null),
) {
    fun decide(snapshot: DueSnapshot, read: PassageRead, cardInfo: (CardRef) -> CardInfo?): List<Grade> {
        val grades = ArrayList<Grade>()
        val graded = HashSet<String>()
        // due cards whose word was in the passage
        for (due in snapshot.cards) {
            if (due.ref.noteId !in read.notesInText) continue
            val tapped = due.ref.noteId in read.tappedNotes
            val ease: Int
            val reason: String
            when {
                due.isNew -> { ease = 1; reason = "new card, first read (meaning shown inline)" }
                tapped -> { ease = 1; reason = "due, tapped" }
                twoReads && due.info.type == 2 && due.info.ivl in 0 until 21 && !pendingReads.firstReadDone(due.ref, read.passageId) -> {
                    pendingReads.recordFirst(due.ref, read.passageId)
                    continue // first of two untapped reads: nothing sent yet
                }
                else -> { ease = 3; reason = "due, read without a tap" }
            }
            pendingReads.clear(due.ref)
            graded += due.ref.key
            grades += Grade(due.ref, due.word.word, ease, reason, due.info, 0)
        }
        // tapped deck words that weren't due: Again right away, except never-studied cards Anki hasn't offered
        for (w in read.tappedWords) for (c in w.cards) {
            val ref = CardRef(w.noteId, c.ord)
            if (ref.key in graded) continue
            val info = cardInfo(ref) ?: continue
            if (info.suspendedOrBuried || info.isNew) continue
            graded += ref.key
            pendingReads.clear(ref)
            grades += Grade(ref, w.word, 1, "tapped, not due", info, 0)
        }
        val per = if (grades.isEmpty()) 0 else (read.readMs / grades.size).coerceIn(1_000, 60_000)
        return grades.map { it.copy(timeMs = per) }
    }

    data class Outcome(val grade: Grade, val sent: Boolean, val note: String)

    /**
     * Re-reads each card just before sending: if it changed since the snapshot (reviewed in
     * AnkiDroid meanwhile), nothing is sent. In read-only and add-only mode nothing is ever sent;
     * the log records what would have been. In grading mode a daily cap applies.
     */
    fun commit(
        grades: List<Grade>, mode: Mode, source: AnkiSource, answers: AnkiAnswers?, log: GradeLog,
        dailyCap: Int = 150,
    ): List<Outcome> = grades.map { g ->
        val now = source.card(g.ref)
        val outcome = when {
            now == null -> Outcome(g, false, "card not readable")
            now.type != g.before.type || now.queue != g.before.queue || now.due != g.before.due ||
                (now.reps >= 0 && g.before.reps >= 0 && now.reps != g.before.reps) -> Outcome(g, false, "changed since the passage started")
            mode != Mode.GRADING || answers == null -> Outcome(g, false, "dry run")
            log.sentToday() >= dailyCap -> Outcome(g, false, "daily cap reached")
            else -> {
                val ok = answers.answer(g.ref, g.ease, g.timeMs)
                Outcome(g, ok, if (ok) "sent" else "AnkiDroid didn't record it")
            }
        }
        log.record(outcome, mode)
        outcome
    }
}

/** Optional two-read rule: the first untapped read of a young review card, waiting for a second passage. */
class PendingReads(private val file: File?) {
    private val firsts: MutableMap<String, String> = HashMap<String, String>().apply {
        file?.takeIf { it.exists() }?.let { f -> val o = JSONObject(f.readText()); o.keys().forEach { put(it, o.getString(it)) } }
    }

    fun firstReadDone(ref: CardRef, passageId: String): Boolean = firsts[ref.key]?.let { it != passageId } == true
    fun recordFirst(ref: CardRef, passageId: String) { firsts[ref.key] = passageId; save() }
    fun clear(ref: CardRef) { if (firsts.remove(ref.key) != null) save() }
    private fun save() { file?.writeAtomic(JSONObject(firsts as Map<*, *>).toString()) }
}

/**
 * Every answer sent or that would have been sent, one JSON line each (PLAN.md §4.5), with the
 * card's state before. The dry-run report reads it back.
 */
class GradeLog(private val file: File?) {
    val entries = ArrayList<JSONObject>()

    init {
        file?.takeIf { it.exists() }?.forEachLine { if (it.isNotBlank()) entries += JSONObject(it) }
    }

    fun record(o: GradingEngine.Outcome, mode: Mode) {
        val g = o.grade
        val e = JSONObject()
            .put("at", System.currentTimeMillis()).put("mode", mode.name).put("noteId", g.ref.noteId).put("ord", g.ref.ord)
            .put("word", g.word).put("ease", g.ease).put("reason", g.reason).put("timeMs", g.timeMs)
            .put("sent", o.sent).put("note", o.note)
            .put("before", JSONObject().put("type", g.before.type).put("queue", g.before.queue).put("due", g.before.due).put("reps", g.before.reps).put("ivl", g.before.ivl))
        entries += e
        file?.let { it.parentFile?.mkdirs(); it.appendText(e.toString() + "\n") }
    }

    fun sentToday(): Int {
        val dayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return entries.count { it.optBoolean("sent") && it.optLong("at") >= dayStart }
    }
}
