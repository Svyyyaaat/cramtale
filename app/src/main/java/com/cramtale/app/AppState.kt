package com.cramtale.app

import android.content.Context
import com.cramtale.app.anki.AnkiReader
import com.cramtale.app.anki.AnkiTarget
import com.cramtale.app.dict.DictionarySetup
import com.cramtale.core.dict.Jmdict
import com.cramtale.core.match.DeckDefaults
import com.cramtale.core.match.DeckExport
import com.cramtale.core.match.DeckIndex
import com.cramtale.core.match.DeckReader
import com.cramtale.core.match.DeckWord
import com.cramtale.core.match.FrequencyList
import com.cramtale.core.match.KnownLists
import com.cramtale.core.match.KnownListsStore
import com.cramtale.core.match.KnownPolicy
import com.cramtale.core.match.Matcher
import com.cramtale.core.match.RawNote
import java.io.File

/** Settings, the dictionary, the deck snapshot and the user's lists. Heavy calls: off the main thread. */
class AppState(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val dictionary = DictionarySetup(context)
    private val listsDir = File(context.filesDir, "lists")
    private val deckFile = File(context.filesDir, "deck.jsonl")

    var targetPackage: String?
        get() = prefs.getString("target", null)
        set(v) = prefs.edit().putString("target", v).apply()

    val target: AnkiTarget? get() = targetPackage?.let(::AnkiTarget)

    var policy: KnownPolicy
        get() = KnownPolicy(
            frequencyThreshold = prefs.getInt("threshold", KnownPolicy().frequencyThreshold),
            studiedSuspendedKnown = prefs.getBoolean("studiedSuspendedKnown", true),
            newSuspendedKnown = prefs.getBoolean("newSuspendedKnown", false),
        )
        set(p) = prefs.edit()
            .putInt("threshold", p.frequencyThreshold)
            .putBoolean("studiedSuspendedKnown", p.studiedSuspendedKnown)
            .putBoolean("newSuspendedKnown", p.newSuspendedKnown)
            .apply()

    var lists: KnownLists = KnownListsStore.load(listsDir)
        set(v) { field = v; KnownListsStore.save(listsDir, v) }

    private var dict: Jmdict? = null
    private var deckWords: List<DeckWord>? = null
    private var matcher: Matcher? = null
    val frequency: FrequencyList? by lazy { FrequencyList.bundled() }

    @Synchronized
    fun jmdict(): Jmdict? {
        if (dict == null && dictionary.isReady()) dict = dictionary.open()
        return dict
    }

    @Synchronized
    fun dictionaryRebuilt() { dict?.close(); dict = null; matcher = null }

    @Synchronized
    fun deck(): List<DeckWord> {
        deckWords?.let { return it }
        val notes = if (deckFile.exists()) deckFile.useLines { DeckExport.parse(it) } else emptyList()
        return DeckReader.words(notes).also { deckWords = it }
    }

    val deckSnapshotTime: Long? get() = deckFile.takeIf { it.exists() }?.lastModified()

    /** Reads the deck words from AnkiDroid (read-only) and keeps a snapshot on the phone. */
    fun reloadDeck(progress: (String) -> Unit): List<DeckWord> {
        val t = target ?: error("Pick an AnkiDroid first")
        val reader = AnkiReader(context, t)
        if (!reader.hasPermission()) error("Grant AnkiDroid access first")
        val decks = reader.decks().associate { it.id to it.name }
        val notes = ArrayList<RawNote>()
        for (rule in DeckDefaults.rules) notes += reader.notesFor(rule, decks, progress)
        deckFile.bufferedWriter().use { DeckExport.write(notes, it) }
        synchronized(this) { deckWords = null; matcher = null }
        return deck()
    }

    @Synchronized
    fun matcher(): Matcher? {
        matcher?.let { return it }
        val d = jmdict() ?: return null
        return Matcher(d, DeckIndex(deck(), d), frequency).also { matcher = it }
    }
}
