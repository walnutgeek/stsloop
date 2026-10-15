package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.BucketRequest
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.Ids
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.hub.Drafts
import com.walnutgeek.stsloop.core.summary.BucketSummaries
import com.walnutgeek.stsloop.core.summary.NoteSource
import com.walnutgeek.stsloop.core.summary.SummaryMarks
import com.walnutgeek.stsloop.core.testmode.TurnTest

/**
 * Writes each closed utterance as its own Turn, transcript included, in one
 * publish. Sample 0 of the stream is wall time [sessionStartedAtMs], so a
 * Turn's `started_at` is derived from its sample offset. A Turn that fails to
 * write is abandoned and reported to the [Listener]; it never takes the rest
 * of the Session down with it.
 *
 * Each Turn is classified by [grammar] from its transcript: a whole-utterance
 * command, a Note in its Bucket when Declared, a Note in the Bucket with
 * [focus] (`bucket_source: "focus"`), or unclassified. A command is carried
 * out by this Session's [SessionCommands], and its Turn records what it did
 * (for "scratch that", the Turn it tombstones; for a lone Bucket alias, the
 * Bucket it focuses, which takes Focus once the Turn is published); "stop
 * listening" asks the [Listener] to end the Session. A command a Bucket
 * declares ("status") is read against the Bucket that had Focus when its
 * utterance ended, and once its Turn is published goes to [request], which
 * sends it to the hub (the reply is spoken later) or says why it cannot.
 * Every Turn published restarts the [focus] timeout, at the end of its utterance.
 *
 * "Summarize bucket" (#76) records the Notes it covers in its Turn, and once
 * that is published goes to [summaries], which sends them to the hub (the
 * summary is spoken later) or reads them out itself.
 *
 * Under an agent Bucket's Focus a Turn that is no command goes to its Draft
 * in [drafts] (#45). "Send" is said as a readback ([Listener.readBack]), and
 * the Draft goes to the hub only once that readback is over.
 */
class CorpusSink(
    private val writer: CorpusWriter,
    private val sessionId: String,
    private val sessionStartedAtMs: Long,
    private val sampleRate: Int,
    private val appVersion: String,
    private val grammar: PhraseGrammar,
    /** This Session's Focus, on the stream's clock; shared with whatever [Focus.tick]s it. */
    private val focus: Focus = Focus({ grammar.buckets }, Timings().focusTimeoutMs.toLong()),
    private val newId: () -> String = Ids::next,
    /**
     * Test mode (#27): the `test` block for a closed utterance, or null outside
     * test mode. Called on the writing thread; a failure only drops the block.
     */
    private val testOf: ((Utterance) -> TurnTest?)? = null,
    /** Whether the hub is online now, for the replies that name a hub Bucket; must return at once. */
    hubOnline: () -> Boolean = { false },
    /**
     * Sends a Bucket's `request` command to the hub; returns what to say now,
     * or null when it was sent and its reply will be said. Must return at once.
     */
    private val request: (BucketRequest) -> String? = { SessionCommands.HUB_OFFLINE },
    /** This Session's Drafts; shared with whatever hears the hub's answers to their sends. */
    private val drafts: Drafts = Drafts(send = { false }, say = {}, clockMs = { 0L }),
    /** This Session's summaries ("summarize bucket"); by default over no Corpus and no hub. */
    private val summaries: BucketSummaries = BucketSummaries(NoteSource.NONE, SummaryMarks(null), { false }, { false }, { _, _ -> }, { 0L }),
    private val listener: Listener,
) {
    interface Listener {
        /**
         * [turn] is in the Corpus. [timing] is null when it has no transcript.
         * [say] is what the loop says back: a command's reply, otherwise the
         * transcript to echo (null when there is none).
         */
        fun published(turn: Turn, utterance: Utterance, timing: SttTiming?, say: String?) {}
        fun failed(utterance: Utterance, error: Exception) {}

        /**
         * [turn], a "stop listening" command, is in the Corpus (and went to
         * [published] just before, with nothing to say): say [say], then end
         * the Session. Not called when the command Turn failed to write.
         */
        fun endSession(turn: Turn, say: String) {}

        /**
         * [turn], a "send", is in the Corpus (and went to [published] just
         * before, with nothing to say): say [say], the Draft read back, then
         * call [send]; or, if the readback is never heard (the Session ends
         * first, or nothing is spoken in it), [withdraw], which keeps the
         * Draft. A Draft is never sent without its readback, so by default it
         * is withdrawn.
         */
        fun readBack(turn: Turn, say: String, send: () -> Unit, withdraw: () -> Unit) = withdraw()
        fun discarded(event: TurnEvent.Discarded) {}
    }

    private val commands = SessionCommands(focus, hubOnline, drafts, summaries)

    /** Turns published so far. */
    var published = 0
        private set

    /** Turns that failed to write and were abandoned. */
    var failed = 0
        private set

    /** Publishes [pcm] (exactly the [utterance]'s samples) with its [transcription], if the recognizer gave one. */
    fun closed(utterance: Utterance, pcm: ShortArray, transcription: Transcription? = null) {
        val transcript = transcription?.transcript
        val endMs = utterance.endSample * 1000 / sampleRate
        val classification = grammar.classify(transcript?.text, focus.filing(endMs), focused = focus.at(endMs).name)
        val command = classification.command?.let(commands::plan)
        val turn = try {
            val inProgress = writer.begin(newId(), sessionId, utterance.startedAtMs(sessionStartedAtMs, sampleRate), sampleRate)
            try {
                inProgress.append(pcm, pcm.size)
                val test = testOf?.let { f -> runCatching { f(utterance) }.getOrNull() }
                inProgress.finish(appVersion, utterance.vad(sampleRate), transcript, command?.classification ?: classification, test)
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
        commands.published(turn)
        val focused = turn.classification?.command?.focus
        if (focused != null) focus.set(focused, endMs) else focus.heard(endMs)
        val key = command?.sendKey
        if (command?.endsSession == true) {
            listener.published(turn, utterance, transcription?.timing, null)
            listener.endSession(turn, command.say.orEmpty())
        } else if (key != null) {
            listener.published(turn, utterance, transcription?.timing, null)
            listener.readBack(turn, command.say.orEmpty(), send = { drafts.dispatch(key) }, withdraw = { drafts.withdraw(key) })
        } else {
            val say = when {
                command == null -> transcript?.text
                command.request != null -> request(command.request)
                command.summarize -> summaries.summarize(command.range)
                else -> command.say
            }
            listener.published(turn, utterance, transcription?.timing, say)
        }
    }

    fun discarded(event: TurnEvent.Discarded) = listener.discarded(event)
}
