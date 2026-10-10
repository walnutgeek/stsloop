package com.walnutgeek.stsloop.core.testmode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimelineTest {
    // --- SampleClock: monotonic nanos → stream sample offsets ---

    @Test
    fun `no sample is known before the first read`() {
        assertNull(SampleClock(16_000).sampleAt(123))
    }

    @Test
    fun `a time maps to the sample offset placed by the sample rate from the last read`() {
        val clock = SampleClock(16_000)
        // 1600 samples had been captured when a read returned at t = 1 s.
        clock.update(samplesCaptured = 1600, atNs = 1_000_000_000)
        assertEquals(1600, clock.sampleAt(1_000_000_000))
        assertEquals(1600 + 160, clock.sampleAt(1_010_000_000)) // 10 ms later
        assertEquals(1600 - 1600, clock.sampleAt(900_000_000)) // 100 ms earlier
    }

    @Test
    fun `a time before the stream began maps to sample 0`() {
        val clock = SampleClock(1000)
        clock.update(100, 1_000_000_000)
        assertEquals(0, clock.sampleAt(0))
    }

    @Test
    fun `the latest read re-anchors the clock, so drift never accumulates`() {
        val clock = SampleClock(1000)
        clock.update(100, 1_000_000_000)
        clock.update(250, 1_100_000_000) // reads came 50 samples "late": the newest anchor wins
        assertEquals(250, clock.sampleAt(1_100_000_000))
        assertEquals(260, clock.sampleAt(1_110_000_000))
    }

    // --- TtsTimeline: overlap of TTS playback with a Turn's audio span ---

    private fun timeline(vararg intervals: Triple<String, Long, Long?>) = TtsTimeline().apply {
        for ((id, start, end) in intervals) {
            started(id, start)
            if (end != null) done(id, end)
        }
    }

    @Test
    fun `no TTS, no overlap`() {
        val o = TtsTimeline().overlap(0, 1000, now = 1000)
        assertFalse(o.phrases.isNotEmpty())
        assertEquals(0, o.samples)
        assertEquals(emptyList<String>(), o.phrases)
    }

    @Test
    fun `an interval inside the Turn overlaps by its length`() {
        val o = timeline(Triple("tts-1", 200, 500)).overlap(100, 1000, now = 2000)
        assertTrue(o.phrases.isNotEmpty())
        assertEquals(300, o.samples)
        assertEquals(listOf("tts-1"), o.phrases)
    }

    @Test
    fun `partial overlaps at either edge count only the shared part`() {
        val t = timeline(Triple("a", 0, 150), Triple("b", 900, 1200))
        val o = t.overlap(100, 1000, now = 2000)
        assertEquals(50 + 100, o.samples)
        assertEquals(listOf("a", "b"), o.phrases)
    }

    @Test
    fun `touching end points do not overlap`() {
        val t = timeline(Triple("before", 0, 100), Triple("after", 1000, 1100))
        assertFalse(t.overlap(100, 1000, now = 2000).phrases.isNotEmpty())
    }

    @Test
    fun `an interval wholly outside the Turn does not overlap`() {
        assertFalse(timeline(Triple("x", 2000, 2500)).overlap(0, 1000, now = 3000).phrases.isNotEmpty())
    }

    @Test
    fun `a phrase still playing extends to now`() {
        val t = timeline(Triple("open", 800, null))
        val o = t.overlap(500, 1000, now = 900)
        assertEquals(100, o.samples)
        assertEquals(listOf("open"), o.phrases)
        // ...and is clipped by the Turn's end once now has passed it.
        assertEquals(200, t.overlap(500, 1000, now = 5000).samples)
    }

    @Test
    fun `a phrase that started after now cannot overlap yet`() {
        assertFalse(timeline(Triple("later", 950, null)).overlap(0, 1000, now = 900).phrases.isNotEmpty())
    }

    @Test
    fun `done without started is ignored, and a second done keeps the first end`() {
        val t = TtsTimeline()
        t.done("ghost", 100)
        t.started("a", 0)
        t.done("a", 100)
        t.done("a", 900)
        assertEquals(100, t.overlap(0, 1000, now = 1000).samples)
    }

    @Test
    fun `old intervals can be dropped once no open Turn can reach them`() {
        val t = timeline(Triple("old", 0, 100), Triple("new", 5000, 5100), Triple("open", 6000, null))
        t.forgetBefore(1000)
        assertEquals(listOf("new", "open"), t.overlap(0, 10_000, now = 10_000).phrases)
    }

    // --- StepTimeline: which input device was routed during a Turn ---

    @Test
    fun `values in effect over a span, in order, without repeats`() {
        val t = StepTimeline<String>()
        assertEquals(emptyList<String>(), t.during(0, 100))
        t.set(0, "builtin_mic")
        t.set(500, "bluetooth_sco")
        t.set(800, "builtin_mic")
        assertEquals(listOf("builtin_mic"), t.during(0, 500))
        assertEquals(listOf("builtin_mic", "bluetooth_sco"), t.during(400, 600))
        assertEquals(listOf("bluetooth_sco", "builtin_mic"), t.during(500, 900))
        assertEquals(listOf("builtin_mic", "bluetooth_sco"), t.during(0, 2000)) // first seen order, each once
        assertEquals(listOf("builtin_mic"), t.during(900, 1000))
    }

    @Test
    fun `a value set before the stream applies from the start`() {
        val t = StepTimeline<String>()
        t.set(-5, "a")
        assertEquals(listOf("a"), t.during(0, 10))
    }

    @Test
    fun `setting the same value again is not a change`() {
        val t = StepTimeline<String>()
        t.set(0, "a")
        t.set(50, "a")
        t.set(100, "b")
        assertEquals(listOf("a", "b"), t.during(0, 200))
    }

    @Test
    fun `the latest value is known`() {
        val t = StepTimeline<String>()
        assertNull(t.latest)
        t.set(0, "a")
        t.set(10, "b")
        assertEquals("b", t.latest)
    }
}
