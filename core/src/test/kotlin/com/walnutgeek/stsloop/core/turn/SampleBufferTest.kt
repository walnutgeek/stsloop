package com.walnutgeek.stsloop.core.turn

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SampleBufferTest {
    private fun shorts(range: IntRange) = ShortArray(range.count()) { (range.first + it).toShort() }

    @Test
    fun `starts empty at sample zero`() {
        val b = SampleBuffer()
        assertEquals(0, b.start)
        assertEquals(0, b.end)
        assertEquals(0, b.size)
    }

    @Test
    fun `appends only the first count samples`() {
        val b = SampleBuffer()
        b.append(shorts(0..9), 4)
        assertEquals(4, b.end)
        assertArrayEquals(shorts(0..3), b.copy(0, 4))
    }

    @Test
    fun `copies any retained range by absolute sample index`() {
        val b = SampleBuffer()
        b.append(shorts(0..99), 100)
        assertArrayEquals(shorts(10..19), b.copy(10, 20))
        assertArrayEquals(ShortArray(0), b.copy(50, 50))
    }

    @Test
    fun `dropping keeps absolute indices stable`() {
        val b = SampleBuffer()
        b.append(shorts(0..99), 100)
        b.dropBefore(60)
        assertEquals(60, b.start)
        assertEquals(40, b.size)
        assertArrayEquals(shorts(60..69), b.copy(60, 70))
        b.append(shorts(100..149), 50)
        assertArrayEquals(shorts(90..129), b.copy(90, 130))
    }

    @Test
    fun `dropping backwards is a no-op`() {
        val b = SampleBuffer()
        b.append(shorts(0..9), 10)
        b.dropBefore(5)
        b.dropBefore(2)
        assertEquals(5, b.start)
    }

    @Test
    fun `cannot drop past the end`() {
        val b = SampleBuffer()
        b.append(shorts(0..9), 10)
        assertThrows<IllegalArgumentException> { b.dropBefore(11) }
    }

    @Test
    fun `copying dropped or future samples throws`() {
        val b = SampleBuffer()
        b.append(shorts(0..99), 100)
        b.dropBefore(50)
        assertThrows<IllegalArgumentException> { b.copy(49, 60) }
        assertThrows<IllegalArgumentException> { b.copy(60, 101) }
    }

    @Test
    fun `floats are samples over 32768`() {
        val b = SampleBuffer()
        b.append(shortArrayOf(0, 16384, -16384, 32767, -32768), 5)
        val out = FloatArray(5)
        b.toFloats(0, out)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f, 32767f / 32768f, -1f), out)
    }

    @Test
    fun `memory stays bounded under a sliding window`() {
        val b = SampleBuffer()
        val chunk = shorts(0..1599)
        repeat(10_000) {
            b.append(chunk, chunk.size)
            b.dropBefore(b.end - 5000)
        }
        assertEquals(5000, b.size)
        assertEquals(16_000_000, b.end)
        assert(b.capacity < 20_000) { "capacity grew to ${b.capacity}" }
    }

    @Test
    fun `a skip drops what is retained and moves the stream on`() {
        val b = SampleBuffer()
        b.append(shorts(0..99), 100)
        b.skip(500)
        assertEquals(0, b.size)
        assertEquals(600, b.start)
        b.append(shorts(600..609), 10)
        assertEquals(610, b.end)
        assertArrayEquals(shorts(600..609), b.copy(600, 610))
    }
}
