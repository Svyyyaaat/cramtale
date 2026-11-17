package com.cramtale.core

import com.cramtale.core.llm.AnthropicLlm
import com.cramtale.core.llm.LlmRequest
import com.cramtale.core.llm.Models
import com.cramtale.core.llm.Msg
import com.cramtale.core.llm.Role
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What actually goes over the wire, checked against a local server (no API key needed). */
class AnthropicLlmWireTest {
    private fun withServer(block: (String, () -> Pair<JSONObject, Map<String, List<String>>>) -> Unit) {
        var body: JSONObject? = null
        var headers: Map<String, List<String>> = emptyMap()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            body = JSONObject(ex.requestBody.readBytes().decodeToString())
            headers = ex.requestHeaders.mapKeys { it.key.lowercase() }
            val reply = """{"id":"msg_1","type":"message","role":"assistant","model":"${body!!.getString("model")}",
                "content":[{"type":"text","text":"こんにちは"}],"stop_reason":"end_turn","stop_sequence":null,
                "usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":100,"cache_creation_input_tokens":0}}"""
            val bytes = reply.toByteArray()
            ex.responseHeaders.add("content-type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}") { body!! to headers }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `writer request - cached system, mid-conversation system message, low effort, fallback`() = withServer { url, seen ->
        val llm = AnthropicLlm("test-key", baseUrl = url)
        val resp = llm.call(
            LlmRequest(
                purpose = "writer", model = Models.WRITER,
                system = listOf(Msg(Role.SYSTEM, "GM frame", cache = true)),
                messages = listOf(
                    Msg(Role.USER, "start"), Msg(Role.ASSISTANT, "passage"),
                    Msg(Role.USER, "reply"), Msg(Role.SYSTEM, "scene packet"),
                ),
            ),
        )
        assertEquals("こんにちは", resp.text)
        assertEquals(100, resp.usage.cacheRead)
        val (body, headers) = seen()
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertEquals("ephemeral", body.getJSONArray("system").getJSONObject(0).getJSONObject("cache_control").getString("type"))
        assertEquals("ephemeral", body.getJSONObject("cache_control").getString("type"))
        val roles = (0 until body.getJSONArray("messages").length()).map { body.getJSONArray("messages").getJSONObject(it).getString("role") }
        assertEquals(listOf("user", "assistant", "user", "system"), roles)
        assertEquals("default", body.getString("fallbacks"))
        assertTrue(headers["anthropic-beta"].orEmpty().any { "server-side-fallback" in it })
        assertFalse(body.has("thinking"), "Opus 5.5 can't turn thinking off")
        assertFalse(body.has("temperature"))
    }

    @Test
    fun `check request - haiku, no thinking, JSON schema, no fallback`() = withServer { url, seen ->
        AnthropicLlm("test-key", baseUrl = url).call(
            LlmRequest(
                purpose = "mistakes", model = Models.CHECKS, system = listOf(Msg(Role.SYSTEM, "check")),
                messages = listOf(Msg(Role.USER, "昨日は寒いでした。")), thinking = false,
                jsonSchema = """{"type":"object","properties":{"errors":{"type":"array"}},"required":["errors"],"additionalProperties":false}""",
            ),
        )
        val (body, _) = seen()
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("json_schema", body.getJSONObject("output_config").getJSONObject("format").getString("type"))
        assertFalse(body.has("fallbacks"))
    }
}
