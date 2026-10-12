package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.grammar.BucketRegistry
import com.walnutgeek.stsloop.core.grammar.Descriptor

/**
 * The current Bucket (`CONTEXT.md`, Focus; `docs/mvp.md`, "Focus"), for one
 * Session. A Session starts in the [BucketRegistry.inbox]. Its Buckets are
 * read from [buckets] at each call, so hub Buckets that arrive mid-Session
 * can be focused. A lone Bucket
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
class Focus(private val buckets: () -> BucketRegistry, private val timeoutMs: Long) {
    /** Over a fixed set of Buckets. */
    constructor(buckets: BucketRegistry, timeoutMs: Long) : this({ buckets }, timeoutMs)

    private var current: Descriptor = Descriptor.INBOX
    private var lastHeardMs = 0L

    /** The Bucket the last timeout left, and the stream time it timed out at; null once Focus is set again. */
    private var timedOut: Descriptor? = null
    private var timedOutAtMs = 0L

    /** The focused Bucket. */
    val bucket: Descriptor
        @Synchronized get() = current

    /** The Bucket the last timeout left, until Focus is set again; null when there is none. */
    val left: Descriptor?
        @Synchronized get() = timedOut

    /**
     * The name of the Bucket a Turn whose utterance ended at [endedAtMs], with
     * no command and no Declaration, is filed under: the Bucket that had Focus
     * then. Null in the Inbox, where a Note is unlabelled, and for a Bucket
     * that does not take Notes.
     */
    @Synchronized
    fun filing(endedAtMs: Long): String? = at(endedAtMs).takeIf { buckets().takesNotes(it) }?.name

    /** The Bucket that had Focus when a Turn's utterance ended at [endedAtMs]: whose commands it is read against. */
    @Synchronized
    fun at(endedAtMs: Long): Descriptor = timedOut?.takeIf { endedAtMs < timedOutAtMs } ?: current

    /** The Bucket called [name] in the current registry, or null. */
    fun named(name: String): Descriptor? = buckets().named(name)

    /**
     * Focus the Bucket called [name], heard at [atMs]. False, and Focus
     * unchanged, when there is no such Bucket: the hub's Buckets can change
     * between a Turn's classification and its publication.
     */
    @Synchronized
    fun set(name: String, atMs: Long): Boolean {
        current = named(name) ?: return false
        timedOut = null
        heard(atMs)
        return true
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
        if (current === Descriptor.INBOX || nowMs - lastHeardMs < timeoutMs) return false
        timedOut = current
        timedOutAtMs = lastHeardMs + timeoutMs
        current = Descriptor.INBOX
        return true
    }

    companion object {
        /** Said when Focus times out back to the Inbox. */
        const val TIMED_OUT = "back to inbox."
    }
}
