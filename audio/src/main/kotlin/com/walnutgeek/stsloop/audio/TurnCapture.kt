package com.walnutgeek.stsloop.audio

import android.util.Log
import com.k2fsa.sherpa.onnx.Vad
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.turn.CorpusSink
import com.walnutgeek.stsloop.core.turn.Segmenter
import com.walnutgeek.stsloop.core.turn.SpeechProbability
import com.walnutgeek.stsloop.core.turn.Timings
import com.walnutgeek.stsloop.core.turn.TurnEvent
import com.walnutgeek.stsloop.core.turn.Utterance
import java.io.File

/**
 * One Session's capture pipeline: the mic's int16 chunks go in, and each
 * utterance the VAD finds comes out as its own Turn in the Corpus ([Segmenter]
 * cuts, [CorpusSink] writes). Sample 0 of the stream is wall time
 * [sessionStartedAtMs]. A Turn that fails to write is logged and counted in
 * [failedTurns]; the Session carries on.
 *
 * Not thread-safe: call [accept] and [finish] from the capture thread.
 */
class TurnCapture(
    writer: CorpusWriter,
    sessionId: String,
    sessionStartedAtMs: Long,
    appVersion: String,
    timings: Timings,
    vad: SpeechProbability,
    windowSamples: Int,
    /** Called after each Turn is published, with the stream range it holds. */
    private val onTurn: (Turn, Utterance) -> Unit = { _, _ -> },
) {
    private val sink = CorpusSink(
        writer, sessionId, sessionStartedAtMs, SAMPLE_RATE_HZ, appVersion,
        listener = object : CorpusSink.Listener {
            override fun published(turn: Turn, utterance: Utterance) = onTurn(turn, utterance)

            override fun failed(utterance: Utterance, error: Exception) {
                Log.e(TAG, "Session $sessionId lost the Turn at samples ${utterance.startSample}..${utterance.endSample}", error)
            }

            override fun discarded(event: TurnEvent.Discarded) {
                Log.d(TAG, "discarded ${event.speechSamples * 1000 / SAMPLE_RATE_HZ} ms of speech at sample ${event.startSample}")
            }
        },
    )
    private val segmenter = Segmenter(timings, SAMPLE_RATE_HZ, windowSamples, vad, sink)

    val publishedTurns: Int get() = sink.published
    val failedTurns: Int get() = sink.failed

    /** Tees the first [count] samples of [samples] in. Closed utterances are written before this returns. */
    fun accept(samples: ShortArray, count: Int) = segmenter.accept(samples, count)

    /** Ends the Session: an utterance still being captured is written as a Turn. Safe to call twice. */
    fun finish() = segmenter.finish()

    companion object {
        private const val TAG = "stsloop.TurnCapture"

        /** Silero VAD as a [SpeechProbability]: the raw per-window probability, no sherpa-side segmenting. */
        fun silero(vad: Vad) = SpeechProbability { window -> vad.compute(window) }

        /**
         * The [Timings] in `<dir>/timings.json`, read at Session start so they can
         * be tuned without a rebuild. A missing file means defaults; each
         * rejected key is logged and keeps its default, the rest still apply.
         */
        fun loadTimings(dir: File): Timings {
            val file = File(dir, Timings.FILE)
            if (!file.exists()) return Timings()
            val text = try {
                file.readText()
            } catch (e: Exception) {
                Log.e(TAG, "cannot read $file, using default timings", e)
                return Timings()
            }
            val parsed = Timings.parse(text)
            for (r in parsed.rejected) Log.e(TAG, "$file: ignoring $r")
            return parsed.timings
        }
    }
}
