package com.cramtale.core.llm

import java.io.File
import java.time.Instant

/** The two models the app uses (PLAN.md §6.1). Prices per million tokens, editable in config/prices.json. */
object Models {
    const val WRITER = "claude-opus-5-5"
    const val CHECKS = "claude-haiku-5-5"
}

enum class Role { USER, ASSISTANT, SYSTEM }

/** A message; [cache] puts a cache breakpoint after it (fixed content first, PLAN.md §6.3). */
data class Msg(val role: Role, val text: String, val cache: Boolean = false)

data class LlmRequest(
    /** What the call is for: "writer", "director", "mistakes"… used for the cost log. */
    val purpose: String,
    val model: String,
    /** System prompt blocks, fixed content first; a block with cache = true ends a cached prefix. */
    val system: List<Msg>,
    val messages: List<Msg>,
    val maxTokens: Int = 4096,
    /** low | medium | high; null = model default. */
    val effort: String? = "low",
    /** JSON schema for structured output (as a JSON object string), or null for plain text. */
    val jsonSchema: String? = null,
    /** Haiku checks can run without thinking; Opus 5.5 can't turn it off (effort is the lever). */
    val thinking: Boolean = true,
)

data class Usage(val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long)

data class LlmResponse(val text: String, val usage: Usage, val stopReason: String, val model: String)

class LlmException(message: String, val retryable: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

interface Llm {
    fun call(req: LlmRequest): LlmResponse
}

data class Price(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double) {
    fun cost(u: Usage) = (u.input * input + u.output * output + u.cacheRead * cacheRead + u.cacheWrite * cacheWrite) / 1_000_000
}

object Prices {
    /** Dollars per million tokens (Anthropic list prices, Nov 2026). Cache writes: 1.25× input (5-minute cache). */
    val DEFAULT = mapOf(
        Models.WRITER to Price(4.0, 20.0, 0.20, 5.0),
        Models.CHECKS to Price(0.10, 0.50, 0.01, 0.125),
    )

    fun parse(json: String): Map<String, Price> {
        val o = org.json.JSONObject(json)
        return o.keys().asSequence().associateWith { k ->
            val p = o.getJSONObject(k)
            Price(p.getDouble("input"), p.getDouble("output"), p.getDouble("cacheRead"), p.getDouble("cacheWrite"))
        }
    }
}

/** Appends one JSON line per call (purpose, model, tokens, cost) and keeps per-session totals. */
class CostLog(private val file: File?, private val prices: Map<String, Price> = Prices.DEFAULT) {
    data class Entry(val at: Instant, val session: String, val purpose: String, val model: String, val usage: Usage, val dollars: Double)

    val entries = ArrayList<Entry>()

    @Synchronized
    fun record(session: String, req: LlmRequest, resp: LlmResponse): Entry {
        val price = prices[resp.model] ?: prices[req.model]
        val e = Entry(Instant.now(), session, req.purpose, resp.model, resp.usage, price?.cost(resp.usage) ?: 0.0)
        entries += e
        file?.let { f ->
            f.parentFile?.mkdirs()
            val o = org.json.JSONObject()
                .put("at", e.at.toString()).put("session", session).put("purpose", e.purpose).put("model", e.model)
                .put("input", e.usage.input).put("output", e.usage.output)
                .put("cacheRead", e.usage.cacheRead).put("cacheWrite", e.usage.cacheWrite).put("dollars", e.dollars)
            f.appendText(o.toString() + "\n")
        }
        return e
    }

    fun total(session: String) = entries.filter { it.session == session }.sumOf { it.dollars }

    fun byPurpose(session: String) = entries.filter { it.session == session }
        .groupBy { it.purpose }.mapValues { (_, v) -> v.sumOf { it.dollars } }
}
