package com.walnutgeek.stsloop.core.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * "Effectively silent" against what real mics deliver. The owner's drive
 * Corpus (#8, 270 Recordings) has noise floors of 3–90 RMS; its quietest
 * 100 ms window anywhere is 2.1 RMS (the car's Bluetooth SCO mic, parked, AC
 * off). A silenced client gets exact zeros.
 */
class SilentAudioTest {
    private val rate = 16_000

    /** Gaussian noise at about [rms], the shape of a quiet mic's floor. */
    private fun noise(ms: Int, rms: Double, seed: Int = 1): ShortArray {
        val r = Random(seed)
        return ShortArray(rate * ms / 1000) {
            // Box-Muller
            val g = kotlin.math.sqrt(-2 * kotlin.math.ln(1 - r.nextDouble())) * kotlin.math.cos(2 * Math.PI * r.nextDouble())
            kotlin.math.round(g * rms).toInt().toShort()
        }
    }

    private fun zeros(ms: Int) = ShortArray(rate * ms / 1000)

    private fun meter(vararg parts: ShortArray, chunk: Int = 1600): RecordingLevel {
        val m = RecordingLevel(rate)
        for (p in parts) {
            var at = 0
            while (at < p.size) {
                val n = minOf(chunk, p.size - at)
                m.add(p.copyOfRange(at, at + n), n)
                at += n
            }
        }
        return m
    }

    @Test
    fun `an all-zero Recording is silent`() = assertTrue(meter(zeros(3000)).silent)

    @Test
    fun `one-LSB dither is still silent`() {
        val dither = ShortArray(rate * 2) { i -> if (i % 2 == 0) 1 else -1 }
        assertTrue(meter(dither).silent)
    }

    @Test
    fun `the quietest real mic floor is not silent`() {
        // 2.1 RMS: the quietest 100 ms window in the drive Corpus.
        assertFalse(meter(noise(3000, 2.1)).silent)
    }

    @Test
    fun `a quiet but working Bluetooth mic is not silent`() {
        for (rms in listOf(3.0, 7.0, 29.0, 90.0)) assertFalse(meter(noise(2000, rms)).silent, "noise floor $rms RMS")
    }

    @Test
    fun `speech then zeros is not silent`() {
        assertFalse(meter(noise(500, 2000.0), zeros(1500)).silent)
    }

    @Test
    fun `one loud window is enough, wherever the chunks fall`() {
        val pcm = zeros(1000)
        for (i in 8000 until 8100) pcm[i] = 3000 // a 100-sample click straddling no window edge
        for (chunk in listOf(1, 7, 160, 1600, 4000)) assertFalse(meter(pcm, chunk = chunk).silent, "chunk $chunk")
    }

    @Test
    fun `a short loud tail in a partial last window counts`() {
        val pcm = zeros(1050)
        for (i in pcm.size - 400 until pcm.size) pcm[i] = 500
        assertFalse(meter(pcm).silent)
    }

    @Test
    fun `an empty Recording is silent`() = assertTrue(RecordingLevel(rate).silent)

    @Test
    fun `the loudest window is reported in RMS`() {
        val m = meter(zeros(500), ShortArray(1600) { i -> if (i % 2 == 0) 100 else -100 }, zeros(500))
        assertEquals(100.0, m.loudestRms, 1e-9)
    }

    @Test
    fun `the threshold sits below every real window`() {
        assertTrue(SilentAudio.SILENT_RMS < 2.1)
        assertTrue(SilentAudio.SILENT_RMS >= 1.0) // above +/-1 LSB dither
    }
}
