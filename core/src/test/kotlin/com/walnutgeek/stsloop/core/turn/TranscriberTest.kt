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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The TRANSCRIBE stage. Capture-side calls only post work; a [ManualWorker]
 * stands in for the recognizer thread, so each test decides when it runs.
 * A fake clock advances 10 ms per sample accepted and 50 ms per finish.
 */
class TranscriberTest {
    private class ManualWorker : Worker {
        val queue = ArrayDeque<() -> Unit>()
        override fun post(task: () -> Unit) { queue += task }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst()() }
    }

    private var nowNs = 0L
    private var wallMs = 5_000_000L

    private inner class FakeStream(val n: Int) : RecognitionStream {
        val samples = mutableListOf<Float>()
        var finished = 0
        var released = 0
        var failAccept = false
        var failFinish = false

        override fun accept(samples: FloatArray) {
            if (failAccept) throw IllegalStateException("native accept failed")
            this.samples += samples.toList()
            nowNs += samples.size * 10_000_000L
        }

        override fun finish(): String {
            finished++
            if (failFinish) throw IllegalStateException("native decode failed")
            nowNs += 50_000_000L
            wallMs += 70
            return "  TURN  $n "
        }

        override fun release() { released++ }
    }

    private inner class FakeRecognizer : StreamingRecognizer {
        override val engine = "sherpa-onnx"
        override val model = "fake-model greedy"
        val streams = mutableListOf<FakeStream>()
        var closed = 0
        var onOpen: (FakeStream) -> Unit = {}

        override fun open(): RecognitionStream = FakeStream(streams.size + 1).also { onOpen(it); streams += it }
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
    private val published = mutableListOf<Triple<Turn, Utterance, SttTiming?>>()
    private val discards = mutableListOf<TurnEvent.Discarded>()
    private val unavailable = mutableListOf<Exception>()
    private val failures = mutableListOf<Pair<Long, Exception>>()

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
            worker, { load() }, sink, wallClock = { wallMs }, nanoTime = { nowNs },
            listener = object : Transcriber.Listener {
                override fun unavailable(error: Exception) { unavailable += error }
                override fun failed(startSample: Long, error: Exception) { failures += startSample to error }
            },
        )
    }

    private fun u(start: Long, end: Long, reason: CloseReason = CloseReason.SILENCE) =
        Utterance(start, end, end - start - 100, 100, reason)

    /** One utterance through the capture side: opened, streamed in two pieces, closed. */
    private fun utterance(start: Long, end: Long) {
        val mid = (start + end) / 2
        transcriber.opened(start)
        transcriber.captured(FloatArray((mid - start).toInt()) { 0.25f })
        transcriber.captured(FloatArray((end - mid).toInt()) { -0.5f })
        transcriber.closed(u(start, end), ShortArray((end - start).toInt()))
    }

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
    fun `each Turn's transcript is the normalised text of its own stream, with engine, model and finished_at`() {
        transcriber.start()
        utterance(0, 400)
        utterance(1000, 1300)
        worker.runAll()
        val transcripts = published.map { it.first.transcript!! }
        assertEquals(listOf("turn 1", "turn 2"), transcripts.map { it.text })
        assertEquals(listOf(5_000_070L, 5_000_140L), transcripts.map { it.finishedAtMs })
        assertTrue(transcripts.all { it.engine == "sherpa-onnx" && it.model == "fake-model greedy" })
        assertTrue(published.all { it.first.kind == TurnKind.UNCLASSIFIED })
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
    fun `timing counts recognizer time over the whole Turn and the finish alone`() {
        transcriber.start()
        utterance(0, 400) // 400 samples * 10 ms + 50 ms finish
        worker.runAll()
        assertEquals(SttTiming(computeMs = 4050, backlogMs = 4000, finalizeMs = 50), published.single().third)
    }

    @Test
    fun `backlog is the time from the close being posted to the worker reaching it`() {
        transcriber.start()
        transcriber.opened(0)
        transcriber.captured(FloatArray(30)) // 300 ms of decoding still queued at the close
        transcriber.closed(u(0, 30), ShortArray(30))
        worker.runAll()
        assertEquals(300L, published.single().third!!.backlogMs)
    }

    @Test
    fun `without start, the recognizer is loaded by the first Turn, on the worker`() {
        utterance(0, 400)
        assertEquals(0, loads)
        worker.runAll()
        assertEquals(1, loads)
        assertEquals("turn 1", published.single().first.transcript!!.text)
    }

    @Test
    fun `a recognizer that fails to load is reported once and Turns are published without transcripts`() {
        load = { loads++; throw IllegalStateException("missing asset") }
        transcriber.start()
        utterance(0, 400)
        utterance(1000, 1300)
        worker.runAll()
        assertEquals(1, loads)
        assertEquals("missing asset", unavailable.single().message)
        assertEquals(2, published.size)
        assertTrue(published.all { it.first.transcript == null && it.third == null && it.first.kind == TurnKind.UNCLASSIFIED })
    }

    @Test
    fun `a stream that fails mid-Turn loses only that Turn's transcript`() {
        recognizer.onOpen = { if (it.n == 1) it.failAccept = true }
        transcriber.start()
        utterance(0, 400)
        utterance(1000, 1300)
        worker.runAll()
        assertNull(published[0].first.transcript)
        assertEquals("turn 2", published[1].first.transcript!!.text)
        assertEquals(0L, failures.single().first)
        assertEquals(1, recognizer.streams[0].released)
        assertEquals(0, recognizer.streams[0].finished)
    }

    @Test
    fun `a failing finish is reported and the Turn is published without a transcript`() {
        recognizer.onOpen = { it.failFinish = true }
        transcriber.start()
        utterance(1000, 1300)
        worker.runAll()
        assertNull(published.single().first.transcript)
        assertEquals("native decode failed", failures.single().second.message)
        assertEquals(1, recognizer.streams.single().released)
    }

    @Test
    fun `a failing open is reported and the Turn is published without a transcript`() {
        recognizer.onOpen = { throw IllegalStateException("no stream") }
        transcriber.start()
        utterance(0, 400)
        worker.runAll()
        assertNull(published.single().first.transcript)
        assertEquals("no stream", failures.single().second.message)
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
    fun `latency is finished_at minus ended_at, and the summary carries it with the RTF`() {
        val turn = Turn(
            "a3f1c9", "s", startedAtMs = 1_000, audio = TurnAudio("audio.wav", "x", 16000, 4000), appVersion = "t",
            transcript = Transcript("hi", "sherpa-onnx", "m", finishedAtMs = 5_250),
        )
        assertEquals(250L, turn.transcriptLatencyMs)
        assertNull(turn.copy(transcript = null).transcriptLatencyMs)
        val timing = SttTiming(computeMs = 348, backlogMs = 120, finalizeMs = 96)
        assertEquals(0.087, timing.rtf(4000), 1e-9)
        assertEquals(
            "transcript 250 ms after ended_at (backlog 120 ms, finish 96 ms), RTF 0.087 (348 ms for 4000 ms of audio)",
            timing.summary(turn),
        )
        assertEquals("no transcript", SttTiming.summary(turn.copy(transcript = null), null))
        assertEquals(0.0, timing.rtf(0))
    }
}
