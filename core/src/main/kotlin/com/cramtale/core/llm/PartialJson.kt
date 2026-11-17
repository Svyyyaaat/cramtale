package com.cramtale.core.llm

/** Reading a JSON reply while it streams in, before it is complete. */
object PartialJson {
    /**
     * The value of string field [key] so far ("…" in `{"passage":"…`), unescaped; null before the field
     * starts. An escape cut off at the end of [text] is left out until the rest arrives.
     */
    fun stringField(text: String, key: String): String? {
        val k = text.indexOf("\"$key\"")
        if (k < 0) return null
        var i = k + key.length + 2
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != ':') return null
        i++
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '"') return null
        i++
        val out = StringBuilder()
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    if (i + 1 >= text.length) return out.toString()
                    when (val e = text[i + 1]) {
                        'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                        'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                        'u' -> {
                            if (i + 6 > text.length) return out.toString()
                            out.append(text.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: '?')
                            i += 4
                        }
                        else -> out.append(e)
                    }
                    i += 2
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
