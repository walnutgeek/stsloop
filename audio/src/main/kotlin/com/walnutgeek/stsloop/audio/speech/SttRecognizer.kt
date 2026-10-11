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
         * 300 ms, as in the #6 benchmark. The shipped model decodes in 560 ms
         * chunks and drops an incomplete last one, so up to ~0.3 s at the very
         * end of a Turn can go undecoded. A silence-closed Turn ends in 1.5 s
         * of room tone, so only a Turn cut mid-speech (Stop, max duration)
         * can lose a word there; a full chunk of padding would instead add
         * one more chunk's decode to every Turn's latency (#30).
         */
        const val TAIL_PADDING_MS = 300

        /** Loads the shipped model. A missing native library throws a [LinkageError]. */
        fun load(assets: AssetManager): SttRecognizer = SttRecognizer(Stt(assets))
    }
}
