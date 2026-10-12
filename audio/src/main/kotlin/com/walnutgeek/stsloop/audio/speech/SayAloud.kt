package com.walnutgeek.stsloop.audio.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Says one sentence aloud outside a Session (onboarding's "ready, N agents"
 * after pairing), with a TTS engine of its own that is shut down once the
 * sentence ends. Inside a Session the loop says things itself, into Silence.
 */
object SayAloud {
    private const val TAG = "stsloop.SayAloud"

    /** Any thread: say [text] once; a missing engine is logged, not thrown. */
    fun once(context: Context, text: String) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "no TTS engine to say \"$text\" (status $status)")
                engine.shutdown()
                return@TextToSpeech
            }
            engine.language = Locale.US
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = engine.shutdown()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = engine.shutdown()
            })
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say-aloud") != TextToSpeech.SUCCESS) engine.shutdown()
        }
    }
}
