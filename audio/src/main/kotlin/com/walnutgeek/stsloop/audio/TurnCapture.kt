package com.walnutgeek.stsloop.audio

import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.Vad
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.speech.StreamingRecognizer
import com.walnutgeek.stsloop.core.turn.CorpusSink
import com.walnutgeek.stsloop.core.turn.Segmenter
import com.walnutgeek.stsloop.core.turn.SpeechProbability
import com.walnutgeek.stsloop.core.turn.SttTiming
import com.walnutgeek.stsloop.core.turn.Timings
import com.walnutgeek.stsloop.core.turn.Transcriber
import com.walnutgeek.stsloop.core.turn.TurnEvent
import com.walnutgeek.stsloop.core.turn.Utterance
import com.walnutgeek.stsloop.core.turn.Worker
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One Session's capture pipeline: the mic's int16 chunks go in, and each
 * utterance the VAD finds comes out as its own transcribed Turn in the Corpus
 * ([Segmenter] cuts, [Transcriber] streams it into the recognizer while it is
 * captured, [CorpusSink] writes). Sample 0 of the stream is wall time
 * [sessionStartedAtMs]. A Turn that fails to write is logged and counted in
 * [failedTurns]; the Session carries on.
 *
 * Decoding and Corpus writes run on this Session's own STT thread, so
 * [accept] never waits on them and the mic is never starved. The recognizer
 * is loaded there too, as soon as the capture is created.
 *
 * Not thread-safe: call [accept] and [finish] from the capture thread.
 */
class TurnCapture(
    writer: CorpusWriter,
    private val sessionId: String,
    sessionStartedAtMs: Long,
    appVersion: String,
    timings: Timings,
    vad: SpeechProbability,
    windowSamples: Int,
    /** Loads the Session's one recognizer; called once, on the STT thread. */
    recognizer: () -> StreamingRecognizer,
    /** Called on the STT thread after each Turn is published, with the stream range it holds. */
    private val onTurn: (Turn, Utterance, SttTiming?) -> Unit = { _, _, _ -> },
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "stt-$sessionId") }
    private val sink = CorpusSink(
        writer, sessionId, sessionStartedAtMs, SAMPLE_RATE_HZ, appVersion,
        listener = object : CorpusSink.Listener {
            override fun published(turn: Turn, utterance: Utterance, timing: SttTiming?) = onTurn(turn, utterance, timing)

            override fun failed(utterance: Utterance, error: Exception) {
                Log.e(TAG, "Session $sessionId lost the Turn at samples ${utterance.startSample}..${utterance.endSample}", error)
            }

            override fun discarded(event: TurnEvent.Discarded) {
                Log.d(TAG, "discarded ${event.speechSamples * 1000 / SAMPLE_RATE_HZ} ms of speech at sample ${event.startSample}")
            }
        },
    )
    private val transcriber = Transcriber(
        Worker { task -> executor.execute(task) },
        load = {
            val t0 = SystemClock.elapsedRealtime()
            recognizer().also { Log.i(TAG, "Session $sessionId loaded ${it.model} in ${SystemClock.elapsedRealtime() - t0} ms") }
        },
        sink,
        wallClock = System::currentTimeMillis,
        nanoTime = System::nanoTime,
        listener = object : Transcriber.Listener {
            override fun unavailable(error: Exception) {
                Log.e(TAG, "Session $sessionId has no recognizer; Turns are written without transcripts", error)
            }

            override fun failed(startSample: Long, error: Exception) {
                Log.e(TAG, "Session $sessionId lost the transcript of the Turn at sample $startSample", error)
            }
        },
    ).apply { start() }
    private val segmenter = Segmenter(timings, SAMPLE_RATE_HZ, windowSamples, vad, transcriber)
    private var finished = false

    /** Published so far; final once [finish] returns. */
    val publishedTurns: Int get() = sink.published
    val failedTurns: Int get() = sink.failed

    /** Tees the first [count] samples of [samples] in. Returns without waiting for decoding or writing. */
    fun accept(samples: ShortArray, count: Int) = segmenter.accept(samples, count)

    /**
     * Ends the Session: an utterance still being captured becomes a Turn, and
     * this waits until every Turn is transcribed and written and the recognizer
     * is released. Safe to call twice.
     */
    fun finish() {
        segmenter.finish()
        if (finished) return
        finished = true
        transcriber.close()
        executor.shutdown()
        val t0 = SystemClock.elapsedRealtime()
        if (!executor.awaitTermination(DRAIN_TIMEOUT_S, TimeUnit.SECONDS)) {
            Log.e(TAG, "Session $sessionId: STT still busy after $DRAIN_TIMEOUT_S s; abandoning its queue")
            executor.shutdownNow()
        }
        Log.i(TAG, "Session $sessionId: STT drained ${SystemClock.elapsedRealtime() - t0} ms after the last sample")
    }

    companion object {
        private const val TAG = "stsloop.TurnCapture"
        private const val DRAIN_TIMEOUT_S = 60L

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
