package com.walnutgeek.stsloop.core.testmode

import com.walnutgeek.stsloop.core.turn.CloseReason
import com.walnutgeek.stsloop.core.turn.Utterance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestModeTrackerTest {
    private val config = TestConfig(enabled = true, label = "desk")
    private val sr = 1000 // 1 sample per ms keeps the arithmetic readable
    private val ms = 1_000_000L

    /** A tracker that has captured [chunks] chunks of 100 samples, chunk i read at (i+1)*100 ms. */
    private fun tracker(chunks: Int, level: Short = 100) = TestModeTracker(config, sr).apply {
        for (i in 1..chunks) captured(samplesCaptured = i * 100L, atNs = i * 100 * ms, chunk = ShortArray(100) { level }, count = 100)
    }

    private fun u(start: Long, end: Long) = Utterance(start, end, end - start, 0, CloseReason.SILENCE)

    @Test
    fun `TTS callbacks are placed on the sample clock`() {
        val t = tracker(10)
        assertEquals(1050L, t.ttsStarted("tts-1", atNs = 1050 * ms))
        assertEquals(1500L, t.ttsDone("tts-1", atNs = 1500 * ms))
    }

    @Test
    fun `a TTS callback before the first read cannot be placed`() {
        val t = TestModeTracker(config, sr)
        assertNull(t.ttsStarted("tts-1", atNs = 5 * ms))
    }

    @Test
    fun `a Turn's test block has the config, the overlap in ms, and the phrases that overlapped`() {
        val t = tracker(30)
        t.ttsStarted("tts-1", 1000 * ms)
        t.ttsDone("tts-1", 1600 * ms)
        t.ttsStarted("tts-2", 2600 * ms)
        t.ttsDone("tts-2", 2900 * ms)
        val test = t.turnTest(u(1200, 2000), nowNs = 3000 * ms)
        assertEquals(config, test.config)
        assertEquals(400, test.ttsOverlapMs)
        assertEquals(listOf("tts-1"), test.ttsPhrases)
        assertTrue(test.ttsOverlap)
        assertFalse(t.turnTest(u(1700, 2500), nowNs = 3000 * ms).ttsOverlap)
    }

    @Test
    fun `the overlap is converted at the stream's sample rate`() {
        val t = TestModeTracker(config, 16_000)
        t.captured(1600, 100 * ms, ShortArray(1600), 1600)
        t.ttsStarted("a", 100 * ms) // sample 1600
        t.ttsDone("a", 200 * ms) // sample 3200
        assertEquals(100, t.turnTest(u(0, 16_000), nowNs = 1000 * ms).ttsOverlapMs)
    }

    @Test
    fun `input devices routed during the Turn are listed`() {
        val t = tracker(30)
        t.inputRouted("builtin_mic", atNs = 0)
        t.inputRouted("bluetooth_sco", atNs = 1500 * ms)
        assertEquals(listOf("builtin_mic"), t.turnTest(u(0, 1000), 3000 * ms).inputDevices)
        assertEquals(listOf("builtin_mic", "bluetooth_sco"), t.turnTest(u(1000, 2000), 3000 * ms).inputDevices)
        assertEquals("bluetooth_sco", t.currentInput)
    }

    @Test
    fun `a route reported before the first read applies from the start of the stream`() {
        val t = TestModeTracker(config, sr)
        t.inputRouted("builtin_mic", atNs = 1)
        t.captured(100, 100 * ms, ShortArray(100), 100)
        assertEquals(listOf("builtin_mic"), t.turnTest(u(0, 100), 100 * ms).inputDevices)
    }

    @Test
    fun `mic level over a span, in dBFS`() {
        val t = tracker(10, level = 3277)
        assertEquals(-20.0, t.micDbfs(0, 1000)!!, 0.01)
        assertNull(t.micDbfs(2000, 3000))
    }
}
