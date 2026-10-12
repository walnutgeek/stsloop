package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.grammar.BucketRegistry
import com.walnutgeek.stsloop.core.grammar.Descriptor
import com.walnutgeek.stsloop.core.grammar.Primitive

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
 * every call is synchronised.
 */
class Focus(private val buckets: BucketRegistry, private val timeoutMs: Long) {
    private var current: Descriptor = buckets.inbox
    private var lastHeardMs = 0L

    /** The focused Bucket. */
    val bucket: Descriptor
        @Synchronized get() = current

    /**
     * The Bucket's name a Turn with no command and no Declaration is filed
     * under, or null: the Inbox, where a Note is unlabelled, and a Bucket that
     * does not take Notes file nothing.
     */
    val filing: String?
        @Synchronized get() = current.takeIf { it !== buckets.inbox && Primitive.NOTE in it.primitives }?.name

    /** Focus the Bucket called [name], heard at [atMs]. */
    @Synchronized
    fun set(name: String, atMs: Long) {
        current = requireNotNull(buckets.named(name)) { "no Bucket called \"$name\"" }
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
        current = buckets.inbox
        return true
    }

    companion object {
        /** Said when Focus times out back to the Inbox. */
        const val TIMED_OUT = "back to inbox."
    }
}
