package com.cramtale.core.story

import com.cramtale.core.writeAtomic
import java.io.File

/**
 * Prompt and config files: the user's editable copies in [dir] (prompts/, config/) win over the
 * defaults bundled in core's resources. Placeholders are {{name}}: a value that isn't given becomes
 * "(none)", one given as "" disappears (optional sections).
 */
class Prompts(private val dir: File?) {
    fun prompt(name: String) = read("prompts/$name.md") ?: error("missing prompt $name")
    fun config(name: String) = read("config/$name")

    private fun read(path: String): String? =
        dir?.let { File(it, path) }?.takeIf { it.exists() }?.readText()
            ?: Prompts::class.java.getResourceAsStream("/$path")?.use { it.readBytes().decodeToString() }

    /**
     * Copies the bundled defaults into [dir] so they can be edited, and keeps unedited copies up to date:
     * a file whose content is any earlier bundled version (defaults-history.txt) was never touched by the
     * user and gets the new default; anything else is the user's (an edited prompt, a picked style, the
     * story's complications) and is kept. Returns the files kept although their default changed.
     */
    fun installDefaults(names: List<String>): List<String> {
        val d = dir ?: return emptyList()
        val history = HISTORY
        val kept = ArrayList<String>()
        for (n in names) {
            val f = File(d, n)
            val text = Prompts::class.java.getResourceAsStream("/$n")?.use { it.readBytes().decodeToString() } ?: continue
            if (!f.exists()) { f.writeAtomic(text); continue }
            val mine = f.readText()
            if (mine == text) continue
            if ("$n\t${fingerprint(mine)}" in history) f.writeAtomic(text) else kept += n
        }
        return kept
    }

    companion object {
        /** "path<TAB>fingerprint" of every default ever shipped (tools/prompt_history.py). */
        val HISTORY: Set<String> by lazy {
            Prompts::class.java.getResourceAsStream("/defaults-history.txt")?.use { it.readBytes().decodeToString() }
                ?.lines()?.filter { it.isNotBlank() }?.toSet().orEmpty()
        }

        fun fingerprint(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

        val PROMPTS = listOf("writer", "turn", "director", "adjudicator", "mistakes", "scribe", "offscreen", "judge",
            "leak", "rewrite", "summary", "meaning", "style_picker", "setup", "intent").map { "prompts/$it.md" }
        val CONFIG = listOf("plot.md", "banned.txt", "complications.md", "style_rules_positive.md", "style_rules_firm.md",
            "style_sample.md", "style_line.txt").map { "config/$it" }

        // every brace escaped: Android's regex engine (ICU) rejects a bare "}" that desktop Java accepts
        private val placeholder = Regex("\\{\\{(\\w+)\\}\\}")

        fun fill(template: String, values: Map<String, String?>): String =
            placeholder.replace(template) { m ->
                val k = m.groupValues[1]
                if (k in values) values[k].orEmpty() else "(none)"
            }
    }
}
