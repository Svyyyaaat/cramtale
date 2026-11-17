package com.cramtale.core.llm

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A failure as a person reads it: what happened and what to do, then the technical chain for reports. */
object ErrorText {
    fun explain(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.take(6).toList()
        val all = chain.joinToString(" ") { it.message.orEmpty() }
        val headline = when {
            chain.any { it is UnknownHostException } ->
                "Can't reach Claude: the phone couldn't look up api.anthropic.com. Check the internet connection (and that " +
                    "this app may use mobile data), then try again."
            chain.any { it is SocketTimeoutException } || "timed out" in all || "timeout" in all.lowercase() ->
                "The connection timed out. On a weak signal this happens; try again."
            chain.any { it is SSLException } ->
                "The secure connection to Claude failed. A VPN, captive Wi-Fi page or filtering DNS can cause this."
            "API error 401" in all -> "Claude rejected the API key. Paste a new one in Settings → Claude API key."
            "API error 403" in all -> "Claude refused the request (403): the key may lack access, or the API isn't available from this network."
            "credit balance" in all.lowercase() -> "Your Anthropic account is out of credit. Add credit in the Anthropic Console, then try again."
            "rate limited" in all -> "Too many requests at once. Wait a minute and try again."
            "server error 529" in all || "overloaded" in all.lowercase() -> "Claude is overloaded right now. Try again in a minute."
            "server error" in all -> "Claude had a server error. Try again."
            "declined" in all -> "The model declined to write this passage. Try again, or reroll."
            "no usable reply" in all -> "Claude's answer came back unusable three times. Try again."
            "Add your Claude API key" in all -> "Add your Claude API key first (Settings → Claude API key)."
            else -> "Something went wrong."
        }
        val details = chain.joinToString("\n← ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        return "$headline\n\n$details"
    }
}
