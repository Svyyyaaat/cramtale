package com.cramtale.core.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.SystemMessageParam
import com.anthropic.models.messages.TextBlockParam
import java.time.Duration

/**
 * [Llm] on the Messages API through the official Java SDK.
 * - Prompt caching: each system block with cache = true gets a breakpoint, and top-level
 *   automatic caching puts one after the conversation history (PLAN.md §6.3).
 * - Effort and structured output go in output_config; Haiku checks run without thinking.
 * - Opus calls opt into the server-side refusal fallback ([fallbacks]), so a rare refusal
 *   doesn't end a turn.
 * - A mid-conversation system message (Role.SYSTEM) carries the per-turn scene packet without
 *   disturbing the cached history.
 */
class AnthropicLlm(apiKey: String, private val fallbacks: Boolean = true, baseUrl: String? = null) : Llm {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .timeout(Duration.ofMinutes(5))
        .maxRetries(2)
        .build()

    override fun call(req: LlmRequest): LlmResponse {
        val b = MessageCreateParams.builder()
            .model(req.model)
            .maxTokens(req.maxTokens.toLong())
            .systemOfTextBlockParams(req.system.map { block ->
                TextBlockParam.builder().text(block.text).apply {
                    if (block.cache) cacheControl(CacheControlEphemeral.builder().build())
                }.build()
            })
            .cacheControl(CacheControlEphemeral.builder().build())
        for (m in req.messages) when (m.role) {
            Role.USER -> b.addUserMessage(m.text)
            Role.ASSISTANT -> b.addAssistantMessage(m.text)
            Role.SYSTEM -> b.addMessage(SystemMessageParam.builder().content(m.text).build())
        }
        val output = LinkedHashMap<String, Any>()
        req.effort?.let { output["effort"] = it }
        req.jsonSchema?.let { output["format"] = mapOf("type" to "json_schema", "schema" to org.json.JSONObject(it).toMap()) }
        if (output.isNotEmpty()) b.putAdditionalBodyProperty("output_config", JsonValue.from(output))
        if (!req.thinking) b.putAdditionalBodyProperty("thinking", JsonValue.from(mapOf("type" to "disabled")))
        if (fallbacks && req.model == Models.WRITER) {
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }

        val msg = try {
            client.messages().create(b.build())
        } catch (e: RateLimitException) {
            throw LlmException("rate limited", retryable = true, cause = e)
        } catch (e: InternalServerException) {
            throw LlmException("server error ${e.statusCode()}", retryable = true, cause = e)
        } catch (e: AnthropicIoException) {
            throw LlmException("network: ${e.message}", retryable = true, cause = e)
        } catch (e: AnthropicServiceException) {
            throw LlmException("API error ${e.statusCode()}: ${e.message}", cause = e)
        }
        val stop = msg.stopReason().map { it.asString() }.orElse("")
        if (stop == "refusal") throw LlmException("the model declined (${msg.stopDetails().map { it.toString() }.orElse("")})")
        val text = msg.content().mapNotNull { c -> c.text().map { it.text() }.orElse(null) }.joinToString("")
        val u = msg.usage()
        return LlmResponse(
            text = text,
            usage = Usage(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0), u.cacheCreationInputTokens().orElse(0)),
            stopReason = stop,
            model = msg.model().asString(),
        )
    }
}
