package com.walnutgeek.stsloop.core.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The live mic stream: sustained digital silence is a failure, a quiet room is not. */
class DeadMicTest {
    private val rate = 16_000
    private val changes = mutableListOf<Pair<Boolean, Long>>()
    private var fed = 0L
    private val mic = DeadMic(rate, silentAfterMs = 3000) { silent, atSample -> changes += silent to atSample }

    private fun feed(ms: Int, value: Short, chunk: Int = 1600) {
        var left = rate * ms / 1000
        while (left > 0) {
            val n = minOf(chunk, left)
            val buf = ShortArray(n) { i -> if (i % 2 == 0) value else (-value).toShort() }
            mic.accept(buf, n)
            fed += n
            left -= n
        }
    }

    @Test
    fun `zeros just short of the limit are not reported`() {
        feed(2900, 0)
        assertEquals(emptyList<Pair<Boolean, Long>>(), changes)
        assertFalse(mic.silent)
    }

    @Test
    fun `zeros for the limit are reported once, at the sample they reach it`() {
        feed(10_000, 0)
        assertEquals(listOf(true to 48_000L), changes)
        assertTrue(mic.silent)
    }

    @Test
    fun `sound again is reported at the first loud window`() {
        feed(500, 300)
        feed(4000, 0)
        feed(1000, 300)
        assertEquals(listOf(true to 8000L + 48_000L, false to 8000L + 64_000L + 1600L), changes)
        assertFalse(mic.silent)
    }

    @Test
    fun `a quiet but working mic is never silent`() {
        feed(60_000, 3) // RMS 3: the car's SCO mic, parked
        assertEquals(emptyList<Pair<Boolean, Long>>(), changes)
    }

    @Test
    fun `a loud window restarts the count`() {
        feed(2500, 0)
        feed(100, 300)
        feed(2500, 0)
        assertEquals(emptyList<Pair<Boolean, Long>>(), changes)
        feed(500, 0)
        assertEquals(1, changes.size)
    }

    @Test
    fun `chunks that do not align with windows still count every sample`() {
        feed(3000, 0, chunk = 333)
        assertEquals(listOf(true to 48_000L), changes)
    }
}
