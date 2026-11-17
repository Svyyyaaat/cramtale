package com.cramtale.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android's regex engine (ICU) is stricter than desktop Java's, and the tests here run on desktop Java:
 * a bare "}" or a Java-only class like \p{IsHan} works in every test and then crashes on the phone.
 * This scans every regex in the code and in the data files for those constructs.
 */
class AndroidRegexTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    /** Problems ICU has with [p] that desktop Java doesn't. */
    private fun androidProblems(p: String): List<String> {
        val out = ArrayList<String>()
        if (Regex("""\\p\{(Is|In|java)""").containsMatchIn(p)) out += "Java-only \\p{Is…}/\\p{In…}/\\p{java…} class"
        var i = 0
        var open = -1  // index of an unescaped "{" that may start a quantifier or a \p{…}
        var inClass = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> { if (i + 1 < p.length && p[i + 1] in "pPxN" && p.getOrNull(i + 2) == '{') { open = i + 2; i += 3; continue }; i += 2; continue }
                c == '[' && !inClass -> inClass = true
                c == ']' && inClass -> inClass = false
                c == '{' && !inClass -> open = i
                c == '}' && !inClass -> {
                    val body = if (open >= 0) p.substring(open + 1, i) else null
                    val quantifier = body != null && Regex("""\d+(,\d*)?""").matches(body)
                    val property = open >= 2 && p[open - 2] == '\\'
                    if (!quantifier && !property) out += "bare } at $i"
                    open = -1
                }
            }
            i++
        }
        return out
    }

    private fun kotlinRegexLiterals(): List<Pair<String, String>> {
        val literal = Regex("""(?:Regex|toRegex|Pattern\.compile)\(\s*"((?:[^"\\]|\\.)*)"""")
        return listOf("core/src/main", "app/src/main", "cli/src/main").flatMap { d ->
            File(root, d).walk().filter { it.extension == "kt" }.flatMap { f ->
                literal.findAll(f.readText()).map { m -> "${f.name}: ${m.groupValues[1]}" to m.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"") }
            }
        } + File(root, "core/src/main/kotlin").walk().filter { it.extension == "kt" }.flatMap { f ->
            // raw strings: Regex("""…""")
            Regex("Regex\\(\"\"\"(.*?)\"\"\"").findAll(f.readText()).map { m -> "${f.name}: ${m.groupValues[1]}" to m.groupValues[1] }
        }
    }

    private fun dataRegexes(): List<Pair<String, String>> {
        val banned = File(root, "config/banned.txt").readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.map { "banned.txt: $it" to it }
        val grammar = File(root, "core/src/main/resources/grammar/patterns.tsv").readLines().flatMap { line ->
            line.split("\t").drop(2).joinToString("\t").split(";;").map { it.trim() }.filter { it.startsWith("~") }
                .map { "patterns.tsv: $it" to it.removePrefix("~") }
        }
        return banned + grammar
    }

    @Test
    fun `the checker itself catches what broke the phone`() {
        assertTrue(androidProblems("\\{\\{(\\w+)}}").isNotEmpty(), "the old placeholder regex")
        assertTrue(androidProblems("(?<=\\p{IsHan})x").isNotEmpty())
        assertEquals(emptyList<String>(), androidProblems(".{1,20}a{3}\\p{Han}\\{\\{(\\w+)\\}\\}[{}]"))
    }

    @Test
    fun `no regex in the code or data uses what Android's engine rejects`() {
        val all = kotlinRegexLiterals() + dataRegexes()
        assertTrue(all.size > 30,  "found only ${all.size} regexes: the scan is broken")
        val bad = all.mapNotNull { (where, p) -> androidProblems(p).takeIf { it.isNotEmpty() }?.let { "$where → $it" } }
        assertEquals(emptyList<String>(), bad)
    }
}
