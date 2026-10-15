package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.Ids
import com.walnutgeek.stsloop.core.turn.SessionCommands

/**
 * "Hub status" and "hub details" (#75), for one Session. While the hub is
 * online the phone asks it ([StatusRequest]) and words its [StatusReport]
 * when it comes, into the next Silence like any reply ([HubReport]);
 * otherwise the phone answers alone, at once, from what it kept.
 *
 * The loop never waits on the hub: [ask] returns with the words to say now,
 * or null when the hub was asked. What the phone knows ([facts]) is read by
 * [ask], on the caller's thread (the STT thread, which writes Turns and may
 * read a few files), never on the hub connection's, whose listener holds its lock. A report not come within [timeoutMs] is
 * given up on, and the loop says [HubRequests.NO_ANSWER] ([tick]); a hub
 * too old to know `status_request` ignores it, and ends there too.
 *
 * Thread-safe, like [HubRequests]: [say] is called with the lock held, so it
 * must return at once; [send] is called without it.
 */
class StatusQueries(
    /** Sends one message to the hub; false when it cannot. Must return at once. */
    private val send: (Message) -> Boolean,
    /** Says an answer as it is written. */
    private val say: (String) -> Unit,
    /** What the phone knows now; [Detail.LONG] also counts the Turns waiting to upload. */
    private val facts: (Detail) -> HubFacts,
    /** A monotonic clock, in milliseconds. */
    private val clockMs: () -> Long,
    private val timeoutMs: Long = HubRequests.REPLY_TIMEOUT_MS,
    private val newId: () -> String = Ids::next,
    private val listener: Listener = object : Listener {},
) {
    /** "hub status" or "hub details". */
    enum class Detail { SHORT, LONG }

    /** What happened to each status request, for the log. */
    interface Listener {
        fun asked(request: StatusRequest, detail: Detail) {}
        fun answered(request: StatusRequest, report: StatusReport, afterMs: Long) {}
        fun timedOut(request: StatusRequest, afterMs: Long) {}
        fun ignored(report: StatusReport) {}
    }

    private class Waiting(val request: StatusRequest, val detail: Detail, val facts: HubFacts, val sentAtMs: Long)

    private val waiting = LinkedHashMap<String, Waiting>()

    /** Answers [detail]: returns what to say now, or null when the hub was asked and its report will be said. */
    fun ask(detail: Detail): String? {
        val known = facts(detail)
        if (known.status !is HubStatus.Online) return words(detail, known, null)
        val request = synchronized(this) {
            var id = newId()
            while (id in waiting) id = newId()
            StatusRequest(id).also { waiting[id] = Waiting(it, detail, known, clockMs()) }
        }
        listener.asked(request, detail) // before the send, so a fast report is never logged first
        if (!send(request)) {
            // The link dropped since the status was read: the record of it is not written yet.
            synchronized(this) { waiting.remove(request.id) }
            return SessionCommands.HUB_OFFLINE
        }
        return null
    }

    /** The hub answered. */
    @Synchronized
    fun answered(report: StatusReport) {
        val w = waiting.remove(report.id) ?: return listener.ignored(report)
        listener.answered(w.request, report, clockMs() - w.sentAtMs)
        say(words(w.detail, w.facts, report))
    }

    /** Gives up on every request older than [timeoutMs], saying [HubRequests.NO_ANSWER] once for them. */
    @Synchronized
    fun tick() {
        if (waiting.isEmpty()) return
        val now = clockMs()
        val late = waiting.values.filter { now - it.sentAtMs >= timeoutMs }
        if (late.isEmpty()) return
        for (w in late) {
            waiting.remove(w.request.id)
            listener.timedOut(w.request, now - w.sentAtMs)
        }
        say(HubRequests.NO_ANSWER)
    }

    /** Requests still waiting for a report. */
    val pending: Int @Synchronized get() = waiting.size

    private fun words(detail: Detail, facts: HubFacts, report: StatusReport?) =
        if (detail == Detail.SHORT) HubReport.short(facts, report) else HubReport.long(facts, report)
}
