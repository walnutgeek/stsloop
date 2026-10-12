package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.grammar.Descriptor
import com.walnutgeek.stsloop.core.turn.Focus
import com.walnutgeek.stsloop.core.turn.SessionCommands

/**
 * The `draft` Primitive's state for one Session (`CONTEXT.md`, Draft;
 * `docs/mvp.md`, "Send"): each agent Bucket's Draft, by Descriptor id, and the
 * sends waiting on the hub.
 *
 * - A Draft is the Turns said under its Bucket's Focus, in order ([append]),
 *   joined by spaces. "Scratch that" takes its Turn back out ([drop]);
 *   "cancel" drops the whole Draft ([cancel]). A Draft outlives Focus: it is
 *   kept when Focus moves or times out ([timedOut] says so), and never sent
 *   but by "send".
 * - "Send" [take]s the Draft into a send under a new idempotency key, so the
 *   Turns said meanwhile start a new Draft. Once the Draft has been read back,
 *   [dispatch] sends it. Until a [Sent] answers that key, the same [Send],
 *   key and all, goes again on every welcome ([welcomed]), and the hub
 *   delivers a key at most once, so a reconnect never sends a Draft twice.
 * - A [Sent] without a reason means the Draft was delivered: it is said, and
 *   the send is done. With any reason, the Draft was not sent, or may not
 *   have been: it is said with [DRAFT_KEPT], and the text goes back in front
 *   of the Bucket's Draft, to be sent again only by a new "send" (a new key).
 *   An `uncertain` answer (it may have arrived) says [MAYBE_ARRIVED] instead.
 * - Not able to send ([dispatch] while the hub is offline): [OFFLINE], and
 *   the Draft is put back. Not answered within [unconfirmedMs]: [NOT_CONFIRMED],
 *   said once, and the send keeps waiting for the next welcome.
 *
 * Nothing here outlives the Session: a send still unanswered when it ends is
 * reported by [unanswered], and its text is in the Corpus (the "send" Turn).
 *
 * Thread-safe: Turns come from the STT thread, [Sent]s and welcomes from the
 * hub connection's (with its lock held), ticks from the capture thread. [say]
 * is called with this lock held and must return at once; [send] is called
 * without it.
 */
class Drafts(
    /** Sends one message to the hub; false when it cannot (not welcomed). Must return at once. */
    private val send: (Message) -> Boolean,
    /** Says a line into the next Silence. */
    private val say: (String) -> Unit,
    /** A monotonic clock, in milliseconds. */
    private val clockMs: () -> Long,
    private val unconfirmedMs: Long = UNCONFIRMED_MS,
    /** A new idempotency key; never the same twice. */
    val newKey: () -> String = Protocol::newKey,
    private val listener: Listener = object : Listener {},
) {
    /** What happened to each send, for the log. */
    interface Listener {
        fun sent(send: Send, again: Boolean) {}
        fun answered(send: Send, sent: Sent, afterMs: Long) {}
        fun ignored(sent: Sent) {}
    }

    /** One Turn's words in a Draft; [turn] is its directory name, so "scratch that" can find it. */
    private class Piece(val turn: String?, val text: String)

    private class Draft(var name: String, val pieces: MutableList<Piece> = mutableListOf())

    private class Pending(val send: Send, val name: String, val pieces: List<Piece>) {
        var dispatched = false
        var sentAtMs = 0L
        var told = false
    }

    private val drafts = LinkedHashMap<String, Draft>()
    private val pending = LinkedHashMap<String, Pending>()

    /** Appends [text], said in the Turn [turn], to the Draft of the agent Bucket [name] whose Descriptor id is [bucketId]. */
    @Synchronized
    fun append(bucketId: String, name: String, turn: String?, text: String) {
        val draft = drafts.getOrPut(bucketId) { Draft(name) }
        draft.name = name
        draft.pieces += Piece(turn, text)
    }

    /** The Draft of [bucketId] as it would be sent, or null when there is none. */
    @Synchronized
    fun text(bucketId: String): String? = drafts[bucketId]?.pieces?.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.text }

    /** Whether a send for [bucketId] is waiting on the hub. */
    @Synchronized
    fun sending(bucketId: String): Boolean = pending.values.any { it.send.bucket == bucketId }

    /** "Scratch that" tombstoned the Turn [turn]: its words leave whichever Draft holds them. True when one did. */
    @Synchronized
    fun drop(turn: String): Boolean = drafts.values.any { d -> d.pieces.removeAll { it.turn == turn } }

    /** Drops the Draft of [bucketId]; returns what it said, or null when there was none. */
    @Synchronized
    fun cancel(bucketId: String): String? = text(bucketId).also { drafts.remove(bucketId) }

    /**
     * Takes the Draft of [bucketId] into a send under [key]; the next Turns
     * start a new Draft. Returns the text, or null when there was no Draft.
     * Nothing goes to the hub before [dispatch].
     */
    @Synchronized
    fun take(bucketId: String, key: String): String? {
        val text = text(bucketId) ?: return null
        val draft = drafts.remove(bucketId)!!
        pending[key] = Pending(Send(key, bucketId, text), draft.name, draft.pieces.toList())
        return text
    }

    /** The Draft taken under [key] has been read back: send it. Says [OFFLINE], and keeps the Draft, when it cannot go. */
    fun dispatch(key: String) {
        // Marked dispatched before it goes, so a welcome that comes meanwhile sends it again (the same key: harmless).
        val p = synchronized(this) {
            pending[key]?.takeIf { !it.dispatched }?.also {
                it.dispatched = true
                it.sentAtMs = clockMs()
            }
        } ?: return
        if (!send(p.send)) {
            synchronized(this) {
                if (pending.remove(key) != null) restore(p)
                say(OFFLINE)
            }
            return
        }
        listener.sent(p.send, again = false)
    }

    /** The Draft taken under [key] will not be sent after all (the Session ended before its readback): put it back. */
    @Synchronized
    fun withdraw(key: String) {
        val p = pending[key]?.takeIf { !it.dispatched } ?: return
        pending.remove(key)
        restore(p)
    }

    /** The hub answered a send. */
    @Synchronized
    fun sent(sent: Sent) {
        val p = pending.remove(sent.key) ?: return listener.ignored(sent)
        listener.answered(p.send, sent, clockMs() - p.sentAtMs)
        when (sent.reason) {
            null -> say(sent.say)
            UNCERTAIN -> {
                restore(p)
                say("${sent.say} $MAYBE_ARRIVED")
            }
            else -> {
                restore(p)
                say("${sent.say} $DRAFT_KEPT")
            }
        }
    }

    /** The hub has welcomed the phone, again perhaps: every send it has not answered goes again, with its key. */
    fun welcomed() {
        val again = synchronized(this) { pending.values.filter { it.dispatched }.map { it.send } }
        for (s in again) if (send(s)) listener.sent(s, again = true)
    }

    /** Says [NOT_CONFIRMED], once per send, for a send unanswered for [unconfirmedMs]. */
    @Synchronized
    fun tick() {
        if (pending.isEmpty()) return
        val now = clockMs()
        val late = pending.values.filter { it.dispatched && !it.told && now - it.sentAtMs >= unconfirmedMs }
        if (late.isEmpty()) return
        late.forEach { it.told = true }
        say(NOT_CONFIRMED)
    }

    /** What the loop says when Focus times out of [left]: [Focus.TIMED_OUT], after "draft kept for …" when it has a Draft. */
    @Synchronized
    fun timedOut(left: Descriptor?): String {
        val id = left?.id ?: return Focus.TIMED_OUT
        return if (text(id) != null) "draft kept for ${SessionCommands.bucketAsWords(left.name)} ${Focus.TIMED_OUT}" else Focus.TIMED_OUT
    }

    /** Sends the hub has not answered yet, for the log at the end of a Session. */
    @Synchronized
    fun unanswered(): List<Send> = pending.values.map { it.send }

    /** Puts a send's Turns back in front of its Bucket's Draft. Lock held. */
    private fun restore(p: Pending) {
        val draft = drafts.getOrPut(p.send.bucket) { Draft(p.name) }
        draft.pieces.addAll(0, p.pieces)
    }

    companion object {
        /** How long a send may go unanswered before the loop says so, in milliseconds. */
        const val UNCONFIRMED_MS = 10_000L

        /** Said after any answer that did not deliver the Draft. */
        const val DRAFT_KEPT = "draft kept."

        /** The [Sent.reason] for a send that may or may not have arrived. */
        const val UNCERTAIN = "uncertain"

        /**
         * Said after an [UNCERTAIN] answer: the Draft is kept, but sending it
         * again is a new send, which the agent would get twice if the first
         * did arrive.
         */
        const val MAYBE_ARRIVED = "it may have arrived. draft kept; check before you send it again."

        /** Said when "send" finds the hub offline. */
        const val OFFLINE = "hub offline, draft kept."

        /** Said once when a send has gone unanswered for [UNCONFIRMED_MS]. */
        const val NOT_CONFIRMED = "the hub has not confirmed the send yet."
    }
}
