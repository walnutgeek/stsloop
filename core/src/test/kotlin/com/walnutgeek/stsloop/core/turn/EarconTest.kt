package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.Wav
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

class EarconTest {
    private val pcm = Earcon.pcm()

    @Test
    fun `it is short`() {
        val ms = pcm.size * 1000L / Earcon.SAMPLE_RATE
        assertTrue(ms in 250..400, "$ms ms")
    }

    @Test
    fun `it is clearly audible and never clips`() {
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue(peak in 10_000..20_000, "peak $peak")
    }

    @Test
    fun `it starts and ends silent, so it does not click`() {
        assertEquals(0, pcm.first().toInt())
        assertTrue(abs(pcm.last().toInt()) < 100)
    }

    @Test
    fun `its wav is the samples behind a canonical header`() {
        val wav = Earcon.wav()
        assertEquals(Wav.HEADER_BYTES + pcm.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        val back = ShortArray(pcm.size)
        Wav.littleEndianToPcm16(wav.copyOfRange(Wav.HEADER_BYTES, wav.size), pcm.size, back)
        assertTrue(pcm.contentEquals(back))
    }

    @Test
    fun `the earcon the phone ships is this one`() {
        // The TTS engine runs in its own process and reads the earcon as an app resource, so it is a
        // file in :audio. Regenerate it from Earcon.wav() when the sound changes; a sample may differ
        // by one where another language's sin() rounds the other way.
        val shipped = File(requireNotNull(System.getProperty("stsloop.earcon")) { "run through Gradle" }).readBytes()
        val wav = Earcon.wav()
        assertEquals(wav.size, shipped.size)
        assertTrue(wav.copyOfRange(0, Wav.HEADER_BYTES).contentEquals(shipped.copyOfRange(0, Wav.HEADER_BYTES)))
        val back = ShortArray(pcm.size)
        Wav.littleEndianToPcm16(shipped.copyOfRange(Wav.HEADER_BYTES, shipped.size), pcm.size, back)
        for (i in pcm.indices) assertTrue(abs(pcm[i] - back[i]) <= 1, "sample $i: ${pcm[i]} vs ${back[i]}")
    }
}
