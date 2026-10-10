package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.Transcript
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.speech.RecognitionStream
import com.walnutgeek.stsloop.core.speech.StreamingRecognizer
import kotlin.concurrent.Volatile
import kotlin.math.roundToLong

/** Runs tasks one at a time, in the order posted, off the capture thread. */
fun interface Worker {
    fun post(task: () -> Unit)
}

/** A Turn's transcript and how long the recognizer took over it. */
data class Transcription(val transcript: Transcript, val timing: SttTiming)

/**
 * The TRANSCRIBE stage (`docs/mvp.md`, "The loop"): streams each utterance
 * into its own recognizer stream while it is being captured, then publishes
 * the Turn with its transcript through [sink].
 *
 * The capture thread only posts work to [worker], so capture never waits on
 * decoding: the recognizer, its streams, and the Corpus writes all live on the
 * worker. Streaming during CAPTURING means that by the time trailing Silence
 * closes an utterance, nearly all of it is already decoded, and only the
 * final flush stands between the last sample and the transcript.
 *
 * Audio is the Corpus's ground truth, so every closed utterance is published,
 * whatever happens to recognition. A Turn goes out without a transcript, and
 * is counted in [untranscribed], when:
 * - the recognizer fails to load (once per Session), or any recognizer call
 *   throws. An [Error] (a broken native library) also stops all recognition
 *   for the rest of the Session.
 * - decoding falls more than [maxQueuedSamples] behind capture. That Turn
 *   stops being fed, its queued samples are skipped, and memory and latency
 *   stay bounded however slow the recognizer gets.
 * - [abandon] was called (Session teardown, or a drain that ran out of time).
 */
class Transcriber(
    private val worker: Worker,
    private val load: () -> StreamingRecognizer,
    private val sink: CorpusSink,
    /** Epoch ms, for `finished_at`. */
    private val wallClock: () -> Long,
    /** Monotonic ns: the clock the Segmenter's `endedAtNs` is on. */
    private val nanoTime: () -> Long,
    /** Samples posted to the recognizer but not yet decoded, past which a Turn stops being fed. */
    private val maxQueuedSamples: Long,
    private val listener: Listener,
) : UtteranceSink {
    interface Listener {
        /** The recognizer could not be loaded; this Session's Turns get no transcripts. */
        fun unavailable(error: Throwable) {}

        /** The stream of the utterance opened at [startSample] failed; its Turn gets no transcript. */
        fun failed(startSample: Long, error: Throwable) {}

        /** Decoding was [queuedSamples] behind when the utterance opened at [startSample] stopped being fed. */
        fun fellBehind(startSample: Long, queuedSamples: Long) {}
    }

    /** One utterance, as the capture thread hands it over. */
    private class Pending(val startSample: Long) {
        /** Set on the capture thread when this Turn stops being fed; read on the worker. */
        @Volatile
        var behindBy = -1L
    }

    // Capture thread only.
    private var current: Pending? = null
    private var postedSamples = 0L

    // Written by the worker only, read by the capture thread.
    @Volatile
    private var decodedSamples = 0L

    @Volatile
    private var abandoned = false

    /** Turns published without a transcript. Final once the worker has run everything posted. */
    @Volatile
    var untranscribed = 0
        private set

    // Worker-confined.
    private var loadAttempted = false
    private var recognizer: StreamingRecognizer? = null
    private var stream: RecognitionStream? = null
    private var streamStart = -1L
    private var computeNs = 0L

    /** Loads the recognizer in the background. Optional: the first Turn loads it otherwise. */
    fun start() = worker.post { recognizer() }

    /**
     * From now on, skip all recognition: every Turn still queued is published
     * without a transcript. A native call already running finishes first, and
     * streams and the recognizer are still released in order on the worker,
     * so nothing is used after release. Callable from any thread.
     */
    fun abandon() {
        abandoned = true
    }

    /** Releases any open stream and the recognizer, after all work posted before it. */
    fun close() = worker.post {
        dropStream()
        recognizer?.let { r -> runCatching { r.close() } }
        recognizer = null
    }

    override fun opened(startSample: Long) {
        val p = Pending(startSample)
        current = p
        worker.post { open(p) }
    }

    override fun captured(samples: FloatArray) {
        val p = current ?: return
        if (p.behindBy >= 0) return
        val queued = postedSamples - decodedSamples
        if (queued + samples.size > maxQueuedSamples) {
            p.behindBy = queued
            return
        }
        postedSamples += samples.size
        worker.post {
            feed(p, samples)
            decodedSamples += samples.size
        }
    }

    override fun closed(utterance: Utterance, pcm: ShortArray, endedAtNs: Long) {
        val p = current
        current = null
        val postedNs = nanoTime()
        worker.post { publish(p, utterance, pcm, endedAtNs, postedNs) }
    }

    override fun discarded(event: TurnEvent.Discarded) {
        current = null
        worker.post {
            dropStream()
            sink.discarded(event)
        }
    }

    private fun open(p: Pending) {
        dropStream()
        streamStart = p.startSample
        computeNs = 0
        if (abandoned) return
        val r = recognizer() ?: return
        stream = timed { attempt { r.open() } }
    }

    private fun feed(p: Pending, samples: FloatArray) {
        if (abandoned || p.behindBy >= 0) return dropStream()
        val s = stream ?: return
        timed { attempt { s.accept(samples) } } ?: dropStream()
    }

    private fun publish(p: Pending?, utterance: Utterance, pcm: ShortArray, endedAtNs: Long, postedNs: Long) {
        val s = stream
        stream = null
        var transcription: Transcription? = null
        if (s != null && p != null && p.behindBy < 0 && !abandoned) {
            val t0 = nanoTime()
            val text = attempt { s.finish() }
            val t1 = nanoTime()
            computeNs += t1 - t0
            val r = recognizer
            if (text != null && r != null) {
                transcription = Transcription(
                    Transcript(text, r.engine, r.model, wallClock(), latencyMs = (t1 - endedAtNs) / NS_PER_MS),
                    SttTiming(
                        computeMs = computeNs / NS_PER_MS,
                        queuedMs = (t0 - postedNs) / NS_PER_MS,
                        finalizeMs = (t1 - t0) / NS_PER_MS,
                    ),
                )
            }
        }
        s?.let { runCatching { it.release() } }
        if (transcription == null) {
            untranscribed++
            if (p != null && p.behindBy >= 0) listener.fellBehind(p.startSample, p.behindBy)
        }
        sink.closed(utterance, pcm, transcription)
    }

    private fun recognizer(): StreamingRecognizer? {
        if (!loadAttempted && !abandoned) {
            loadAttempted = true
            recognizer = try {
                load()
            } catch (e: Throwable) {
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

    /**
     * Runs a recognizer call, reporting a failure against the open utterance
     * as null. An [Error] means the natives cannot be trusted: no further
     * recognition this Session.
     */
    private fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        if (e !is Exception) abandoned = true
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
    /** From the cut to the worker reaching it: decoding of earlier audio still queued. */
    val queuedMs: Long,
    /** The final flush alone: what decoding adds after the utterance is cut. */
    val finalizeMs: Long,
) {
    /** Real-time factor: recognizer time over audio time. */
    fun rtf(audioMs: Long): Double = if (audioMs <= 0) 0.0 else computeMs.toDouble() / audioMs

    companion object {
        /** One log line: the persisted latency, where it went, and the RTF. */
        fun summary(turn: Turn, timing: SttTiming?): String {
            val t = turn.transcript
            if (t == null || timing == null) return "no transcript"
            val audioMs = turn.audio.durationMs
            return "transcript ${t.latencyMs} ms after the last sample (queued ${timing.queuedMs} ms, finish ${timing.finalizeMs} ms), " +
                "RTF ${threeDecimals(timing.rtf(audioMs))} (${timing.computeMs} ms for $audioMs ms of audio)"
        }

        /** Locale-independent, unlike `"%.3f".format`. */
        private fun threeDecimals(x: Double): String {
            val m = (x * 1000).roundToLong()
            return "${m / 1000}.${(m % 1000).toString().padStart(3, '0')}"
        }
    }
}
