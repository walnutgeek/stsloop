package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.Ids
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.testmode.TurnTest

/**
 * Writes each closed utterance as its own Turn, transcript included, in one
 * publish. Sample 0 of the stream is wall time [sessionStartedAtMs], so a
 * Turn's `started_at` is derived from its sample offset. A Turn that fails to
 * write is abandoned and reported to the [Listener]; it never takes the rest
 * of the Session down with it.
 *
 * Each Turn is classified by [grammar] from its transcript: a Note (with its
 * Bucket when Declared), or unclassified when nothing was heard.
 */
class CorpusSink(
    private val writer: CorpusWriter,
    private val sessionId: String,
    private val sessionStartedAtMs: Long,
    private val sampleRate: Int,
    private val appVersion: String,
    private val grammar: PhraseGrammar,
    private val newId: () -> String = Ids::next,
    /**
     * Test mode (#27): the `test` block for a closed utterance, or null outside
     * test mode. Called on the writing thread; a failure only drops the block.
     */
    private val testOf: ((Utterance) -> TurnTest?)? = null,
    private val listener: Listener,
) {
    interface Listener {
        /** [timing] is null when the Turn has no transcript. */
        fun published(turn: Turn, utterance: Utterance, timing: SttTiming?) {}
        fun failed(utterance: Utterance, error: Exception) {}
        fun discarded(event: TurnEvent.Discarded) {}
    }

    /** Turns published so far. */
    var published = 0
        private set

    /** Turns that failed to write and were abandoned. */
    var failed = 0
        private set

    /** Publishes [pcm] (exactly the [utterance]'s samples) with its [transcription], if the recognizer gave one. */
    fun closed(utterance: Utterance, pcm: ShortArray, transcription: Transcription? = null) {
        val turn = try {
            val inProgress = writer.begin(newId(), sessionId, utterance.startedAtMs(sessionStartedAtMs, sampleRate), sampleRate)
            try {
                inProgress.append(pcm, pcm.size)
                val transcript = transcription?.transcript
                val test = testOf?.let { f -> runCatching { f(utterance) }.getOrNull() }
                inProgress.finish(appVersion, utterance.vad(sampleRate), transcript, grammar.classify(transcript?.text), test)
            } catch (e: Exception) {
                runCatching { inProgress.abandon() }
                throw e
            }
        } catch (e: Exception) {
            failed++
            listener.failed(utterance, e)
            return
        }
        published++
        listener.published(turn, utterance, transcription?.timing)
    }

    fun discarded(event: TurnEvent.Discarded) = listener.discarded(event)
}
