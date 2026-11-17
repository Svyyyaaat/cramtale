package com.cramtale.core.llm

/**
 * Scripted [Llm] for tests and for running the story loop without an API key: replies are
 * queued per purpose ("writer", "mistakes"…); a purpose with nothing queued uses its default.
 * Every request is kept, so tests can check what was sent.
 */
class FakeLlm(private val defaults: Map<String, String> = emptyMap()) : Llm {
    private val queued = HashMap<String, ArrayDeque<String>>()
    val requests = ArrayList<LlmRequest>()

    @Synchronized
    fun queue(purpose: String, vararg replies: String) {
        queued.getOrPut(purpose) { ArrayDeque() }.addAll(replies)
    }

    // the engine runs independent calls in parallel
    @Synchronized
    override fun call(req: LlmRequest): LlmResponse {
        requests += req
        val text = queued[req.purpose]?.removeFirstOrNull() ?: defaults[req.purpose]
            ?: throw LlmException("FakeLlm: nothing scripted for '${req.purpose}'")
        return LlmResponse(text, Usage(1000, text.length.toLong(), 0, 0), "end_turn", req.model)
    }

    @Synchronized
    fun sent(purpose: String) = requests.filter { it.purpose == purpose }
}
