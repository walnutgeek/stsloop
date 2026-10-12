package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.BucketRequest
import com.walnutgeek.stsloop.core.Ids
import com.walnutgeek.stsloop.core.turn.SessionCommands

/**
 * The `request` Primitive, for one Session: a command a Bucket declares
 * ("status", "what's blocked") goes to the hub as a [Request], and the hub's
 * [Reply] is said when it comes, into the next Silence like any reply. The
 * phone knows nothing of what the command does; the Descriptor named it and
 * the Provider words the answer.
 *
 * The loop never waits on the hub: [request] returns at once, with
 * [SessionCommands.HUB_OFFLINE] when the request cannot be sent. A reply
 * that has not come within [timeoutMs] is given up on, and the loop says
 * [NO_ANSWER] ([tick]); a reply that comes later, or to a request this
 * Session did not send, is ignored.
 *
 * Thread-safe: requests come from the STT thread, replies from the hub
 * connection's, ticks from the capture thread. [say] is called with the lock
 * held, so it must return at once; [send] is called without it.
 */
class HubRequests(
    /** Sends one message to the hub; false when it cannot (not connected and welcomed). Must return at once. */
    private val send: (Message) -> Boolean,
    /** Says a reply as it is written. */
    private val say: (String) -> Unit,
    /** A monotonic clock, in milliseconds. */
    private val clockMs: () -> Long,
    private val timeoutMs: Long = REPLY_TIMEOUT_MS,
    private val newId: () -> String = Ids::next,
    private val listener: Listener = object : Listener {},
) {
    interface Listener {
        fun sent(request: Request, bucket: BucketRequest) {}
        fun replied(request: Request, reply: Reply, afterMs: Long) {}
        fun timedOut(request: Request) {}

        /** A reply that matches no request waiting for one: late, or not ours. */
        fun ignored(reply: Reply) {}
    }

    private class Waiting(val request: Request, val sentAtMs: Long)

    private val waiting = LinkedHashMap<String, Waiting>()

    /** Sends [bucket]'s request. Returns what to say now: null when it was sent, and its reply will be said. */
    fun request(bucket: BucketRequest): String? {
        val request = synchronized(this) {
            var id = newId()
            while (id in waiting) id = newId()
            Request(id, bucket.bucketId, bucket.command).also { waiting[id] = Waiting(it, clockMs()) }
        }
        // Not under this lock: the link calls [replied] under its own, so holding both here could deadlock.
        if (!send(request)) {
            synchronized(this) { waiting.remove(request.id) }
            return SessionCommands.HUB_OFFLINE
        }
        listener.sent(request, bucket)
        return null
    }

    /** The hub answered. */
    @Synchronized
    fun replied(reply: Reply) {
        val w = waiting.remove(reply.id) ?: return listener.ignored(reply)
        listener.replied(w.request, reply, clockMs() - w.sentAtMs)
        say(reply.say)
    }

    /** Gives up on every request older than [timeoutMs], saying [NO_ANSWER] once for them. */
    @Synchronized
    fun tick() {
        if (waiting.isEmpty()) return
        val now = clockMs()
        val late = waiting.values.filter { now - it.sentAtMs >= timeoutMs }
        if (late.isEmpty()) return
        for (w in late) {
            waiting.remove(w.request.id)
            listener.timedOut(w.request)
        }
        say(NO_ANSWER)
    }

    /** Requests still waiting for a reply. */
    val pending: Int @Synchronized get() = waiting.size

    companion object {
        /** How long the phone waits for a reply. The hub answers from what it already knows, in milliseconds. */
        const val REPLY_TIMEOUT_MS = 5_000L

        /** Said when the hub has not answered in time. */
        const val NO_ANSWER = "no answer from the hub."
    }
}
