package com.walnutgeek.stsloop.core.summary

import com.walnutgeek.stsloop.core.Ids
import com.walnutgeek.stsloop.core.grammar.Descriptor
import com.walnutgeek.stsloop.core.hub.HubRequests
import com.walnutgeek.stsloop.core.hub.Message
import com.walnutgeek.stsloop.core.hub.Reply
import com.walnutgeek.stsloop.core.hub.Summarize
import com.walnutgeek.stsloop.core.turn.SessionCommands

/**
 * "Summarize bucket" (#76; `docs/mvp.md`, "Summaries") for one Session: a
 * short spoken summary of the Notes said in a capture Bucket since its last
 * summary ([NoteRange]).
 *
 * - [range] reads the Notes from the Corpus when the command is heard, so
 *   its Turn can record them; once that Turn is published, [summarize]
 *   carries it out.
 * - With the hub online and offering the `summaries` capability, the Notes'
 *   words go to it ([Summarize]: the newest are included even before they
 *   are uploaded), and its [Reply] is said when it comes. Only once that
 *   summary has been heard does the Bucket's mark move ([SummaryMarks]), so
 *   a summary the Session ends before, or the hub's own [Reply.FALLBACK],
 *   leaves those Notes to the next summary.
 * - Otherwise the phone answers alone at once, reading the count and the
 *   latest three Notes raw ([fallback]); so it does, after [HubRequests.NO_ANSWER],
 *   when the hub has not answered within [timeoutMs]. Neither moves the mark.
 * - No Notes in the range: [nothing], and nothing goes to the hub.
 *
 * The loop never waits on the hub: [summarize] returns at once. Thread-safe:
 * ranges and summaries come from the STT thread, replies from the hub
 * connection's, ticks from the capture thread. [say] is called with the lock
 * held and must return at once; its `heard` callback may come on any thread
 * and moves the mark through [io] (a file write), off the capture thread.
 */
class BucketSummaries(
    private val notes: NoteSource,
    private val marks: SummaryMarks,
    /** Whether the hub is welcomed now with the `summaries` capability; must return at once. */
    private val hubSummarises: () -> Boolean,
    /** Sends one message to the hub; false when it cannot. Must return at once. */
    private val send: (Message) -> Boolean,
    /** Says a line into the next Silence; `heard` runs once it is over, with whether it was said. */
    private val say: (text: String, heard: (Boolean) -> Unit) -> Unit,
    /** A monotonic clock, in milliseconds. */
    private val clockMs: () -> Long,
    /** Epoch milliseconds, for a first summary's last day. */
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = REPLY_TIMEOUT_MS,
    private val newId: () -> String = Ids::next,
    /** Runs a mark's file write; by default at once, on the calling thread. */
    private val io: (() -> Unit) -> Unit = { it() },
    private val listener: Listener = object : Listener {},
) {
    /** What happened to each summary, for the log. */
    interface Listener {
        fun sent(summarize: Summarize) {}
        fun replied(summarize: Summarize, reply: Reply, afterMs: Long) {}
        fun timedOut(summarize: Summarize, afterMs: Long) {}

        /** [bucket]'s summary was heard: its next one starts after the Turn [through]. */
        fun moved(bucket: String, through: String) {}

        /** Reading the Corpus, or writing a mark, failed. */
        fun failed(what: String, error: Exception) {}
    }

    private class Waiting(val summarize: Summarize, val range: NoteRange, val sentAtMs: Long)

    private val waiting = LinkedHashMap<String, Waiting>()

    /** The Notes a summary of [bucket] covers now, or null when the Corpus cannot be read. Reads the Corpus. */
    fun range(bucket: String): NoteRange? = try {
        NoteRange.of(notes.newestFirst(), bucket, marks.since(bucket), wallClockMs())
    } catch (e: Exception) {
        listener.failed("reading the Notes of $bucket", e)
        null
    }

    /** Carries out the summary of [range] ([CANNOT_READ] when null). Returns what to say now: null when it went to the hub. */
    fun summarize(range: NoteRange?): String? {
        if (range == null) return CANNOT_READ
        if (range.notes.isEmpty()) return nothing(range)
        if (!hubSummarises()) return fallback(range)
        val summarize = synchronized(this) {
            var id = newId()
            while (id in waiting) id = newId()
            Summarize(id, range.bucket, range.notes, range.since, range.more).also { waiting[id] = Waiting(it, range, clockMs()) }
        }
        // Not under this lock: the link calls [replied] under its own.
        if (!send(summarize)) {
            synchronized(this) { waiting.remove(summarize.id) }
            return fallback(range)
        }
        listener.sent(summarize)
        return null
    }

    /** The hub answered; false when [reply] is not to a summary this Session is waiting for. */
    @Synchronized
    fun replied(reply: Reply): Boolean {
        val w = waiting.remove(reply.id) ?: return false
        listener.replied(w.summarize, reply, clockMs() - w.sentAtMs)
        val through = w.range.newest.takeIf { reply.reason == null }
        say(reply.say) { heard -> if (heard && through != null) move(w.range.bucket, through) }
        return true
    }

    /** Gives up on every summary older than [timeoutMs]: says so, then reads its Notes raw. */
    @Synchronized
    fun tick() {
        if (waiting.isEmpty()) return
        val now = clockMs()
        for (w in waiting.values.filter { now - it.sentAtMs >= timeoutMs }) {
            waiting.remove(w.summarize.id)
            listener.timedOut(w.summarize, timeoutMs)
            say("${HubRequests.NO_ANSWER} ${fallback(w.range)}") {}
        }
    }

    /** Summaries still waiting for the hub. */
    val pending: Int @Synchronized get() = waiting.size

    private fun move(bucket: String, through: String) = io {
        try {
            marks.move(bucket, through)
            listener.moved(bucket, through)
        } catch (e: Exception) {
            listener.failed("moving the summary mark of $bucket", e)
        }
    }

    companion object {
        /** How long the phone waits for the hub's summary: its longest model timeout (22 s) and the trip. */
        const val REPLY_TIMEOUT_MS = 30_000L

        /** Said when the Corpus cannot be read. */
        const val CANNOT_READ = "cannot read the notes."

        private val COUNT_WORDS = listOf("", "one", "two", "three")

        /** [range]'s Bucket as words: "house project", "the inbox". */
        fun bucketWords(bucket: String): String =
            if (bucket == Descriptor.INBOX.name) "the inbox" else SessionCommands.bucketAsWords(bucket).removeSuffix(".")

        /** Said when [range] has no Notes. */
        fun nothing(range: NoteRange): String = bucketWords(range.bucket).let { b ->
            if (range.since != null) "nothing new in $b since last time." else "no notes in $b in the last day."
        }

        /**
         * [range] read raw, as the phone alone answers: the count, and the
         * latest three Notes, oldest of them first. "errands: 7 notes since
         * last time; the latest three: a. b. c."
         */
        fun fallback(range: NoteRange): String {
            val n = range.notes.size
            val count = if (n == 1 && !range.more) "one note" else "${if (range.more) "more than " else ""}$n note${if (n == 1) "" else "s"}"
            val span = if (range.since != null) "since last time" else "in the last day"
            val latest = range.notes.takeLast(3)
            val lead = if (n > latest.size || range.more) "; the latest ${COUNT_WORDS[latest.size]}: " else ": "
            return "${bucketWords(range.bucket)}: $count $span$lead" +
                latest.joinToString(" ") { it.text.trim().trimEnd('.', ',', ';', ':', '!', '?', ' ') + "." }
        }
    }
}
