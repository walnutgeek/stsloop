package com.walnutgeek.stsloop.core.turn

import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** Where the loop is (`docs/mvp.md`, "The loop"). The mic is open only in [LISTENING] and [CAPTURING]. */
enum class LoopState { LISTENING, CAPTURING, SPEAKING, GUARD }

/**
 * Plays machine Turns. [speak] is called on the capture thread and must not
 * block; however the machine Turn ends (done, error, stopped, never started),
 * the speaker reports it once through [HalfDuplex.spoken].
 */
fun interface Speaker {
    fun speak(id: Long, text: String)

    /**
     * Machine Turn [id] reported no end in time and the mic is about to
     * reopen: it must never play from now on (stop it, or drop it if it has
     * not started). No end report is needed for it any more.
     */
    fun abandon(id: Long) {}
}

/** What the loop says back for a transcript (`docs/mvp.md`, "Spoken echo of the transcript"). */
object EchoText {
    /**
     * The transcript in a form a TTS engine reads as words, or null when there
     * is nothing to say. The current engine emits upper case, which TTS engines
     * may spell out letter by letter, so an all-caps transcript is lower-cased;
     * a cased engine's text is kept as it is.
     */
    fun of(transcript: String?): String? {
        val text = transcript?.trim() ?: return null
        if (text.none { it.isLetterOrDigit() }) return null
        return if (text.any { it.isLowerCase() }) text else text.lowercase(Locale.ROOT)
    }
}

/**
 * The Session's floor, strictly Half-duplex (`CONTEXT.md`): human Turns go
 * through the [segmenter]; each transcript handed to [echo] becomes a machine
 * Turn spoken by [speaker]; and the mic is closed from the moment SPEAKING
 * begins until `guard_ms` after it ends. While it is closed, no sample reaches
 * the [segmenter] (so neither the VAD, the recognizer, nor a Turn ever sees
 * the machine's own voice); the stream's sample offsets still count them.
 *
 * Silence is the only scheduler: a machine Turn starts only while LISTENING,
 * never while a human Turn is CAPTURING. A transcript that arrives while the
 * next human Turn is already being captured (including the continuation of a
 * Turn cut at `max_utterance_ms`) waits for that Turn's Silence. Transcripts
 * queued that way are spoken in order, back to back, in one closed span, with
 * one guard interval after the last.
 *
 * Its clock is the capture clock: [accept] stamps each chunk with when its
 * last sample was captured, and [spoken] reports the end on the same clock,
 * so the guard ends at an exact sample. If the speaker never reports an end,
 * it is told to [Speaker.abandon] that machine Turn, the [Listener] hears it
 * was lost, and the mic reopens a guard interval later.
 *
 * [accept], [finish] and [state] belong to the capture thread; [echo] and
 * [spoken] may be called from any thread. With no [speaker] (echo off) it is
 * a pass-through and the mic never closes.
 */
class HalfDuplex(
    private val segmenter: Segmenter,
    timings: Timings,
    private val sampleRate: Int,
    private val speaker: Speaker?,
    private val listener: Listener = object : Listener {},
) {
    interface Listener {
        /** Machine Turn [id] starts: the mic closes at stream sample [atSample]. */
        fun speaking(id: Long, text: String, atSample: Long) {}

        /** The guard interval is over: the mic is open again from [atSample], after [closedSamples] closed. */
        fun reopened(atSample: Long, closedSamples: Long) {}

        /** Machine Turn [id] reported no end within its limit; the guard runs from now. */
        fun lost(id: Long) {}

        /** [count] transcripts will never be spoken: the Session ended first. Any thread. */
        fun unspoken(count: Int) {}
    }

    private enum class Phase { OPEN, SPEAKING, GUARD }

    private class Ended(val id: Long, val atNs: Long)

    private val guardNs = timings.guardMs * NS_PER_MS
    private val pending = ConcurrentLinkedQueue<String>()
    private val lock = Any()
    private var finished = false // guarded by lock
    private val ended = ConcurrentLinkedQueue<Ended>()

    // Capture thread only.
    private var phase = Phase.OPEN
    private var lastId = 0L
    private var speakingSinceNs = 0L
    private var speakingLimitNs = 0L
    private var guardUntilNs = 0L
    private var closedAt = 0L
    private var position = 0L

    /** Capture thread only. */
    val state: LoopState
        get() = when (phase) {
            Phase.SPEAKING -> LoopState.SPEAKING
            Phase.GUARD -> LoopState.GUARD
            Phase.OPEN -> if (segmenter.state == TurnState.CAPTURING) LoopState.CAPTURING else LoopState.LISTENING
        }

    /** Any thread: echo a published Turn's transcript. Nothing to say ([EchoText]) or no speaker: ignored. */
    fun echo(transcript: String?) {
        if (speaker == null) return
        say(EchoText.of(transcript) ?: return)
    }

    /**
     * Any thread: say [text] as it is written, as a machine Turn under the same
     * rules as an echo (into Silence, mic closed). For what the loop has to say
     * on its own account, such as a failure it noticed. No speaker: ignored.
     */
    fun announce(text: String) {
        if (speaker == null || text.isBlank()) return
        say(text)
    }

    private fun say(text: String) {
        synchronized(lock) {
            if (!finished) return run { pending.add(text) }
        }
        listener.unspoken(1) // a transcript finished decoding after the Session ended
    }

    /** Any thread: machine Turn [id] ended (done, error or stopped) at [atNs], on the capture clock. */
    fun spoken(id: Long, atNs: Long) {
        ended.add(Ended(id, atNs))
    }

    /**
     * Capture thread: the first [count] of [samples], the last of them
     * captured at [capturedAtNs]. Fed to the segmenter while the mic is open,
     * dropped while it is closed.
     */
    fun accept(samples: ShortArray, count: Int, capturedAtNs: Long) {
        val chunkStart = position
        position += count
        var from = 0 // samples of this chunk already handled
        while (true) {
            when (phase) {
                Phase.SPEAKING -> {
                    val end = takeEnd()
                    when {
                        end != null -> if (!speakNext(capturedAtNs, chunkStart + from)) guard(end)
                        capturedAtNs - speakingSinceNs > speakingLimitNs -> {
                            speaker?.abandon(lastId)
                            listener.lost(lastId)
                            guard(capturedAtNs)
                        }
                        else -> {
                            segmenter.skip(count - from, capturedAtNs)
                            return
                        }
                    }
                }
                Phase.GUARD -> {
                    val rest = count - from
                    val open = openAfter(guardUntilNs, rest, capturedAtNs)
                    segmenter.skip(rest - open, capturedAtNs - open * NS_PER_S / sampleRate)
                    if (open == 0) return
                    from = count - open
                    phase = Phase.OPEN
                    listener.reopened(chunkStart + from, chunkStart + from - closedAt)
                }
                Phase.OPEN -> {
                    if (from < count) {
                        val chunk = if (from == 0) samples else samples.copyOfRange(from, count)
                        segmenter.accept(chunk, count - from, capturedAtNs)
                        from = count
                    }
                    // Every sample so far was captured before this call: speaking now closes the mic on the next one.
                    if (segmenter.state == TurnState.CAPTURING || !speakNext(capturedAtNs, position)) return
                }
            }
        }
    }

    /**
     * Capture thread: the Session is ending. A Turn still being captured
     * closes; what was never spoken is reported, now and as later
     * transcripts arrive (Turns still being decoded).
     */
    fun finish() {
        segmenter.finish()
        val unspoken = synchronized(lock) {
            finished = true
            pending.size.also { pending.clear() }
        }
        if (unspoken > 0) listener.unspoken(unspoken)
    }

    /** Starts the next queued machine Turn, closing the mic at stream sample [atSample]; false if none is queued. */
    private fun speakNext(nowNs: Long, atSample: Long): Boolean {
        val s = speaker ?: return false
        val text = pending.poll() ?: return false
        val id = ++lastId
        if (phase == Phase.OPEN) closedAt = atSample
        phase = Phase.SPEAKING
        speakingSinceNs = nowNs
        speakingLimitNs = speakingLimitMs(text) * NS_PER_MS
        listener.speaking(id, text, atSample)
        s.speak(id, text)
        return true
    }

    private fun guard(fromNs: Long) {
        phase = Phase.GUARD
        guardUntilNs = fromNs + guardNs
    }

    /** When the current machine Turn ended, if the speaker has said so; ends of earlier ones are dropped. */
    private fun takeEnd(): Long? {
        var at: Long? = null
        while (true) {
            val e = ended.poll() ?: return at
            if (e.id == lastId) at = e.atNs
        }
    }

    /**
     * Of the last [n] samples, the last of them captured at [lastNs], how many
     * were captured strictly after [untilNs]. The guard interval includes its end.
     */
    private fun openAfter(untilNs: Long, n: Int, lastNs: Long): Int {
        if (n == 0 || lastNs <= untilNs) return 0
        val open = ((lastNs - untilNs) * sampleRate + NS_PER_S - 1) / NS_PER_S
        return minOf(open, n.toLong()).toInt()
    }

    companion object {
        private const val NS_PER_MS = 1_000_000L
        private const val NS_PER_S = 1_000_000_000L

        /**
         * How long a machine Turn may go without the speaker reporting its end
         * before the mic reopens anyway: 10 s, plus 150 ms per character (about
         * twice as long as a TTS engine takes at its default rate).
         */
        fun speakingLimitMs(text: String): Long = 10_000L + 150L * text.length
    }
}
