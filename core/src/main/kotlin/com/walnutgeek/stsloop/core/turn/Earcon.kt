package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.Wav
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The earcon before an Inbound Item (`speak_item`): two short rising notes,
 * a fifth apart (A5 then E6), so an alert never sounds like the loop's own
 * replies, which are speech alone. The phone plays it from
 * `audio/src/main/res/raw/stsloop_earcon.wav` (the TTS engine, in its own
 * process, can read an app resource but not an app file), which is this
 * [wav]: `EarconTest` keeps the two in step.
 */
object Earcon {
    const val SAMPLE_RATE = 22_050
    private val NOTES = listOf(880.0 to 110, 1318.5 to 160) // Hz to ms
    private const val GAP_MS = 30
    private const val FADE_MS = 10
    private const val PEAK = 0.4 // of full scale: clearly heard, well below the voice that follows

    /** The earcon as 16-bit mono samples at [SAMPLE_RATE]. */
    fun pcm(): ShortArray {
        val out = ArrayList<Short>()
        for ((i, note) in NOTES.withIndex()) {
            if (i > 0) repeat(GAP_MS * SAMPLE_RATE / 1000) { out += 0 }
            val (hz, ms) = note
            val n = ms * SAMPLE_RATE / 1000
            val fade = FADE_MS * SAMPLE_RATE / 1000
            for (k in 0 until n) {
                // A short linear fade at each end, so neither note clicks.
                val envelope = min(1.0, min(k, n - 1 - k).toDouble() / fade)
                out += (PEAK * envelope * Short.MAX_VALUE * sin(2 * PI * hz * k / SAMPLE_RATE)).roundToInt().toShort()
            }
        }
        return out.toShortArray()
    }

    /** The earcon as a complete WAV file, for a TTS engine to play. */
    fun wav(): ByteArray {
        val samples = pcm()
        val data = ByteArray(samples.size * Wav.BYTES_PER_SAMPLE)
        Wav.pcm16ToLittleEndian(samples, samples.size, data)
        return Wav.header(SAMPLE_RATE, data.size) + data
    }
}
