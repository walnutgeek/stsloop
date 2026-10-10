package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.Transcript
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.speech.RecognitionStream
import com.walnutgeek.stsloop.core.speech.StreamingRecognizer
import com.walnutgeek.stsloop.core.speech.TranscriptText
import kotlin.math.roundToLong

/** Runs tasks one at a time, in the order posted, off the capture thread. */
fun interface Worker {
    fun post(task: () -> Unit)
}

/**
 * The TRANSCRIBE stage (`docs/mvp.md`, "The loop"): streams each utterance
 * into its own recognizer stream while it is being captured, then publishes
 * the Turn with its transcript through [sink].
 *
 * The capture thread only posts work to [worker], so capture never waits on
 * decoding: the recognizer, its streams, and the Corpus writes all live on the
 * worker. Streaming during CAPTURING means that by the time trailing Silence
 * closes an utterance, nearly all of it is already decoded, and only the
 * final flush stands between `ended_at` and the transcript.
 *
 * The recognizer is [load]ed once, on the worker, at [start] or the first
 * Turn, so the microphone opens without waiting for the model. If it fails to
 * load, or a stream fails, Turns are still published, without a transcript:
 * the Recording is the Corpus's primary record.
 */
class Transcriber(
    private val worker: Worker,
    private val load: () -> StreamingRecognizer,
    private val sink: CorpusSink,
    /** Epoch ms, for `finished_at`. */
    private val wallClock: () -> Long,
    /** Monotonic ns, for recognizer time. */
    private val nanoTime: () -> Long,
    private val listener: Listener,
) : UtteranceSink {
    interface Listener {
        /** The recognizer could not be loaded; this Session's Turns get no transcripts. */
        fun unavailable(error: Exception) {}

        /** The stream of the utterance opened at [startSample] failed; its Turn gets no transcript. */
        fun failed(startSample: Long, error: Exception) {}
    }

    // Worker-confined from here on.
    private var loadAttempted = false
    private var recognizer: StreamingRecognizer? = null
    private var stream: RecognitionStream? = null
    private var streamStart = -1L
    private var computeNs = 0L

    /** Loads the recognizer in the background. Optional: the first Turn loads it otherwise. */
    fun start() = worker.post { recognizer() }

    /** Releases any open stream and the recognizer, after all work posted before it. */
    fun close() = worker.post {
        dropStream()
        recognizer?.close()
        recognizer = null
    }

    override fun opened(startSample: Long) = worker.post {
        dropStream()
        streamStart = startSample
        computeNs = 0
        val r = recognizer() ?: return@post
        stream = timed { attempt { r.open() } }
    }

    override fun captured(samples: FloatArray) = worker.post {
        val s = stream ?: return@post
        timed { attempt { s.accept(samples) } } ?: dropStream()
    }

    override fun closed(utterance: Utterance, pcm: ShortArray) = worker.post {
        val s = stream
        stream = null
        var transcript: Transcript? = null
        var timing: SttTiming? = null
        if (s != null) {
            val t0 = nanoTime()
            val text = try {
                attempt { s.finish() }
            } finally {
                runCatching { s.release() }
            }
            val finishNs = nanoTime() - t0
            computeNs += finishNs
            val r = recognizer
            if (text != null && r != null) {
                transcript = Transcript(TranscriptText.normalize(text), r.engine, r.model, wallClock())
                timing = SttTiming(computeMs = computeNs / NS_PER_MS, finalizeMs = finishNs / NS_PER_MS)
            }
        }
        sink.closed(utterance, pcm, transcript, timing)
    }

    override fun discarded(event: TurnEvent.Discarded) = worker.post {
        dropStream()
        sink.discarded(event)
    }

    private fun recognizer(): StreamingRecognizer? {
        if (!loadAttempted) {
            loadAttempted = true
            recognizer = try {
                load()
            } catch (e: Exception) {
                listener.unavailable(e)
                null
            }
        }
        return recognizer
    }

    private fun dropStream() {
        stream?.let { s -> runCatching { s.release() } }
        stream = null
    }

    /** Runs [block], reporting a failure against the open utterance as null. */
    private fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        listener.failed(streamStart, e)
        null
    }

    private fun <T> timed(block: () -> T): T {
        val t0 = nanoTime()
        try {
            return block()
        } finally {
            computeNs += nanoTime() - t0
        }
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
    }
}

/** How much recognizer time one Turn took. */
data class SttTiming(
    /** Everything spent in the recognizer on this Turn's stream: open, feeding while captured, and finish. */
    val computeMs: Long,
    /** The final flush alone: what decoding adds after the utterance is cut. */
    val finalizeMs: Long,
) {
    /** Real-time factor: recognizer time over audio time. */
    fun rtf(audioMs: Long): Double = if (audioMs <= 0) 0.0 else computeMs.toDouble() / audioMs

    fun summary(turn: Turn): String = summary(turn, this)

    companion object {
        /** One log line: end-of-utterance → transcript latency, finish time, and RTF. */
        fun summary(turn: Turn, timing: SttTiming?): String {
            val latency = turn.transcriptLatencyMs
            if (latency == null || timing == null) return "no transcript"
            val audioMs = turn.audio.durationMs
            return "transcript $latency ms after ended_at (finish ${timing.finalizeMs} ms), " +
                "RTF ${threeDecimals(timing.rtf(audioMs))} (${timing.computeMs} ms for $audioMs ms of audio)"
        }

        /** Locale-independent, unlike `"%.3f".format`. */
        private fun threeDecimals(x: Double): String {
            val m = (x * 1000).roundToLong()
            return "${m / 1000}.${(m % 1000).toString().padStart(3, '0')}"
        }
    }
}
