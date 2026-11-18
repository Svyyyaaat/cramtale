package com.cramtale.core

import com.cramtale.core.llm.ClaudeCodeLlm
import com.cramtale.core.llm.CostLog
import com.cramtale.core.llm.LlmException
import com.cramtale.core.llm.LlmRequest
import com.cramtale.core.llm.Models
import com.cramtale.core.llm.Msg
import com.cramtale.core.llm.Prices
import com.cramtale.core.llm.Role
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Termux backend: claude_call.py run for real, with a stand-in `claude` that replays a recorded stream. */
class ClaudeCodeTest {
    private val script = listOf("../app/src/main/assets/termux/claude_call.py", "app/src/main/assets/termux/claude_call.py")
        .map(::File).first { it.exists() }
    private val tmp = Files.createTempDirectory("cc").toFile()

    /** A fake `claude` on PATH printing [lines]; it saves its arguments and stdin for the test. */
    private fun fakeClaude(lines: List<String>): File {
        val bin = File(tmp, "bin").apply { mkdirs() }
        File(tmp, "out.jsonl").writeText(lines.joinToString("\n") + "\n")
        File(bin, "claude").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" > ${tmp}/args.txt\ncat > ${tmp}/stdin.txt\ncat ${tmp}/out.jsonl\n")
            setExecutable(true)
        }
        return bin
    }

    private fun runner(bin: File): (org.json.JSONObject, ((String) -> Unit)?) -> String = { request, onText ->
        val req = File(tmp, "req.json").apply { writeText(request.toString()) }
        val stream = File(tmp, "stream.txt").apply { delete() }
        val p = ProcessBuilder("python3", script.path, req.path, stream.path)
            .apply { environment()["PATH"] = bin.path + ":" + System.getenv("PATH"); environment()["HOME"] = tmp.path }
            .redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        onText?.invoke(stream.readText())
        out
    }

    private fun delta(json: String) = """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":${org.json.JSONObject.quote(json)}}}}"""

    @Test
    fun `a structured call streams its JSON and returns it`() {
        val bin = fakeClaude(listOf(
            """{"type":"system","subtype":"init"}""",
            delta("""{"passage": "雨が"""), delta("""降った。"}"""),
            """{"type":"result","subtype":"success","is_error":false,"stop_reason":"tool_use","structured_output":{"passage":"雨が降った。"},
               "usage":{"input_tokens":5,"output_tokens":20,"cache_read_input_tokens":100,"cache_creation_input_tokens":7}}""".replace("\n", ""),
        ))
        val llm = ClaudeCodeLlm(runner(bin))
        val pieces = StringBuilder()
        val req = LlmRequest("writer", Models.WRITER, listOf(Msg(Role.SYSTEM, "You narrate.")),
            listOf(Msg(Role.USER, "Begin."), Msg(Role.ASSISTANT, "前の話。"), Msg(Role.USER, "Go on.")),
            jsonSchema = """{"type":"object"}""")
        val r = llm.stream(req) { pieces.append(it) }
        assertEquals("雨が降った。", org.json.JSONObject(r.text).getString("passage"))
        assertEquals("""{"passage": "雨が降った。"}""", pieces.toString())
        assertEquals("end_turn", r.stopReason)
        assertEquals(100, r.usage.cacheRead)
        val args = File(tmp, "args.txt").readLines()
        assertTrue("--json-schema" in args && "--system-prompt" in args && Models.WRITER in args, args.toString())
        assertEquals("You narrate.", args[args.indexOf("--system-prompt") + 1])
        val stdin = File(tmp, "stdin.txt").readText()
        assertTrue("<assistant>\n前の話。\n</assistant>" in stdin && stdin.trimEnd().endsWith("Go on."), stdin)
        // the subscription pays: nothing in dollars
        val costs = CostLog(File(tmp, "costs.jsonl"), Prices.DEFAULT)
        assertEquals(0.0, costs.record("s", req, r).dollars)
    }

    @Test
    fun `failures say what to do`() {
        val bin = fakeClaude(listOf("""{"type":"result","subtype":"success","is_error":true,"result":"Invalid API key · Please run /login"}"""))
        val e = assertFailsWith<LlmException> {
            ClaudeCodeLlm(runner(bin)).call(LlmRequest("judge", Models.CHECKS, emptyList(), listOf(Msg(Role.USER, "x"))))
        }
        assertTrue("log in" in e.message!!, e.message)
        val args = File(tmp, "args.txt").readLines()
        assertTrue("--effort" !in args, "Haiku gets no effort")
    }

    @Test
    fun `a system prompt too long for one argument goes in with the prompt`() {
        val bin = fakeClaude(listOf("""{"type":"result","subtype":"success","is_error":false,"result":"ok","usage":{}}"""))
        val big = "長い指示。".repeat(10_000)
        ClaudeCodeLlm(runner(bin)).call(LlmRequest("writer", Models.WRITER, listOf(Msg(Role.SYSTEM, big)), listOf(Msg(Role.USER, "Go."))))
        assertTrue(File(tmp, "stdin.txt").readText().startsWith("<instructions>\n長い指示。"))
        assertTrue(File(tmp, "args.txt").readText().length < 5000)
    }

    @Test
    fun `plain text calls take text deltas`() {
        val bin = fakeClaude(listOf(
            """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"静かな"}}}""",
            """{"type":"result","subtype":"success","is_error":false,"stop_reason":"end_turn","result":"静かな夜。","usage":{}}""",
        ))
        val r = ClaudeCodeLlm(runner(bin)).stream(LlmRequest("summary", Models.WRITER, emptyList(), listOf(Msg(Role.USER, "x")))) {}
        assertEquals("静かな夜。", r.text)
        assertTrue("--json-schema" !in File(tmp, "args.txt").readLines())
    }
}
