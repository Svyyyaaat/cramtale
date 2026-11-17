package com.cramtale.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Japanese text-to-speech with the phone's own engine (no network, no cost): the passage read aloud
 * for listening practice, or one word from the popup. Started on first use.
 */
class Speaker(private val context: Context) {
    enum class State { IDLE, STARTING, READY, NO_JAPANESE, FAILED }

    var state by mutableStateOf(State.IDLE)
        private set
    /** Whether something is being read right now (the passage button shows Stop). */
    var speaking by mutableStateOf(false)
        private set

    private var tts: TextToSpeech? = null
    private var pending: Pair<String, Float>? = null

    private fun start() {
        if (tts != null) return
        state = State.STARTING
        tts = TextToSpeech(context.applicationContext) { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) { state = State.FAILED; return@TextToSpeech }
            val r = t.setLanguage(Locale.JAPANESE)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) { state = State.NO_JAPANESE; return@TextToSpeech }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { speaking = true }
                override fun onDone(id: String?) { speaking = false }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { speaking = false }
            })
            state = State.READY
            pending?.let { (text, rate) -> say(text, rate) }
            pending = null
        }
    }

    /** Reads [text] aloud ([rate] 1 = normal; a bit slower suits listening practice). */
    fun say(text: String, rate: Float = 0.9f) {
        when (state) {
            State.READY -> {
                val t = tts ?: return
                t.setSpeechRate(rate)
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cramtale")
            }
            State.NO_JAPANESE, State.FAILED -> {}
            else -> { pending = text to rate; start() }
        }
    }

    fun stop() { tts?.stop(); speaking = false }

    fun shutdown() { tts?.shutdown(); tts = null; state = State.IDLE; speaking = false }

    /** Why nothing is heard, for the UI; null when fine. */
    fun problem(): String? = when (state) {
        State.NO_JAPANESE -> "No Japanese voice on this phone. Install one in Settings → Text-to-speech (Google's engine has it)."
        State.FAILED -> "Text-to-speech isn't available on this phone."
        else -> null
    }
}
