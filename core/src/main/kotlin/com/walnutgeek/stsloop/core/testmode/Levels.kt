package com.walnutgeek.stsloop.core.testmode

import kotlin.math.log10
import kotlin.math.sqrt

/** Signal level of PCM16 audio, the same measure the drive report uses (100 ms RMS). */
object Levels {
    /** Reported for digital silence instead of minus infinity. */
    const val FLOOR_DBFS = -120.0

    fun rms(samples: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / count)
    }

    fun dbfs(rms: Double): Double = if (rms <= 0.0) FLOOR_DBFS else 20 * log10(rms / 32768.0)
}

/**
 * The RMS of the most recent [capacity] capture chunks, by their place on the
 * sample clock, so the level the mic heard while a phrase played can be
 * compared with the level just before it. Thread-safe.
 */
class LevelHistory(private val capacity: Int) {
    private class Chunk(val start: Long, val end: Long, val rms: Double)

    private val chunks = ArrayDeque<Chunk>()

    /** A chunk of [samples] samples ending at [endSample] (exclusive). */
    @Synchronized
    fun add(endSample: Long, samples: Int, rms: Double) {
        chunks.addLast(Chunk(endSample - samples, endSample, rms))
        while (chunks.size > capacity) chunks.removeFirst()
    }

    /** Power-mean RMS of the chunks touching `[start, end)`, or null when none is kept. */
    @Synchronized
    fun rms(start: Long, end: Long): Double? {
        var power = 0.0
        var n = 0
        for (c in chunks) {
            if (c.start < end && c.end > start) {
                power += c.rms * c.rms
                n++
            }
        }
        return if (n == 0) null else sqrt(power / n)
    }
}
