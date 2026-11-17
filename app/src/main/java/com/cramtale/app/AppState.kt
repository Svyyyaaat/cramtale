package com.cramtale.app

import android.content.Context
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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
class AppState(val context: Context) {
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
            newSuspendedKnown = prefs.getBoolean("newSuspendedKnown", KnownPolicy().newSuspendedKnown),
        )
        set(p) = prefs.edit()
            .putInt("threshold", p.frequencyThreshold)
            .putBoolean("studiedSuspendedKnown", p.studiedSuspendedKnown)
            .putBoolean("newSuspendedKnown", p.newSuspendedKnown)
            .apply()

    /** Setup steps (0 = welcome); saved so leaving the app resumes the flow. */
    var onboardingStep: Int
        get() = prefs.getInt("onboardingStep", 0)
        set(v) = prefs.edit().putInt("onboardingStep", v).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(v) = prefs.edit().putBoolean("onboardingDone", v).apply()

    /** Optional rule: review cards with an interval under 21 days need two untapped reads. Off by default. */
    var twoReads: Boolean
        get() = prefs.getBoolean("twoReads", false)
        set(v) = prefs.edit().putBoolean("twoReads", v).apply()

    /** Style rules as firm prohibitions instead of positive statements (until the A/B test decides). */
    var firmStyleRules: Boolean
        get() = prefs.getBoolean("firmStyleRules", false)
        set(v) = prefs.edit().putBoolean("firmStyleRules", v).apply()

    enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

    /** Light or dark: follows the phone unless set (Settings → Theme). Compose state, so a change shows at once. */
    var themeMode by androidx.compose.runtime.mutableStateOf(
        runCatching { ThemeMode.valueOf(prefs.getString("themeMode", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM))
        private set

    fun setTheme(m: ThemeMode) { themeMode = m; prefs.edit().putString("themeMode", m.name).apply() }

    /** Colours: the phone's Material You palette (default), or Cramtale's own. */
    var materialYou by androidx.compose.runtime.mutableStateOf(prefs.getBoolean("materialYou", true))
        private set

    fun chooseMaterialYou(on: Boolean) { materialYou = on; prefs.edit().putBoolean("materialYou", on).apply() }

    /** Reading text size, as a multiple of the default. */
    var textScale: Float
        get() = prefs.getFloat("textScale", 1f)
        set(v) = prefs.edit().putFloat("textScale", v).apply()

    var passageLength: com.cramtale.core.story.PassageLength
        get() = runCatching { com.cramtale.core.story.PassageLength.valueOf(prefs.getString("passageLength", "NORMAL")!!) }
            .getOrDefault(com.cramtale.core.story.PassageLength.NORMAL)
        set(v) = prefs.edit().putString("passageLength", v.name).apply()

    /** Japanese text-to-speech (the phone's own engine). */
    val speaker by lazy { com.cramtale.app.speech.Speaker(context) }

    val story by lazy { com.cramtale.app.story.StoryController(context, this) }

    var lists: KnownLists = KnownListsStore.load(listsDir)
        set(v) { field = v; KnownListsStore.save(listsDir, v) }

    private var dict: Jmdict? = null
    private var deckWords: List<DeckWord>? = null
    private var matcher: Matcher? = null
    val frequency: FrequencyList? by lazy { FrequencyList.bundled() }

    private val dictLock = Any()

    /** The dictionary on its own lock: building the matcher (seconds, under the main lock) doesn't block a popup. */
    fun jmdict(): Jmdict? = synchronized(dictLock) {
        if (dict == null && dictionary.isReady()) dict = dictionary.open()
        dict
    }

    @Synchronized
    fun dictionaryRebuilt() { synchronized(dictLock) { dict?.close(); dict = null }; matcher = null }

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

    /**
     * Notes added in Anki since the snapshot: read just those (read-only) and merge them in. Returns the
     * ids that turned out to be vocabulary notes; the others (sentence or grammar cards) aren't.
     */
    fun addNotes(ids: Set<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val t = target ?: return emptySet()
        val reader = AnkiReader(context, t)
        if (!reader.hasPermission()) return emptySet()
        val decks = reader.decks().associate { it.id to it.name }
        val fresh = DeckDefaults.rules.flatMap { rule -> ids.chunked(200).flatMap { reader.notesFor(rule, decks, onlyNotes = it.toSet()) } }
        if (fresh.isEmpty()) return emptySet()
        synchronized(this) {
            val byId = LinkedHashMap<Long, RawNote>()
            if (deckFile.exists()) deckFile.useLines { DeckExport.parse(it) }.forEach { byId[it.noteId] = it }
            fresh.forEach { byId[it.noteId] = it }
            val tmp = File(deckFile.path + ".tmp")
            tmp.bufferedWriter().use { DeckExport.write(byId.values.toList(), it) }
            if (!tmp.renameTo(deckFile)) { deckFile.delete(); tmp.renameTo(deckFile) }
            deckWords = null; matcher = null
        }
        return fresh.map { it.noteId }.toSet()
    }

    @Synchronized
    fun matcher(): Matcher? {
        matcher?.let { return it }
        val d = jmdict() ?: return null
        return Matcher(d, DeckIndex(deck(), d), frequency).also { matcher = it }
    }
}
