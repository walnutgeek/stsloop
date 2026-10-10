package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.TurnVad

enum class TurnState { LISTENING, CAPTURING }

enum class CloseReason { SILENCE, MAX_DURATION, SESSION_END }

/**
 * A human Turn's span of the Session's sample stream: `[startSample, endSample)`.
 * It covers pre-roll, speech, and trailing Silence; [speechSamples] runs from
 * the first to the last speech window (gaps shorter than the trailing Silence
 * count), and [trailingSilenceSamples] from the last speech window to [endSample].
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
    /** An utterance long enough to become a Turn, or the continuation of one cut at max. */
    data class Closed(val utterance: Utterance) : TurnEvent

    /** Too little speech (a cough, a door slam): no Turn is written. */
    data class Discarded(val startSample: Long, val endSample: Long, val speechSamples: Long) : TurnEvent
}

/**
 * The human side of the loop: LISTENING → CAPTURING → closed by trailing
 * Silence, by max duration, or by the end of the Session (`docs/mvp.md`, "The loop").
 *
 * It post-processes per-window speech probabilities rather than delegating to
 * sherpa-onnx's segmenter, so every timing is a [Timings] value and every
 * boundary is an exact sample offset. Its only clock is the sample position:
 * each [window] call advances it, which is what makes it testable with a fake
 * VAD and no wall time.
 *
 * - LISTENING: a window at or above `speech_threshold` is an onset. The
 *   Recording starts `pre_roll_ms` earlier, but never before the end of the
 *   previous Turn.
 * - CAPTURING: a window at or above `release_threshold` is speech. The
 *   utterance closes `trailing_silence_ms` after the last speech window, or at
 *   `max_utterance_ms` after its start, whichever comes first. Either cut may
 *   fall inside a window.
 * - A max cut while speech is still running is not an ending: the next Turn
 *   starts exactly at the cut and stays CAPTURING, so no sample is lost, and
 *   that continuation is kept however little speech it holds.
 * - Otherwise an utterance with less than `min_utterance_ms` of speech is discarded.
 */
class TurnStateMachine(private val timings: Timings, private val sampleRate: Int) {
    init {
        require(sampleRate > 0) { "sampleRate must be > 0, was $sampleRate" }
    }

    private val trailingSamples = samples(timings.trailingSilenceMs)
    private val maxSamples = samples(timings.maxUtteranceMs)
    private val minSpeechSamples = samples(timings.minUtteranceMs)
    private val preRollSamples = samples(timings.preRollMs)

    var state: TurnState = TurnState.LISTENING
        private set

    /** Samples judged so far; the end of the last window. */
    var position: Long = 0
        private set

    private var lastTurnEnd = 0L
    private var start = 0L
    private var onset = 0L
    private var lastSpeechEnd = 0L
    private var continuation = false

    /** Where a Recording would start if the window at [position] were an onset. */
    private val earliestStart: Long get() = maxOf(position - preRollSamples, lastTurnEnd, 0)

    /** The earliest sample a Turn may still need; audio before it can be dropped. */
    val retainFrom: Long get() = if (state == TurnState.CAPTURING) start else earliestStart

    /** Judges the next [samples]-long window, whose speech probability is [probability]. */
    fun window(samples: Int, probability: Float): TurnEvent? {
        require(samples in 1 until maxSamples) { "a window must have 1 until $maxSamples samples, was $samples" }
        val from = position
        val to = from + samples
        when (state) {
            TurnState.LISTENING -> {
                if (!(probability >= timings.speechThreshold)) {
                    position = to
                    return null
                }
                start = earliestStart
                state = TurnState.CAPTURING
                onset = from
                continuation = false
                lastSpeechEnd = to
            }
            TurnState.CAPTURING -> if (probability >= timings.releaseThreshold) lastSpeechEnd = to
        }
        position = to
        val silenceEnd = lastSpeechEnd + trailingSamples
        val maxEnd = start + maxSamples
        return when {
            to >= silenceEnd && silenceEnd <= maxEnd -> close(silenceEnd, CloseReason.SILENCE)
            to >= maxEnd -> {
                val speaking = lastSpeechEnd >= maxEnd
                val event = close(maxEnd, CloseReason.MAX_DURATION)
                if (speaking) continueFrom(maxEnd)
                event
            }
            else -> null
        }
    }

    /**
     * The Session is ending: close whatever is being captured at [at], the end
     * of the stream. Samples after [position] were never judged by the VAD and
     * count as trailing silence.
     */
    fun end(at: Long = position): TurnEvent? {
        require(at >= position) { "cannot end at $at before the judged position $position" }
        if (state != TurnState.CAPTURING) return null
        position = at
        return close(at, CloseReason.SESSION_END)
    }

    private fun continueFrom(cut: Long) {
        state = TurnState.CAPTURING
        start = cut
        onset = cut
        continuation = true
    }

    private fun close(end: Long, reason: CloseReason): TurnEvent {
        state = TurnState.LISTENING
        val speechEnd = minOf(lastSpeechEnd, end)
        val speech = speechEnd - onset
        if (speech < minSpeechSamples && !continuation) return TurnEvent.Discarded(start, end, speech)
        lastTurnEnd = end
        return TurnEvent.Closed(Utterance(start, end, speech, end - speechEnd, reason))
    }

    private fun samples(ms: Int): Long = ms.toLong() * sampleRate / 1000
}
