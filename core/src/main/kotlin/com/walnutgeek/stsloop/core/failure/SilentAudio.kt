package com.walnutgeek.stsloop.core.failure

/**
 * What "effectively silent" audio means, for the Recording of a Turn and for
 * the live mic stream (`docs/mvp.md`, "Platform constraints on the loop").
 *
 * Audio is judged in [WINDOW_MS] windows by RMS, the measure the drive report
 * uses. A window is effectively silent below [SILENT_RMS] (about -90 dBFS):
 * exact zeros, which is what Android hands a silenced client, or at most one
 * LSB of dither. A working mic never gets there: the owner's drive Corpus
 * (#8, 270 Recordings on the phone mic and the car's Bluetooth SCO mic) has
 * noise floors of 3–90 RMS, and its quietest window anywhere is 2.1 RMS.
 */
object SilentAudio {
    const val WINDOW_MS = 100

    /** A window below this RMS is effectively silent. */
    const val SILENT_RMS = 1.5

    /**
     * How long the live stream must stay effectively silent before the mic
     * counts as failed. Long enough to ride out a Bluetooth SCO link coming up
     * at the start of a Session; short enough to be said before the driver
     * has finished a thought into a dead mic.
     */
    const val MIC_SILENT_AFTER_MS = 3_000

    fun windowSamples(sampleRate: Int): Int = maxOf(1, sampleRate * WINDOW_MS / 1000)
}

/**
 * Splits a stream of PCM16 chunks into [SilentAudio.WINDOW_MS] windows on the
 * stream's own sample clock (windows start at sample 0, whatever the chunk
 * sizes), reporting each window's mean square and the sample it ended at.
 */
internal class Windows(sampleRate: Int, private val window: (meanSquare: Double, endSample: Long) -> Unit) {
    private val size = SilentAudio.windowSamples(sampleRate)
    private var sum = 0.0
    private var filled = 0
    private var position = 0L

    fun add(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sum += s * s
            filled++
            position++
            if (filled == size) {
                window(sum / size, position)
                sum = 0.0
                filled = 0
            }
        }
    }

    /** The mean square of the partial window so far, or null when it is empty. */
    fun partial(): Double? = if (filled == 0) null else sum / filled
}

/**
 * The level of one Recording as it is written: [silent] when no window of it
 * (a partial last window included) reaches [SilentAudio.SILENT_RMS]. An
 * all-zero Recording is silent; any Recording with speech, or with a working
 * mic's noise floor, is not.
 */
class RecordingLevel(sampleRate: Int) {
    private var loudest = 0.0 // mean square
    private val windows = Windows(sampleRate) { ms, _ -> if (ms > loudest) loudest = ms }

    fun add(samples: ShortArray, count: Int) = windows.add(samples, count)

    /** RMS of the loudest window so far. */
    val loudestRms: Double get() = kotlin.math.sqrt(maxOf(loudest, windows.partial() ?: 0.0))

    val silent: Boolean get() = loudestRms < SilentAudio.SILENT_RMS
}

/**
 * Watches the live mic stream for sustained digital silence: [onChange]
 * hears `true` once [silentAfterMs] of effectively silent windows have run
 * back to back, and `false` at the first window after that which is not
 * silent, each with the stream sample the window ended at. Capture thread only.
 */
class DeadMic(
    sampleRate: Int,
    silentAfterMs: Int = SilentAudio.MIC_SILENT_AFTER_MS,
    private val onChange: (silent: Boolean, atSample: Long) -> Unit,
) {
    private val limit = silentAfterMs.toLong() * sampleRate / 1000
    private val windowSize = SilentAudio.windowSamples(sampleRate)
    private val threshold = SilentAudio.SILENT_RMS * SilentAudio.SILENT_RMS
    private var run = 0L

    var silent = false
        private set

    private val windows = Windows(sampleRate) { ms, end ->
        if (ms < threshold) {
            run += windowSize
            if (!silent && run >= limit) {
                silent = true
                onChange(true, end)
            }
        } else {
            run = 0
            if (silent) {
                silent = false
                onChange(false, end)
            }
        }
    }

    fun accept(samples: ShortArray, count: Int) = windows.add(samples, count)
}
