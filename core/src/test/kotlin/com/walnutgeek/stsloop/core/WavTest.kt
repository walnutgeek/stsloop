package com.walnutgeek.stsloop.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WavTest {
    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    @Test
    fun `canonical 44-byte PCM16 mono header`() {
        val h = Wav.header(sampleRate = 16_000, dataBytes = 64_000)
        assertEquals(44, h.size)
        assertEquals("RIFF", ascii(h, 0))
        assertEquals(36 + 64_000, le32(h, 4))
        assertEquals("WAVE", ascii(h, 8))
        assertEquals("fmt ", ascii(h, 12))
        assertEquals(16, le32(h, 16)) // fmt chunk size
        assertEquals(1, le16(h, 20)) // PCM
        assertEquals(1, le16(h, 22)) // mono
        assertEquals(16_000, le32(h, 24))
        assertEquals(32_000, le32(h, 28)) // byte rate
        assertEquals(2, le16(h, 32)) // block align
        assertEquals(16, le16(h, 34)) // bits per sample
        assertEquals("data", ascii(h, 36))
        assertEquals(64_000, le32(h, 40))
    }

    @Test
    fun `PCM16 samples become little-endian bytes`() {
        val out = ByteArray(6)
        Wav.pcm16ToLittleEndian(shortArrayOf(0x0102, -2, Short.MIN_VALUE, 99), count = 3, out = out)
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0xfe.toByte(), 0xff.toByte(), 0x00, 0x80.toByte()), out)
    }

    @Test
    fun `PCM16 is two bytes per sample`() = assertEquals(2, Wav.BYTES_PER_SAMPLE)

    @Test
    fun `duration is derived from the sample count`() {
        assertEquals(4471, Wav.durationMs(samples = 71_536, sampleRate = 16_000))
        assertEquals(0, Wav.durationMs(samples = 0, sampleRate = 16_000))
    }
}
