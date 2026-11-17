package com.cramtale.core

import com.cramtale.core.story.Prompts
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptsTest {
    private fun bundled(n: String) = Prompts::class.java.getResourceAsStream("/$n")!!.use { it.readBytes().decodeToString() }

    @Test
    fun `every current default is in the history (run tools-prompt_history-py after editing a prompt)`() {
        val missing = (Prompts.PROMPTS + Prompts.CONFIG).filter { "$it\t${Prompts.fingerprint(bundled(it))}" !in Prompts.HISTORY }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `an untouched old default is updated, an edited file is kept`() {
        val dir = Files.createTempDirectory("prompts").toFile()
        val p = Prompts(dir)
        p.installDefaults(Prompts.PROMPTS + Prompts.CONFIG)
        val turn = File(dir, "prompts/turn.md")
        val sample = File(dir, "config/style_sample.md")
        assertEquals(bundled("prompts/turn.md"), turn.readText())

        // a phone with an older shipped default: a real earlier version of a prompt, from git
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val older = gitVersions(root, "prompts/writer.md").firstOrNull { it != bundled("prompts/writer.md") }
        if (older != null) {
            val writer = File(dir, "prompts/writer.md")
            writer.writeText(older)
            p.installDefaults(Prompts.PROMPTS + Prompts.CONFIG)
            assertEquals(bundled("prompts/writer.md"), writer.readText(), "an untouched old default follows the update")
        }

        // the user's edit and a picked style are kept
        turn.writeText("my own prompt")
        sample.writeText("私の好きな文体。")
        val kept = p.installDefaults(Prompts.PROMPTS + Prompts.CONFIG)
        assertEquals("my own prompt", turn.readText())
        assertEquals("私の好きな文体。", sample.readText())
        assertTrue("prompts/turn.md" in kept)

    }

    private fun gitVersions(root: File, path: String): List<String> = runCatching {
        val revs = ProcessBuilder("git", "log", "--format=%H", "--", path).directory(root).start().inputStream.bufferedReader().readLines()
        revs.mapNotNull { r ->
            val pr = ProcessBuilder("git", "show", "$r:$path").directory(root).start()
            pr.inputStream.readBytes().decodeToString().takeIf { pr.waitFor() == 0 }
        }
    }.getOrDefault(emptyList())
}
