package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.TurnVad

enum class TurnState { IDLE, CAPTURING }

enum class CloseReason { SILENCE, MAX_DURATION, SESSION_END }

/**
 * A human Turn's span of the Session's sample stream: `[startSample, endSample)`.
 * It covers pre-roll, speech, and trailing Silence; [speechSamples] runs from
 * the first to the last speech window (pauses inside count), and
 * [trailingSilenceSamples] from the last speech window to [endSample].
 */
data class Utterance(
    val startSample: Long,
    val endSample: Long,
    val speechSamples: Long,
    val trailingSilenceSamples: Long,
    val closedBy: CloseReason,
) {
    val lengthSamples: Long get() = endSample - startSample

    /** Wall time of [startSample], given that sample 0 was captured at [sessionStartedAtMs]. */
    fun startedAtMs(sessionStartedAtMs: Long, sampleRate: Int): Long = sessionStartedAtMs + startSample * 1000 / sampleRate

    /** The `vad` block of `turn.json`. */
    fun vad(sampleRate: Int): TurnVad =
        TurnVad(speechMs = speechSamples * 1000 / sampleRate, trailingSilenceMs = trailingSilenceSamples * 1000 / sampleRate)
}

sealed interface TurnEvent {
    /** An utterance long enough to become a Turn. */
    data class Closed(val utterance: Utterance) : TurnEvent

    /** Too little speech (a cough, a door slam): no Turn is written. */
    data class Discarded(val startSample: Long, val endSample: Long, val speechSamples: Long) : TurnEvent
}

/**
 * The human side of the loop: IDLE → CAPTURING → closed by trailing Silence
 * or by max duration (`docs/mvp.md`, "The loop").
 *
 * It post-processes per-window speech probabilities rather than delegating to
 * sherpa-onnx's segmenter, so every timing is a [Timings] value and every
 * boundary is an exact sample offset. Its only clock is the sample position:
 * each [window] call advances it, which is what makes it testable with a fake
 * VAD and no wall time.
 *
 * - IDLE: a window at or above `speech_threshold` is an onset. The Recording
 *   starts `pre_roll_ms` earlier, but never before the end of the previous Turn.
 * - CAPTURING: a window at or above `release_threshold` is speech. The
 *   utterance closes `trailing_silence_ms` after the last speech window, or at
 *   `max_utterance_ms` after its start, whichever comes first. Either cut may
 *   fall inside a window; the remainder of that window belongs to IDLE.
 * - An utterance with less than `min_utterance_ms` of speech is discarded.
 */
class TurnStateMachine(private val timings: Timings, private val sampleRate: Int) {
    init {
        require(sampleRate > 0) { "sampleRate must be > 0, was $sampleRate" }
    }

    private val trailing = samples(timings.trailingSilenceMs)
    private val max = samples(timings.maxUtteranceMs)
    private val min = samples(timings.minUtteranceMs)
    private val preRoll = samples(timings.preRollMs)

    var state: TurnState = TurnState.IDLE
        private set

    /** Samples judged so far; the end of the last window. */
    var position: Long = 0
        private set

    private var lastTurnEnd = 0L
    private var start = 0L
    private var onset = 0L
    private var lastSpeechEnd = 0L

    /** The earliest sample a Turn may still need; audio before it can be dropped. */
    val retainFrom: Long
        get() = if (state == TurnState.CAPTURING) start else maxOf(position - preRoll, lastTurnEnd, 0)

    /** Judges the next [samples]-long window, whose speech probability is [probability]. */
    fun window(samples: Int, probability: Float): TurnEvent? {
        require(samples > 0) { "a window must have samples, was $samples" }
        val from = position
        val to = from + samples
        position = to
        when (state) {
            TurnState.IDLE -> {
                if (!(probability >= timings.speechThreshold)) return null
                state = TurnState.CAPTURING
                onset = from
                start = maxOf(from - preRoll, lastTurnEnd, 0)
                lastSpeechEnd = to
            }
            TurnState.CAPTURING -> if (probability >= timings.releaseThreshold) lastSpeechEnd = to
        }
        val silenceEnd = lastSpeechEnd + trailing
        val maxEnd = start + max
        return when {
            to >= silenceEnd && silenceEnd <= maxEnd -> close(silenceEnd, CloseReason.SILENCE)
            to >= maxEnd -> close(maxEnd, CloseReason.MAX_DURATION)
            else -> null
        }
    }

    /** The Session is ending: close whatever is being captured at [position]. */
    fun end(): TurnEvent? =
        if (state == TurnState.CAPTURING) close(position, CloseReason.SESSION_END) else null

    private fun close(end: Long, reason: CloseReason): TurnEvent {
        state = TurnState.IDLE
        val speechEnd = minOf(lastSpeechEnd, end)
        val speech = speechEnd - onset
        if (speech < min) return TurnEvent.Discarded(start, end, speech)
        lastTurnEnd = end
        return TurnEvent.Closed(Utterance(start, end, speech, end - speechEnd, reason))
    }

    private fun samples(ms: Int): Long = ms.toLong() * sampleRate / 1000
}
