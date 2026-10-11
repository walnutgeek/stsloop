package com.walnutgeek.stsloop.audio.speech

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OnlineStream
import com.walnutgeek.stsloop.audio.SAMPLE_RATE_HZ
import com.walnutgeek.stsloop.core.speech.RecognitionStream
import com.walnutgeek.stsloop.core.speech.StreamingRecognizer
import com.walnutgeek.stsloop.core.speech.modelId

/**
 * [Stt] as the loop's [StreamingRecognizer]: one stream per Turn, created
 * through the chokepoint without hotwords (no Bucket Word lists yet). Every
 * call must come from one thread.
 */
class SttRecognizer(
    private val stt: Stt,
    /** Zeros fed before `inputFinished`, so the encoder's last chunk is complete. */
    tailPaddingMs: Int = TAIL_PADDING_MS,
) : StreamingRecognizer {
    private val tailPadding = FloatArray(SAMPLE_RATE_HZ / 1000 * tailPaddingMs)
    override val engine = ENGINE
    override val model = stt.spec.modelId

    override fun open(): RecognitionStream = Stream(stt.createStream())

    override fun close() = stt.close()

    private inner class Stream(private val stream: OnlineStream) : RecognitionStream {
        override fun accept(samples: FloatArray) {
            stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
            drain()
        }

        /** The engine's raw text, unaltered: this model emits mixed case with some punctuation. */
        override fun finish(): String {
            // Zeros past the end push the last frames into an encoder chunk.
            stream.acceptWaveform(tailPadding, SAMPLE_RATE_HZ)
            stream.inputFinished()
            drain()
            return stt.text(stream)
        }

        override fun release() = stream.release()

        private fun drain() {
            while (stt.isReady(stream)) stt.decode(stream)
        }
    }

    companion object {
        const val ENGINE = "sherpa-onnx"

        /**
         * 1.2 s, as in the #8 offline A/B. The shipped model decodes in 560 ms
         * chunks, drops an incomplete last one, and emits a word only well
         * after it is spoken: with 300 ms, TTS "scratch that" followed by
         * 0.5 s of silence came out as "Scratch", and one of 20 drive
         * Recordings lost its last word even after 1.5 s of Silence. It costs
         * about 100 ms of median latency (325 ms against 219 ms, #30).
         */
        const val TAIL_PADDING_MS = 1200

        /** Loads the shipped model. A missing native library throws a [LinkageError]. */
        fun load(assets: AssetManager): SttRecognizer = SttRecognizer(Stt(assets))
    }
}
