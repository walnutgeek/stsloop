package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.Transcript
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnAudio
import com.walnutgeek.stsloop.core.TurnInProgress
import com.walnutgeek.stsloop.core.TurnKind
import com.walnutgeek.stsloop.core.TurnVad
import com.walnutgeek.stsloop.core.speech.RecognitionStream
import com.walnutgeek.stsloop.core.speech.StreamingRecognizer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The TRANSCRIBE stage. Capture-side calls only post work; a [ManualWorker]
 * stands in for the recognizer thread, so each test decides when it runs.
 * One monotonic fake clock serves capture and recognizer: it advances 10 ms
 * per sample accepted and 50 ms per finish.
 */
class TranscriberTest {
    private class ManualWorker : Worker {
        val queue = ArrayDeque<() -> Unit>()
        override fun post(task: () -> Unit) { queue += task }
        fun runOne() = queue.removeFirst()()
        fun runAll() { while (queue.isNotEmpty()) runOne() }
    }

    private var nowNs = 0L
    private var wallMs = 5_000_000L

    private inner class FakeStream(val n: Int) : RecognitionStream {
        val samples = mutableListOf<Float>()
        var finished = 0
        var released = 0
        var acceptError: Throwable? = null
        var finishError: Throwable? = null

        override fun accept(samples: FloatArray) {
            check(released == 0) { "use after release" }
            acceptError?.let { throw it }
            this.samples += samples.toList()
            nowNs += samples.size * 10_000_000L
        }

        override fun finish(): String {
            check(released == 0) { "use after release" }
            finished++
            finishError?.let { throw it }
            nowNs += 50_000_000L
            wallMs += 70
            return " TURN $n"
        }

        override fun release() { released++ }
    }

    private inner class FakeRecognizer : StreamingRecognizer {
        override val engine = "sherpa-onnx"
        override val model = "fake-model greedy"
        val streams = mutableListOf<FakeStream>()
        var closed = 0
        var onOpen: (FakeStream) -> Unit = {}

        override fun open(): RecognitionStream {
            check(closed == 0) { "open after close" }
            return FakeStream(streams.size + 1).also { onOpen(it); streams += it }
        }

        override fun close() { closed++ }
    }

    private class FakeWriter : CorpusWriter {
        override fun begin(id: String, sessionId: String, startedAtMs: Long, sampleRate: Int) = object : TurnInProgress {
            var samples = 0
            override fun append(samples: ShortArray, count: Int) { this.samples += count }
            override fun finish(appVersion: String, vad: TurnVad?, transcript: Transcript?, kind: TurnKind?) =
                Turn(id, sessionId, startedAtMs, TurnAudio("audio.wav", "x", sampleRate, samples * 1000L / sampleRate), appVersion, vad, transcript, kind)
            override fun abandon() = throw IOException("unexpected")
        }
    }

    private val worker = ManualWorker()
    private val recognizer = FakeRecognizer()
    private var loads = 0
    private var load: () -> StreamingRecognizer = { loads++; recognizer }
    private var maxQueuedSamples = 1_000_000L
    private val published = mutableListOf<Triple<Turn, Utterance, SttTiming?>>()
    private val discards = mutableListOf<TurnEvent.Discarded>()
    private val unavailable = mutableListOf<Throwable>()
    private val failures = mutableListOf<Pair<Long, Throwable>>()
    private val behind = mutableListOf<Pair<Long, Long>>()

    private val transcriber by lazy {
        val sink = CorpusSink(
            FakeWriter(), "5e5510", sessionStartedAtMs = 1_000_000, sampleRate = 1000, appVersion = "t",
            listener = object : CorpusSink.Listener {
                override fun published(turn: Turn, utterance: Utterance, timing: SttTiming?) {
                    published += Triple(turn, utterance, timing)
                }
                override fun discarded(event: TurnEvent.Discarded) { discards += event }
            },
        )
        Transcriber(
            worker, { load() }, sink, wallClock = { wallMs }, nanoTime = { nowNs }, maxQueuedSamples = maxQueuedSamples,
            listener = object : Transcriber.Listener {
                override fun unavailable(error: Throwable) { unavailable += error }
                override fun failed(startSample: Long, error: Throwable) { failures += startSample to error }
                override fun fellBehind(startSample: Long, queuedSamples: Long) { behind += startSample to queuedSamples }
            },
        )
    }

    private fun u(start: Long, end: Long, reason: CloseReason = CloseReason.SILENCE) =
        Utterance(start, end, end - start - 100, 100, reason)

    /** One utterance through the capture side: opened, streamed in two pieces, closed now. */
    private fun utterance(start: Long, end: Long) {
        val mid = (start + end) / 2
        transcriber.opened(start)
        transcriber.captured(FloatArray((mid - start).toInt()) { 0.25f })
        transcriber.captured(FloatArray((end - mid).toInt()) { -0.5f })
        transcriber.closed(u(start, end), ShortArray((end - start).toInt()), endedAtNs = nowNs)
    }

    private val transcripts get() = published.map { it.first.transcript }

    @Test
    fun `capture-side calls only post work, and nothing is decoded or written until the worker runs`() {
        transcriber.start()
        utterance(0, 400)
        assertEquals(0, loads)
        assertEquals(0, recognizer.streams.size)
        assertEquals(0, published.size)
        worker.runAll()
        assertEquals(1, published.size)
    }

    @Test
    fun `each Turn's transcript is its own stream's raw text, with engine, model and finished_at`() {
        transcriber.start()
        utterance(0, 400)
        utterance(1000, 1300)
        worker.runAll()
        assertEquals(listOf(" TURN 1", " TURN 2"), transcripts.map { it!!.text })
        assertEquals(listOf(5_000_070L, 5_000_140L), transcripts.map { it!!.finishedAtMs })
        assertTrue(transcripts.all { it!!.engine == "sherpa-onnx" && it.model == "fake-model greedy" })
        assertTrue(published.all { it.first.kind == TurnKind.UNCLASSIFIED })
        assertEquals(0, transcriber.untranscribed)
    }

    @Test
    fun `latency runs on the monotonic clock from the Turn's last captured sample to the final text`() {
        transcriber.start()
        transcriber.opened(0)
        transcriber.captured(FloatArray(30))
        nowNs = 2_000_000_000L
        transcriber.closed(u(0, 30), ShortArray(30), endedAtNs = 1_900_000_000L)
        worker.runAll()
        // 100 ms before the close was seen, + 300 ms of queued decoding + 50 ms finish.
        assertEquals(450L, transcripts.single()!!.latencyMs)
    }

    @Test
    fun `one recognizer for the Session, one stream per Turn, each released after use`() {
        transcriber.start()
        utterance(0, 400)
        utterance(1000, 1300)
        utterance(2000, 2100)
        worker.runAll()
        assertEquals(1, loads)
        assertEquals(3, recognizer.streams.size)
        assertTrue(recognizer.streams.all { it.finished == 1 && it.released == 1 })
    }

    @Test
    fun `a stream receives exactly the samples captured for its Turn, in order`() {
        transcriber.start()
        utterance(0, 400)
        worker.runAll()
        val expected = FloatArray(400) { if (it < 200) 0.25f else -0.5f }
        assertArrayEquals(expected, recognizer.streams.single().samples.toFloatArray())
    }

    @Test
    fun `timing counts recognizer time over the whole Turn, the queue at the close, and the finish`() {
        transcriber.start()
        utterance(0, 400) // 400 samples * 10 ms still queued at the close, then a 50 ms finish
        worker.runAll()
        assertEquals(SttTiming(computeMs = 4050, queuedMs = 4000, finalizeMs = 50), published.single().third)
    }

    @Test
    fun `without start, the recognizer is loaded by the first Turn, on the worker`() {
        utterance(0, 400)
        assertEquals(0, loads)
        worker.runAll()
        assertEquals(1, loads)
        assertEquals(" TURN 1", transcripts.single()!!.text)
    }

    @Test
    fun `a recognizer that fails to load, even with an Error, is reported once and Turns keep their audio`() {
        load = { loads++; throw UnsatisfiedLinkError("no sherpa-onnx-jni") }
        transcriber.start()
        utterance(0, 400)
        utterance(1000, 1300)
        worker.runAll()
        assertEquals(1, loads)
        assertEquals("no sherpa-onnx-jni", unavailable.single().message)
        assertEquals(2, published.size)
        assertTrue(published.all { it.first.transcript == null && it.third == null && it.first.kind == TurnKind.UNCLASSIFIED })
        assertEquals(2, transcriber.untranscribed)
    }

    @Test
    fun `a stream that fails mid-Turn loses only that Turn's transcript`() {
        recognizer.onOpen = { if (it.n == 1) it.acceptError = IllegalStateException("native accept failed") }
        transcriber.start()
        utterance(0, 400)
        utterance(1000, 1300)
        worker.runAll()
        assertEquals(listOf(null, " TURN 2"), transcripts.map { it?.text })
        assertEquals(0L, failures.single().first)
        assertEquals(1, recognizer.streams[0].released)
        assertEquals(0, recognizer.streams[0].finished)
        assertEquals(1, transcriber.untranscribed)
    }

    @Test
    fun `a failing finish is reported and the Turn is published without a transcript`() {
        recognizer.onOpen = { it.finishError = IllegalStateException("native decode failed") }
        transcriber.start()
        utterance(1000, 1300)
        worker.runAll()
        assertNull(transcripts.single())
        assertEquals("native decode failed", failures.single().second.message)
        assertEquals(1, recognizer.streams.single().released)
    }

    @Test
    fun `a failing open is reported and the Turn is published without a transcript`() {
        recognizer.onOpen = { throw IllegalStateException("no stream") }
        transcriber.start()
        utterance(0, 400)
        worker.runAll()
        assertNull(transcripts.single())
        assertEquals("no stream", failures.single().second.message)
    }

    @Test
    fun `an Error inside the recognizer stops all recognition but no Turn's audio is lost`() {
        recognizer.onOpen = { if (it.n == 1) it.finishError = UnsatisfiedLinkError("native method missing") }
        transcriber.start()
        utterance(0, 400)
        utterance(1000, 1300)
        worker.runAll()
        assertEquals(2, published.size)
        assertEquals(listOf(null, null), transcripts)
        assertEquals(1, recognizer.streams.size) // natives are not touched again
        assertEquals(1, failures.size)
        assertEquals(2, transcriber.untranscribed)
    }

    @Test
    fun `a discarded utterance releases its stream and publishes nothing`() {
        transcriber.start()
        transcriber.opened(0)
        transcriber.captured(FloatArray(20))
        transcriber.discarded(TurnEvent.Discarded(0, 120, 20))
        worker.runAll()
        assertEquals(0, published.size)
        assertEquals(1, discards.size)
        assertEquals(1, recognizer.streams.single().released)
        assertEquals(0, recognizer.streams.single().finished)
    }

    @Test
    fun `when decoding falls behind, the Turn stops being fed, and keeps its audio without a transcript`() {
        maxQueuedSamples = 500
        transcriber.start()
        transcriber.opened(0)
        transcriber.captured(FloatArray(300))
        transcriber.captured(FloatArray(300)) // 600 queued > 500: not posted
        transcriber.captured(FloatArray(10)) // nor anything after it for this Turn
        assertEquals(3, worker.queue.size) // start, opened, the first 300 only
        transcriber.closed(u(0, 610), ShortArray(610), endedAtNs = nowNs)
        worker.runAll()
        val (turn, _, timing) = published.single()
        assertNull(turn.transcript)
        assertNull(timing)
        assertEquals(listOf(0L to 300L), behind) // reported with what was queued when it fell behind
        assertEquals(1, recognizer.streams.single().released)
        assertEquals(0, recognizer.streams.single().finished)
        assertEquals(1, transcriber.untranscribed)
    }

    @Test
    fun `queued feeds of a Turn that fell behind are skipped, and the next Turn transcribes once caught up`() {
        maxQueuedSamples = 500
        transcriber.start()
        transcriber.opened(0)
        transcriber.captured(FloatArray(300))
        transcriber.captured(FloatArray(150))
        transcriber.captured(FloatArray(100)) // 550 > 500: behind
        transcriber.closed(u(0, 550), ShortArray(550), endedAtNs = nowNs)
        worker.runAll()
        assertTrue(recognizer.streams.single().samples.isEmpty(), "a Turn that fell behind is not decoded further")
        utterance(1000, 1400)
        worker.runAll()
        assertEquals(listOf(null, " TURN 2"), transcripts.map { it?.text })
    }

    @Test
    fun `abandon skips the remaining recognition, still publishes every queued Turn, and releases natives in order`() {
        transcriber.start()
        utterance(0, 400)
        utterance(1000, 1300)
        transcriber.opened(2000)
        transcriber.captured(FloatArray(50))
        transcriber.close()
        worker.runOne() // start: load
        worker.runOne() // opened(0): a decode is in flight when time runs out
        transcriber.abandon()
        worker.runAll()
        assertEquals(2, published.size)
        assertEquals(listOf(null, null), transcripts)
        assertEquals(1, recognizer.streams.size)
        assertEquals(1, recognizer.streams.single().released)
        assertEquals(1, recognizer.closed)
        assertEquals(2, transcriber.untranscribed)
    }

    @Test
    fun `close releases an open stream and then the recognizer, after the work queued before it`() {
        transcriber.start()
        utterance(0, 400)
        transcriber.opened(1000)
        transcriber.close()
        worker.runAll()
        assertEquals(1, published.size)
        assertEquals(1, recognizer.streams[1].released)
        assertEquals(1, recognizer.closed)
    }

    @Test
    fun `close before anything loaded does not load the recognizer`() {
        transcriber.close()
        worker.runAll()
        assertEquals(0, loads)
    }

    @Test
    fun `the summary carries the persisted latency, the queue, the finish and the RTF`() {
        val turn = Turn(
            "a3f1c9", "s", startedAtMs = 1_000, audio = TurnAudio("audio.wav", "x", 16000, 4000), appVersion = "t",
            transcript = Transcript("HI", "sherpa-onnx", "m", finishedAtMs = 5_250, latencyMs = 313),
        )
        val timing = SttTiming(computeMs = 348, queuedMs = 120, finalizeMs = 96)
        assertEquals(0.087, timing.rtf(4000), 1e-9)
        assertEquals(
            "transcript 313 ms after the last sample (queued 120 ms, finish 96 ms), RTF 0.087 (348 ms for 4000 ms of audio)",
            SttTiming.summary(turn, timing),
        )
        assertEquals("no transcript", SttTiming.summary(turn.copy(transcript = null), null))
        assertEquals(0.0, timing.rtf(0))
        assertFalse(SttTiming.summary(turn, timing).contains("backlog"))
    }
}
