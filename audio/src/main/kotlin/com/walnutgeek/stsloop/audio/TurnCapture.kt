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
 * is loaded there too, as soon as the capture is created. If decoding falls
 * more than [maxQueuedMs] of audio behind, the Turn being captured stops
 * being fed and is written without a transcript; its audio is always kept.
 *
 * Not thread-safe: call [accept] and [finish] from the capture thread;
 * [hurry] may be called from any thread.
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
    maxQueuedMs: Long = MAX_QUEUED_MS,
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
        nanoTime = SystemClock::elapsedRealtimeNanos, // the clock accept() stamps samples with
        maxQueuedSamples = maxQueuedMs * SAMPLE_RATE_HZ / 1000,
        listener = object : Transcriber.Listener {
            override fun unavailable(error: Throwable) {
                Log.e(TAG, "Session $sessionId has no recognizer; Turns are written without transcripts", error)
            }

            override fun failed(startSample: Long, error: Throwable) {
                Log.e(TAG, "Session $sessionId lost the transcript of the Turn at sample $startSample", error)
            }

            override fun fellBehind(startSample: Long, queuedSamples: Long) {
                Log.w(
                    TAG,
                    "Session $sessionId: decoding ${queuedSamples * 1000 / SAMPLE_RATE_HZ} ms behind; " +
                        "the Turn at sample $startSample is written without a transcript",
                )
            }
        },
    ).apply { start() }
    private val segmenter = Segmenter(timings, SAMPLE_RATE_HZ, windowSamples, vad, transcriber)
    private var finished = false

    /** Published so far; final once [finish] returns. */
    val publishedTurns: Int get() = sink.published
    val failedTurns: Int get() = sink.failed

    /** Published without a transcript; final once [finish] returns. */
    val untranscribedTurns: Int get() = transcriber.untranscribed

    /**
     * Tees the first [count] samples of [samples] in. [capturedAtNs] is when
     * the last of them was captured, on `SystemClock.elapsedRealtimeNanos`:
     * call this straight after `AudioRecord.read`. Returns without waiting for
     * decoding or writing.
     */
    fun accept(samples: ShortArray, count: Int, capturedAtNs: Long = SystemClock.elapsedRealtimeNanos()) =
        segmenter.accept(samples, count, capturedAtNs)

    /**
     * Skip all remaining recognition: every queued Turn is written at once,
     * without a transcript. For a teardown that cannot wait for decoding.
     */
    fun hurry() = transcriber.abandon()

    /**
     * Ends the Session: an utterance still being captured becomes a Turn, and
     * this waits until every Turn is written and the recognizer is released.
     * Decoding gets [TRANSCRIBE_DRAIN_MS]; after that the rest of the queue is
     * written without transcripts. The queue is never dropped: if a native call
     * is stuck past [WRITE_DRAIN_MS] more, this returns and the STT thread
     * still writes those Turns, and then releases the recognizer, when the call
     * returns. Safe to call twice.
     */
    fun finish() {
        try {
            segmenter.finish()
        } finally {
            if (!finished) {
                finished = true
                drain()
            }
        }
    }

    private fun drain() {
        transcriber.close()
        executor.shutdown()
        val t0 = SystemClock.elapsedRealtime()
        if (!executor.awaitTermination(TRANSCRIBE_DRAIN_MS, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Session $sessionId: STT still decoding after $TRANSCRIBE_DRAIN_MS ms; writing the rest without transcripts")
            transcriber.abandon()
            if (!executor.awaitTermination(WRITE_DRAIN_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Session $sessionId: a native call is stuck; queued Turns will be written when it returns")
                return
            }
        }
        Log.i(TAG, "Session $sessionId: STT drained ${SystemClock.elapsedRealtime() - t0} ms after the last sample")
    }

    companion object {
        private const val TAG = "stsloop.TurnCapture"

        /** Decoding may fall this far behind capture before a Turn stops being fed. */
        const val MAX_QUEUED_MS = 10_000L

        /**
         * A normal Stop waits this long for decoding. The service stays in the
         * foreground until the capture thread ends, so a long wait is safe there;
         * a teardown calls [hurry] first and waits only for the writes.
         */
        const val TRANSCRIBE_DRAIN_MS = 30_000L
        const val WRITE_DRAIN_MS = 3_000L

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
