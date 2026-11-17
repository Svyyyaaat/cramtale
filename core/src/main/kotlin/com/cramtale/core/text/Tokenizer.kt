package com.cramtale.core.text

/**
 * One Kuromoji (IPADIC) token. [base] is the dictionary form (食べ → 食べる), or the surface
 * when IPADIC has none; [reading] is the katakana reading of the surface, "" when unknown.
 */
data class Tok(
    val surface: String,
    val start: Int,
    val pos1: String,
    val pos2: String,
    val pos3: String,
    val base: String,
    val reading: String,
    /** False for words IPADIC doesn't know (it guessed the boundaries). */
    val inDictionary: Boolean,
) {
    val end get() = start + surface.length

    /** Punctuation, spaces, Latin text, digits: never words. */
    val isSymbol get() = pos1 == "記号" || !Kana.hasJapanese(surface)
}

object Tokenizer {
    private val kuromoji by lazy { com.atilika.kuromoji.ipadic.Tokenizer() }

    /** Slow on the first call (loads the dictionary); call off the main thread. */
    fun tokenize(text: String): List<Tok> = kuromoji.tokenize(text).map { t ->
        Tok(
            surface = t.surface,
            start = t.position,
            pos1 = t.partOfSpeechLevel1,
            pos2 = t.partOfSpeechLevel2,
            pos3 = t.partOfSpeechLevel3,
            base = t.baseForm.takeUnless { it == "*" || it.isBlank() } ?: t.surface,
            reading = t.reading.takeUnless { it == "*" } ?: "",
            inDictionary = t.isKnown,
        )
    }
}
