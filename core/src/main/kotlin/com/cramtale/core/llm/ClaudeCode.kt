package com.cramtale.core.llm

import org.json.JSONObject

/**
 * Claude Code (`claude -p`, signed in with the user's own subscription) as a backend. The phone runs it
 * in Termux through claude_call.py; this is the part that doesn't depend on Android: what the script is
 * given for a request and how its answer becomes a response. The transport hands over the script's
 * final line and, while it runs, the reply's text so far.
 */
class ClaudeCodeLlm(
    /** Runs one call: the request JSON in; the script's printed line out. [onText] gets each new piece. */
    private val run: (request: JSONObject, onText: ((String) -> Unit)?) -> String,
) : Llm {
    override fun call(req: LlmRequest): LlmResponse = send(req, null)
    override fun stream(req: LlmRequest, onText: (String) -> Unit): LlmResponse = send(req, onText)

    private fun send(req: LlmRequest, onText: ((String) -> Unit)?): LlmResponse =
        response(run(request(req), onText), req)

    companion object {
        /** One prompt for `claude -p`: a conversation (the writer's) becomes a transcript ending with the request. */
        fun request(req: LlmRequest): JSONObject {
            val turns = req.messages
            val prompt = if (turns.size <= 1) turns.joinToString("") { it.text } else buildString {
                append("The conversation so far:\n\n")
                for (m in turns.dropLast(1)) {
                    val tag = if (m.role == Role.ASSISTANT) "assistant" else "user"
                    append("<$tag>\n").append(m.text.trim()).append("\n</$tag>\n\n")
                }
                append("Now answer this, as the assistant:\n\n").append(turns.last().text)
            }
            return JSONObject()
                .put("model", req.model)
                .put("system", req.system.joinToString("\n\n") { it.text })
                .put("prompt", prompt)
                .put("schema", req.jsonSchema ?: JSONObject.NULL)
                // Haiku takes no effort setting
                .put("effort", if (req.model == Models.CHECKS) JSONObject.NULL else (req.effort ?: JSONObject.NULL))
        }

        fun response(line: String, req: LlmRequest): LlmResponse {
            val o = runCatching { JSONObject(line.trim().lines().last { it.isNotBlank() }) }.getOrNull()
                ?: throw LlmException("Claude Code (Termux) gave no answer: ${line.trim().takeLast(300).ifBlank { "nothing printed" }}", retryable = true)
            if (!o.optBoolean("ok")) throw LlmException(o.optString("error", "Claude Code failed"), retryable = o.optBoolean("retryable"))
            val u = o.optJSONObject("usage") ?: JSONObject()
            val stop = o.optString("stop").let { if (it == "tool_use" || it.isBlank()) "end_turn" else it }
            return LlmResponse(o.optString("text"), Usage(u.optLong("input"), u.optLong("output"), u.optLong("cacheRead"), u.optLong("cacheWrite")),
                // the subscription pays: no price in the cost log
                stop, "$PREFIX${o.optString("model", req.model)}")
        }

        /** Cost-log model names for calls paid by the subscription (no list price applies). */
        const val PREFIX = "claude-code/"
    }
}
