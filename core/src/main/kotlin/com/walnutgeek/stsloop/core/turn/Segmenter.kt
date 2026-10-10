package com.walnutgeek.stsloop.core.turn

/** A VAD: the probability that one window of `[-1, 1)` samples is speech. */
fun interface SpeechProbability {
    fun of(window: FloatArray): Float
}

/**
 * Receives the Segmenter's output, on the thread that calls [Segmenter.accept].
 * Each utterance is [opened], then [captured] in order, then either [closed]
 * or [discarded].
 */
interface UtteranceSink {
    /** An utterance starts at [startSample] (pre-roll included). */
    fun opened(startSample: Long) {}

    /**
     * The open utterance's next samples, as `/32768` floats, as soon as the
     * VAD has judged them. Contiguous from its start; all of them together
     * are exactly its Recording. [samples] is the sink's to keep.
     */
    fun captured(samples: FloatArray) {}

    /** [pcm] is exactly samples `[startSample, endSample)` of the stream. */
    fun closed(utterance: Utterance, pcm: ShortArray)

    fun discarded(event: TurnEvent.Discarded) {}
}

/**
 * The software tee (`docs/mvp.md`, "One microphone, software tee"): one int16
 * stream goes in; the same retained samples are cut into fixed VAD windows
 * (converted to float `/32768` here, downstream of the tee) and into each
 * closed utterance's Recording, which is also streamed to the sink while it is
 * captured (for the recognizer). The [TurnStateMachine] decides the cuts, so a
 * Turn's audio is the exact sample range it reports.
 */
class Segmenter(
    timings: Timings,
    sampleRate: Int,
    private val windowSamples: Int,
    private val vad: SpeechProbability,
    private val sink: UtteranceSink,
) {
    init {
        require(windowSamples > 0) { "windowSamples must be > 0, was $windowSamples" }
    }

    private val machine = TurnStateMachine(timings, sampleRate)
    private val buffer = SampleBuffer()
    private val window = FloatArray(windowSamples)

    /** Samples of the open utterance streamed so far end here; -1 while none is open. */
    private var streamedTo = -1L

    val state: TurnState get() = machine.state

    /** Samples currently held in memory. */
    val retainedSamples: Int get() = buffer.size

    /** Tees the first [count] samples of [samples] in; [samples] is not modified or kept. */
    fun accept(samples: ShortArray, count: Int) {
        buffer.append(samples, count)
        while (buffer.end - machine.position >= windowSamples) {
            buffer.toFloats(machine.position, window)
            emit(machine.window(windowSamples, vad.of(window)))
        }
        // Only judged samples: anything past the position may belong to the next utterance.
        stream(to = machine.position)
        buffer.dropBefore(machine.retainFrom)
    }

    /**
     * The Session is ending: an utterance still being captured closes at the
     * end of the stream, including the last partial window the VAD never judged.
     * Calling it again does nothing.
     */
    fun finish() {
        emit(machine.end(at = buffer.end))
        buffer.dropBefore(machine.retainFrom)
    }

    private fun emit(event: TurnEvent?) {
        when (event) {
            is TurnEvent.Closed -> {
                val u = event.utterance
                stream(to = u.endSample)
                streamedTo = -1
                sink.closed(u, buffer.copy(u.startSample, u.endSample))
            }
            is TurnEvent.Discarded -> {
                streamedTo = -1
                sink.discarded(event)
            }
            null -> {}
        }
        // An onset, or the continuation after a max cut.
        if (streamedTo < 0 && machine.state == TurnState.CAPTURING) {
            streamedTo = machine.retainFrom
            sink.opened(streamedTo)
        }
    }

    /** Streams the open utterance's samples up to [to]. */
    private fun stream(to: Long) {
        if (streamedTo < 0 || to <= streamedTo) return
        val out = FloatArray((to - streamedTo).toInt())
        buffer.toFloats(streamedTo, out)
        streamedTo = to
        sink.captured(out)
    }
}
