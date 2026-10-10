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
class SttRecognizer(private val stt: Stt) : StreamingRecognizer {
    override val engine = ENGINE
    override val model = stt.spec.modelId

    override fun open(): RecognitionStream = Stream(stt.createStream())

    override fun close() = stt.close()

    private inner class Stream(private val stream: OnlineStream) : RecognitionStream {
        override fun accept(samples: FloatArray) {
            stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
            drain()
        }

        override fun finish(): String {
            // Zeros past the end push the last frames through the encoder's chunk.
            stream.acceptWaveform(TAIL_PADDING, SAMPLE_RATE_HZ)
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

        /** 300 ms, as in the #6 benchmark; a silence-closed Turn already ends in 1.5 s of room tone. */
        private val TAIL_PADDING = FloatArray(SAMPLE_RATE_HZ * 3 / 10)

        /**
         * Loads the shipped model. A missing native library is a [LinkageError];
         * it is rethrown as an exception so the loop reports it and carries on
         * without transcripts.
         */
        fun load(assets: AssetManager): SttRecognizer = try {
            SttRecognizer(Stt(assets))
        } catch (e: LinkageError) {
            throw IllegalStateException("no speech natives; run scripts/build-sherpa-onnx.sh", e)
        }
    }
}
