package com.walnutgeek.stsloop.core.turn

/**
 * The retained tail of a Session's PCM16 stream, addressed by absolute sample
 * index. Samples are appended as captured and dropped from the front once no
 * Turn can need them, so memory is bounded by pre-roll while idle and by the
 * max utterance length while capturing.
 */
class SampleBuffer {
    private var data = ShortArray(4096)
    private var head = 0 // index in [data] of sample [start]

    /** Absolute index of the oldest retained sample. */
    var start: Long = 0
        private set

    /** Absolute index one past the newest sample. */
    val end: Long get() = start + size

    var size: Int = 0
        private set

    internal val capacity: Int get() = data.size

    fun append(samples: ShortArray, count: Int) {
        require(count in 0..samples.size) { "count $count out of 0..${samples.size}" }
        if (head + size + count > data.size) {
            if (size + count > data.size) {
                data = ShortArray(maxOf(data.size * 2, size + count)).also { data.copyInto(it, 0, head, head + size) }
            } else {
                data.copyInto(data, 0, head, head + size)
            }
            head = 0
        }
        samples.copyInto(data, head + size, 0, count)
        size += count
    }

    /** Forgets every sample before [sample]. Earlier indices are a no-op. */
    fun dropBefore(sample: Long) {
        require(sample <= end) { "cannot drop to $sample past the end $end" }
        if (sample <= start) return
        val n = (sample - start).toInt()
        head += n
        size -= n
        start = sample
    }

    /** Samples `[from, to)`, which must still be retained. */
    fun copy(from: Long, to: Long): ShortArray {
        checkRange(from, to)
        val i = head + (from - start).toInt()
        return data.copyOfRange(i, i + (to - from).toInt())
    }

    /**
     * Fills [out] with the samples from [from] scaled to [-1, 1) by `/32768`:
     * the float conversion sits downstream of the tee, so the int16 bytes the
     * Corpus keeps are exactly the ones the VAD judged.
     */
    fun toFloats(from: Long, out: FloatArray) {
        checkRange(from, from + out.size)
        val i = head + (from - start).toInt()
        for (k in out.indices) out[k] = data[i + k] / 32768f
    }

    private fun checkRange(from: Long, to: Long) {
        require(from in start..to && to <= end) { "range [$from, $to) outside retained [$start, $end)" }
    }
}
