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

    /** Copies the bundled defaults into [dir] where the user has no copy yet, so they can be edited. */
    fun installDefaults(names: List<String>) {
        val d = dir ?: return
        for (n in names) {
            val f = File(d, n)
            if (f.exists()) continue
            val text = Prompts::class.java.getResourceAsStream("/$n")?.use { it.readBytes().decodeToString() } ?: continue
            f.parentFile.mkdirs()
            f.writeAtomic(text)
        }
    }

    companion object {
        val PROMPTS = listOf("writer", "turn", "director", "adjudicator", "mistakes", "scribe", "offscreen", "judge",
            "leak", "rewrite", "summary", "meaning", "style_picker", "setup").map { "prompts/$it.md" }
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
