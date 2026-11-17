package com.cramtale.core.text

object Kana {
    fun kataToHira(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }

    fun isKanji(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々' || c == '〆' || c == 'ヶ'

    fun hasKanji(s: String) = s.any(::isKanji)

    fun isKanaChar(c: Char) = c in 'ぁ'..'ゟ' || c in '゠'..'ヿ' || c == 'ー'

    fun isKana(s: String) = s.isNotEmpty() && s.all(::isKanaChar)

    /** Any kana or kanji at all: lets the matcher skip Latin text, digits and punctuation. */
    fun hasJapanese(s: String) = s.any { isKanji(it) || isKanaChar(it) }
}
