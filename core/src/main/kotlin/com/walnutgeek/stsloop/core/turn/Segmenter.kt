package com.walnutgeek.stsloop.core.turn

/** A VAD: the probability that one window of `[-1, 1)` samples is speech. */
fun interface SpeechProbability {
    fun of(window: FloatArray): Float
}

/** Receives the Segmenter's output, on the thread that calls [Segmenter.accept]. */
interface UtteranceSink {
    /** [pcm] is exactly samples `[startSample, endSample)` of the stream. */
    fun closed(utterance: Utterance, pcm: ShortArray)

    fun discarded(event: TurnEvent.Discarded) {}
}

/**
 * The software tee (`docs/mvp.md`, "One microphone, software tee"): one int16
 * stream goes in; the same retained samples are cut into fixed VAD windows
 * (converted to float `/32768` here, downstream of the tee) and into each
 * closed utterance's Recording. The [TurnStateMachine] decides the cuts, so a
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
                sink.closed(u, buffer.copy(u.startSample, u.endSample))
            }
            is TurnEvent.Discarded -> sink.discarded(event)
            null -> {}
        }
    }
}
