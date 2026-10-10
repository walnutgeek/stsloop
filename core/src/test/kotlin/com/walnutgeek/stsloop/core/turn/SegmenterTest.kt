package com.walnutgeek.stsloop.core.turn

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The tee: one int16 stream, cut into VAD windows and Turn Recordings. Runs at
 * 1000 Hz with 10-sample windows, so a sample is a millisecond; the fake VAD
 * calls a window speech when any sample is loud.
 */
class SegmenterTest {
    private val timings = Timings(trailingSilenceMs = 100, maxUtteranceMs = 1000, minUtteranceMs = 30, preRollMs = 20)
    private val loudVad = SpeechProbability { w -> if (w.any { abs(it) >= 0.25f }) 1f else 0f }

    private class Sink : UtteranceSink {
        val closed = mutableListOf<Pair<Utterance, ShortArray>>()
        val discarded = mutableListOf<TurnEvent.Discarded>()
        override fun closed(utterance: Utterance, pcm: ShortArray) { closed += utterance to pcm }
        override fun discarded(event: TurnEvent.Discarded) { discarded += event }
    }

    /** A stream of quiet (|x| < 1000, nonzero) and loud (|x| >= 9000) stretches, in ms. */
    private fun stream(vararg spans: Pair<Boolean, Int>, seed: Int = 1): ShortArray {
        val rnd = Random(seed)
        val out = ArrayList<Short>()
        for ((loud, ms) in spans) repeat(ms) {
            val mag = if (loud) rnd.nextInt(9000, 32767) else rnd.nextInt(1, 1000)
            out += (if (rnd.nextBoolean()) mag else -mag).toShort()
        }
        return out.toShortArray()
    }

    private fun speech(ms: Int) = true to ms
    private fun quiet(ms: Int) = false to ms

    private fun run(pcm: ShortArray, chunk: Int, vad: SpeechProbability = loudVad, finish: Boolean = true): Sink {
        val sink = Sink()
        val seg = Segmenter(timings, 1000, 10, vad, sink)
        var i = 0
        val buf = ShortArray(chunk)
        while (i < pcm.size) {
            val n = minOf(chunk, pcm.size - i)
            pcm.copyInto(buf, 0, i, i + n)
            seg.accept(buf, n)
            i += n
        }
        if (finish) seg.finish()
        return sink
    }

    private val threeUtterances = stream(
        quiet(300), speech(400), quiet(500), speech(250), quiet(200), speech(15), quiet(300), speech(600), quiet(150),
    )

    @Test
    fun `each utterance between silences is its own closed utterance`() {
        val sink = run(threeUtterances, 160)
        assertEquals(3, sink.closed.size)
        assertEquals(1, sink.discarded.size) // the 15 ms blip
    }

    @Test
    fun `each utterance's audio is byte-identical to its range of the stream`() {
        val sink = run(threeUtterances, 160)
        for ((u, pcm) in sink.closed) {
            assertArrayEquals(threeUtterances.copyOfRange(u.startSample.toInt(), u.endSample.toInt()), pcm)
        }
    }

    @Test
    fun `utterance ranges follow pre-roll and trailing silence`() {
        val spans = run(threeUtterances, 160).closed.map { it.first.startSample to it.first.endSample }
        // The third onset is window-aligned: speech starts at 1965, its first loud window at 1960.
        assertEquals(listOf(280L to 800L, 1180L to 1550L, 1940L to 2670L), spans)
    }

    @Test
    fun `chunk size does not change the result`() {
        val reference = run(threeUtterances, 160).closed
        for (chunk in listOf(1, 7, 10, 333, 1600, threeUtterances.size)) {
            val got = run(threeUtterances, chunk).closed
            assertEquals(reference.map { it.first }, got.map { it.first }, "chunk $chunk")
            for (k in reference.indices) assertArrayEquals(reference[k].second, got[k].second, "chunk $chunk")
        }
    }

    @Test
    fun `the vad sees every full window in order, scaled by 1 over 32768`() {
        val pcm = stream(quiet(95), speech(40), quiet(48))
        val seen = mutableListOf<Float>()
        run(pcm, 33, { w -> assertEquals(10, w.size); seen += w.toList(); 0f })
        val full = pcm.size / 10 * 10
        assertEquals(full, seen.size)
        for (i in 0 until full) assertEquals(pcm[i] / 32768f, seen[i])
    }

    @Test
    fun `the caller's buffer is not mutated`() {
        val pcm = threeUtterances.copyOf()
        val seg = Segmenter(timings, 1000, 10, loudVad, Sink())
        seg.accept(pcm, pcm.size)
        assertArrayEquals(threeUtterances, pcm)
    }

    @Test
    fun `only the first count samples of a chunk are used`() {
        val sink = Sink()
        val seg = Segmenter(timings, 1000, 10, loudVad, sink)
        val chunk = ShortArray(100) { 20000 }
        seg.accept(chunk, 0) // nothing
        seg.accept(ShortArray(100), 50)
        seg.accept(chunk, 60)
        seg.accept(ShortArray(200), 200)
        val (u, pcm) = sink.closed.single()
        assertEquals(30L, u.startSample)
        assertEquals(60L, u.speechSamples)
        assertTrue(pcm.copyOfRange(20, 80).all { it == 20000.toShort() })
    }

    @Test
    fun `finish closes an utterance still being captured`() {
        val pcm = stream(quiet(100), speech(300), quiet(40))
        val sink = run(pcm, 160)
        val (u, audio) = sink.closed.single()
        assertEquals(CloseReason.SESSION_END, u.closedBy)
        assertEquals(440L, u.endSample)
        assertArrayEquals(pcm.copyOfRange(80, 440), audio)
    }

    @Test
    fun `finish includes the retained samples the vad never judged`() {
        val pcm = stream(quiet(100), speech(300), quiet(47)) // the last 7 samples are a partial window
        val sink = run(pcm, 160)
        val (u, audio) = sink.closed.single()
        assertEquals(447L, u.endSample)
        assertEquals(47L, u.trailingSilenceSamples)
        assertArrayEquals(pcm.copyOfRange(80, 447), audio)
    }

    @Test
    fun `finish twice is harmless`() {
        val sink = Sink()
        val seg = Segmenter(timings, 1000, 10, loudVad, sink)
        val pcm = stream(speech(300))
        seg.accept(pcm, pcm.size)
        seg.finish()
        seg.finish()
        assertEquals(1, sink.closed.size)
    }

    @Test
    fun `release-level speech runs across max cuts into contiguous utterances`() {
        val halfLoud = SpeechProbability { w -> if (w.any { abs(it) >= 0.25f }) 1f else if (w.any { abs(it) >= 0.05f }) 0.4f else 0f }
        // 100 ms quiet, 100 ms loud, then 1500 ms of mid-level (|x| 2000..4000: release-level only)
        val rnd = Random(3)
        val mid = ShortArray(1500) { (rnd.nextInt(2000, 4000) * if (it % 2 == 0) 1 else -1).toShort() }
        val pcm = stream(quiet(100), speech(100)) + mid + stream(quiet(200), seed = 2)
        val sink = run(pcm, 160, halfLoud)
        assertEquals(listOf(80L to 1080L, 1080L to 1800L), sink.closed.map { it.first.startSample to it.first.endSample })
        val joined = sink.closed.flatMap { it.second.toList() }.toShortArray()
        assertArrayEquals(pcm.copyOfRange(80, 1800), joined)
    }

    @Test
    fun `without finish an open utterance is not emitted`() {
        val sink = run(stream(quiet(100), speech(300)), 160, finish = false)
        assertEquals(0, sink.closed.size)
    }

    @Test
    fun `finish while listening emits nothing`() {
        val sink = run(stream(quiet(100), speech(300), quiet(300)), 160)
        assertEquals(1, sink.closed.size)
    }

    @Test
    fun `runaway speech becomes contiguous max-length utterances`() {
        val pcm = stream(quiet(100), speech(2500), quiet(200))
        val sink = run(pcm, 1600)
        assertEquals(listOf(1000L, 1000L, 620L), sink.closed.map { it.first.lengthSamples })
        val joined = sink.closed.flatMap { it.second.toList() }.toShortArray()
        assertArrayEquals(pcm.copyOfRange(80, 2700), joined)
    }

    @Test
    fun `a long listening stretch keeps only pre-roll plus a partial window in memory`() {
        val seg = Segmenter(timings, 1000, 10, loudVad, Sink())
        val chunk = stream(quiet(1600))
        repeat(1000) { seg.accept(chunk, chunk.size) }
        assertTrue(seg.retainedSamples <= 20 + 10, "retained ${seg.retainedSamples}")
    }

    @Test
    fun `capturing retains the whole utterance, and closing releases it`() {
        val seg = Segmenter(timings, 1000, 10, loudVad, Sink())
        val talk = stream(quiet(100), speech(800))
        seg.accept(talk, talk.size)
        assertEquals(820, seg.retainedSamples)
        val q = stream(quiet(300))
        seg.accept(q, q.size)
        assertTrue(seg.retainedSamples <= 30, "retained ${seg.retainedSamples}")
    }

    @Test
    fun `window size must be positive`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { Segmenter(timings, 1000, 0, loudVad, Sink()) }
    }
}
