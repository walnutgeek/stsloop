package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.Ids
import com.walnutgeek.stsloop.core.Turn

/**
 * Writes each closed utterance as its own Turn. Sample 0 of the stream is wall
 * time [sessionStartedAtMs], so a Turn's `started_at` is derived from its
 * sample offset. A Turn that fails to write is abandoned and reported to the
 * [Listener]; it never takes the rest of the Session down with it.
 */
class CorpusSink(
    private val writer: CorpusWriter,
    private val sessionId: String,
    private val sessionStartedAtMs: Long,
    private val sampleRate: Int,
    private val appVersion: String,
    private val newId: () -> String = Ids::next,
    private val listener: Listener,
) : UtteranceSink {
    interface Listener {
        fun published(turn: Turn, utterance: Utterance) {}
        fun failed(utterance: Utterance, error: Exception) {}
        fun discarded(event: TurnEvent.Discarded) {}
    }

    /** Turns published so far. */
    var published = 0
        private set

    /** Turns that failed to write and were abandoned. */
    var failed = 0
        private set

    override fun closed(utterance: Utterance, pcm: ShortArray) {
        val turn = try {
            val inProgress = writer.begin(newId(), sessionId, utterance.startedAtMs(sessionStartedAtMs, sampleRate), sampleRate)
            try {
                inProgress.append(pcm, pcm.size)
                inProgress.finish(appVersion, utterance.vad(sampleRate))
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
        listener.published(turn, utterance)
    }

    override fun discarded(event: TurnEvent.Discarded) = listener.discarded(event)
}
