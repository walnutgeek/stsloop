package com.walnutgeek.stsloop.audio

import android.util.Log
import com.k2fsa.sherpa.onnx.Vad
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.Ids
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.turn.Segmenter
import com.walnutgeek.stsloop.core.turn.SpeechProbability
import com.walnutgeek.stsloop.core.turn.Timings
import com.walnutgeek.stsloop.core.turn.TurnEvent
import com.walnutgeek.stsloop.core.turn.Utterance
import com.walnutgeek.stsloop.core.turn.UtteranceSink
import java.io.File

/**
 * One Session's capture pipeline: the mic's int16 chunks go in, and each
 * utterance the VAD finds comes out as its own Turn in the Corpus. Sample 0 of
 * the stream is wall time [sessionStartedAtMs]; a Turn's `started_at` is
 * derived from its sample offset, and its audio is exactly that range.
 *
 * Not thread-safe: call [accept] and [finish] from the capture thread.
 */
class TurnCapture(
    private val writer: CorpusWriter,
    private val sessionId: String,
    private val sessionStartedAtMs: Long,
    private val appVersion: String,
    timings: Timings,
    vad: SpeechProbability,
    windowSamples: Int,
    /** Called after each Turn is published, with the stream range it holds. */
    private val onTurn: (Turn, Utterance) -> Unit = { _, _ -> },
) {
    private val segmenter = Segmenter(timings, SAMPLE_RATE_HZ, windowSamples, vad, Sink())

    /** Tees the first [count] samples of [samples] in. Closed utterances are written before this returns. */
    fun accept(samples: ShortArray, count: Int) = segmenter.accept(samples, count)

    /** Ends the Session: an utterance still being captured is written as a Turn. */
    fun finish() = segmenter.finish()

    private inner class Sink : UtteranceSink {
        override fun closed(utterance: Utterance, pcm: ShortArray) {
            val turn = writer.begin(Ids.next(), sessionId, utterance.startedAtMs(sessionStartedAtMs, SAMPLE_RATE_HZ), SAMPLE_RATE_HZ)
            val written = try {
                turn.append(pcm, pcm.size)
                turn.finish(appVersion, utterance.vad(SAMPLE_RATE_HZ))
            } catch (e: Exception) {
                turn.abandon()
                throw e
            }
            onTurn(written, utterance)
        }

        override fun discarded(event: TurnEvent.Discarded) {
            Log.d(TAG, "discarded ${event.speechSamples * 1000 / SAMPLE_RATE_HZ} ms of speech at sample ${event.startSample}")
        }
    }

    companion object {
        private const val TAG = "stsloop.TurnCapture"

        /** Silero VAD as a [SpeechProbability]: the raw per-window probability, no sherpa-side segmenting. */
        fun silero(vad: Vad) = SpeechProbability { window -> vad.compute(window) }

        /**
         * The [Timings] in `<dir>/timings.json`, read at Session start so they can
         * be tuned without a rebuild. A missing file means defaults; an invalid
         * one is logged and also falls back to defaults rather than failing the Session.
         */
        fun loadTimings(dir: File): Timings {
            val file = File(dir, Timings.FILE)
            if (!file.exists()) return Timings()
            return try {
                Timings.parse(file.readText())
            } catch (e: Exception) {
                Log.e(TAG, "ignoring $file, using default timings: ${e.message}")
                Timings()
            }
        }
    }
}
