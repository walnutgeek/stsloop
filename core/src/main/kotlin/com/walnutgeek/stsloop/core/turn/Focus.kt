package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.grammar.BucketRegistry
import com.walnutgeek.stsloop.core.grammar.Descriptor

/**
 * The current Bucket (`CONTEXT.md`, Focus; `docs/mvp.md`, "Focus"), for one
 * Session. A Session starts in the [BucketRegistry.inbox]. A lone Bucket
 * alias [set]s Focus; a Turn that is neither a command nor Declared is then
 * filed under [filing]. Focus on any other Bucket returns to the Inbox once
 * [timeoutMs] pass with nothing heard ([tick]), and the loop says
 * [TIMED_OUT].
 *
 * Its clock is the Session's sample stream, in milliseconds from sample 0,
 * so the Turns that keep it and the [tick]s that end it agree on time. Turns
 * are published on the STT thread and [tick] runs on the capture thread, so
 * every call is synchronised. A Turn reaches the Corpus some seconds after
 * it was captured (decoding may lag by up to `TurnCapture.MAX_QUEUED_MS`), so
 * it is filed by the Focus at the time its utterance ended ([filing]), even
 * when the timeout has passed since.
 */
class Focus(private val buckets: BucketRegistry, private val timeoutMs: Long) {
    private var current: Descriptor = buckets.inbox
    private var lastHeardMs = 0L

    /** The Bucket the last timeout left, and the stream time it timed out at; null once Focus is set again. */
    private var timedOut: Descriptor? = null
    private var timedOutAtMs = 0L

    /** The focused Bucket. */
    val bucket: Descriptor
        @Synchronized get() = current

    /**
     * The name of the Bucket a Turn whose utterance ended at [endedAtMs], with
     * no command and no Declaration, is filed under: the Bucket that had Focus
     * then. Null in the Inbox, where a Note is unlabelled, and for a Bucket
     * that does not take Notes.
     */
    @Synchronized
    fun filing(endedAtMs: Long): String? {
        val then = timedOut?.takeIf { endedAtMs < timedOutAtMs } ?: current
        return then.takeIf { buckets.takesNotes(it) }?.name
    }

    /** Focus the Bucket called [name], heard at [atMs]. */
    @Synchronized
    fun set(name: String, atMs: Long) {
        current = requireNotNull(buckets.named(name)) { "no Bucket called \"$name\"" }
        timedOut = null
        heard(atMs)
    }

    /** A Turn, or speech being captured, at [atMs]: the timeout starts again. */
    @Synchronized
    fun heard(atMs: Long) {
        lastHeardMs = maxOf(lastHeardMs, atMs)
    }

    /**
     * The stream is at [nowMs]; [capturing] says whether speech is being
     * captured, which counts as heard. True, once, when Focus has just timed
     * out back to the Inbox: say [TIMED_OUT].
     */
    @Synchronized
    fun tick(nowMs: Long, capturing: Boolean): Boolean {
        if (capturing) heard(nowMs)
        if (current === buckets.inbox || nowMs - lastHeardMs < timeoutMs) return false
        timedOut = current
        timedOutAtMs = lastHeardMs + timeoutMs
        current = buckets.inbox
        return true
    }

    companion object {
        /** Said when Focus times out back to the Inbox. */
        const val TIMED_OUT = "back to inbox."
    }
}
