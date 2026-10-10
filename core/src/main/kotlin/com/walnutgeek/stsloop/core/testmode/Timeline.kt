package com.walnutgeek.stsloop.core.testmode

/**
 * Maps a monotonic time (`elapsedRealtimeNanos`) to an offset in the
 * Session's sample stream, so events seen on other threads (TTS callbacks,
 * route changes) land on the same clock as a Turn's audio span.
 *
 * Anchored on the latest `AudioRecord.read`: [update] is called with the
 * number of samples captured so far and when that read returned, and a time
 * is placed from there by the sample rate. Re-anchoring on every read keeps
 * drift from accumulating over a long Session. Like `transcript.latency_ms`,
 * it does not correct for the HAL's internal capture latency. Thread-safe.
 */
class SampleClock(private val sampleRate: Int) {
    private class Anchor(val samples: Long, val atNs: Long)

    @Volatile
    private var anchor: Anchor? = null

    fun update(samplesCaptured: Long, atNs: Long) {
        anchor = Anchor(samplesCaptured, atNs)
    }

    /** The sample offset captured at [atNs], or null before the first read. Never negative. */
    fun sampleAt(atNs: Long): Long? {
        val a = anchor ?: return null
        return maxOf(0L, a.samples + Math.floorDiv((atNs - a.atNs) * sampleRate, 1_000_000_000L))
    }
}

/** How much TTS playback a span of the stream overlapped: [samples] in total, and which [phrases]. */
data class TtsOverlap(val samples: Long, val phrases: List<String>)

/**
 * The TTS playback intervals of a Session, on the sample clock: from the
 * engine's start callback to its done (or error/stop) callback. Thread-safe.
 */
class TtsTimeline {
    private class Interval(val id: String, val start: Long, var end: Long?)

    private val intervals = mutableListOf<Interval>()

    @Synchronized
    fun started(id: String, sample: Long) {
        intervals += Interval(id, sample, null)
    }

    /** Closes [id]'s interval; a second call, or one for an unknown id, is ignored. */
    @Synchronized
    fun done(id: String, sample: Long) {
        val i = intervals.lastOrNull { it.id == id } ?: return
        if (i.end == null) i.end = maxOf(sample, i.start)
    }

    /**
     * The overlap of `[start, end)` with every interval. A phrase still
     * playing counts as playing until [now] (the current sample offset).
     */
    @Synchronized
    fun overlap(start: Long, end: Long, now: Long): TtsOverlap {
        var total = 0L
        val ids = mutableListOf<String>()
        for (i in intervals) {
            val shared = minOf(end, i.end ?: now) - maxOf(start, i.start)
            if (shared > 0) {
                total += shared
                ids += i.id
            }
        }
        return TtsOverlap(total, ids)
    }

    /** Drops intervals that ended before [sample]; no Turn still to be written can reach them. */
    @Synchronized
    fun forgetBefore(sample: Long) {
        intervals.removeAll { it.end != null && it.end!! <= sample }
    }
}

/** A value that changes at points on the sample clock, e.g. the routed input device. Thread-safe. */
class StepTimeline<T> {
    private val steps = mutableListOf<Pair<Long, T>>()

    val latest: T? @Synchronized get() = steps.lastOrNull()?.second

    /** From [sample] on, the value is [value]. Samples are expected in order; a repeat of the latest value is no change. */
    @Synchronized
    fun set(sample: Long, value: T) {
        if (steps.lastOrNull()?.second == value) return
        steps += maxOf(0L, sample) to value
    }

    /** Each value in effect at some point of `[start, end)`, in the order first seen. */
    @Synchronized
    fun during(start: Long, end: Long): List<T> {
        val out = mutableListOf<T>()
        for ((i, step) in steps.withIndex()) {
            val from = step.first
            val until = steps.getOrNull(i + 1)?.first ?: Long.MAX_VALUE
            if (from < end && until > start && step.second !in out) out += step.second
        }
        return out
    }
}
