package com.walnutgeek.stsloop.core.hub

/**
 * What the phone knows about its hub when "hub status" or "hub details" is
 * said (#75), all of it read without asking the hub.
 */
data class HubFacts(
    /** The link's status now. */
    val status: HubStatus,
    /** Whether the loop counts the hub offline ([HubLink.offline]): a try has failed since the Session began or the last welcome. */
    val offline: Boolean,
    /** What the phone last learnt about reaching the hub (`reach.json`). */
    val reach: HubReach,
    /** The agent Buckets in the last snapshot of Descriptors ([HubReport.agents]); null when the phone has none. */
    val agents: Int?,
    /** How many Turns wait to upload to the hub; null when that is not known (or not asked: "hub status"). */
    val toUpload: Int?,
    /** Wall time now, in milliseconds. */
    val nowMs: Long,
) {
    companion object {
        /**
         * The facts as [store] keeps them, for a link at [status]: `reach.json`,
         * the cached Descriptors, and, when [toUpload] is asked for, the Turns of
         * [corpus] the hub has not acknowledged. Reads files: for the writing
         * (STT) thread, never the capture thread or the hub connection's. A file
         * that cannot be read leaves its fact unknown. The hub was last there
         * when its last welcomed connection ended; if the app stopped before it
         * saw that end, when that connection began ([HubReach.lastSeenAtMs]).
         */
        fun read(status: HubStatus, offline: Boolean, store: HubStore, corpus: UploadSource?, toUpload: Boolean, nowMs: Long) =
            HubFacts(
                status, offline, store.reach(),
                agents = runCatching { store.descriptors()?.let(HubReport::agents) }.getOrNull(),
                toUpload = if (!toUpload || corpus == null || status == HubStatus.Unpaired) {
                    null
                } else {
                    runCatching { store.uploads().uploaded().let { done -> corpus.turns().count { it !in done } } }.getOrNull()
                },
                nowMs = nowMs,
            )
    }
}

/**
 * The words of "hub status" ([short]) and "hub details" ([long]), #75.
 *
 * Online, the hub's [StatusReport] gives the agents, how many are blocked,
 * herdr's state and the Claude Code hooks; the phone adds how long it has
 * been connected and how many Turns wait to upload, which only it knows.
 * Offline the phone answers alone, from [HubReach] (since when, and the last
 * failure, in the same words as the settings screen, [HubFailure.text]) and
 * the cached Descriptors ("last saw 6 agents").
 *
 * - "hub status": "hub connected, 6 agents, 2 blocked.", with herdr's state
 *   added only when it is in trouble ("herdr not answering."); offline,
 *   "hub offline for 12 minutes, last saw 6 agents."
 * - "hub details": "hub connected for 40 minutes. 6 agents, 2 blocked. herdr
 *   0.9.1 ok. Claude Code hooks installed. 3 Turns wait to upload."; offline,
 *   "hub offline for 12 minutes. <the last failure> last saw 6 agents. 3
 *   Turns wait to upload."
 *
 * The first try of a Session is not offline yet ([HubFacts.offline]): "connecting to the hub."
 */
object HubReport {
    const val NO_HUB = "no hub paired."
    const val CONNECTING = "connecting to the hub."

    /** The Descriptor type of an agent Bucket. */
    const val AGENT = "agent"

    /** "hub status". [report] is the hub's answer; null when the phone answers alone. */
    fun short(facts: HubFacts, report: StatusReport?): String = when {
        facts.status == HubStatus.Unpaired -> NO_HUB
        report != null -> {
            val herdr = report.herdr?.takeIf { it.state == HerdrReport.NOT_ANSWERING || it.state == HerdrReport.PROTOCOL_MISMATCH }
            listOfNotNull("hub connected, ${agents(report)}.", herdr?.let(::herdr)).joinToString(" ")
        }
        !facts.offline -> CONNECTING
        else -> {
            val since = offlineFor(facts) ?: return "hub offline, never connected."
            "$since${facts.agents?.let { ", last saw ${count(it.toLong(), "agent")}" }.orEmpty()}."
        }
    }

    /** "hub details". [report] is the hub's answer; null when the phone answers alone. */
    fun long(facts: HubFacts, report: StatusReport?): String {
        val parts = when {
            facts.status == HubStatus.Unpaired -> return "$NO_HUB pair one in settings."
            report != null -> listOfNotNull(
                facts.reach.connectedAtMs?.let { "hub connected ${duration(facts.nowMs - it)}." } ?: "hub connected.",
                "${agents(report)}.",
                report.herdr?.let(::herdr),
                report.hooksInstalled?.let { if (it) "Claude Code hooks installed." else "Claude Code hooks not installed." },
            )
            !facts.offline -> listOf(CONNECTING)
            else -> listOfNotNull(
                offlineFor(facts)?.let { "$it." } ?: "hub offline, never connected.",
                (facts.status.failure ?: facts.reach.failure)?.text,
                facts.agents?.takeIf { facts.reach.lastSeenAtMs != null }?.let { "last saw ${count(it.toLong(), "agent")}." },
            )
        }
        return (parts + listOfNotNull(facts.toUpload?.let(::toUpload))).joinToString(" ")
    }

    /** How many agent Buckets [descriptors] holds. */
    fun agents(descriptors: Descriptors): Int = descriptors.buckets.count { it.type == AGENT }

    /** How long [ms] is, in words a TTS engine reads well: "for 12 minutes", "for 1 hour 5 minutes", "for 3 days". */
    fun duration(ms: Long): String {
        val minutes = maxOf(ms, 0) / 60_000
        if (minutes < 1) return "for less than a minute"
        val days = minutes / (24 * 60)
        if (days >= 1) return "for ${count(days, "day")}"
        val hours = minutes / 60
        val rest = minutes % 60
        return "for " + listOfNotNull(
            hours.takeIf { it > 0 }?.let { count(it, "hour") },
            rest.takeIf { it > 0 || hours == 0L }?.let { count(it, "minute") },
        ).joinToString(" ")
    }

    private fun offlineFor(facts: HubFacts): String? = facts.reach.lastSeenAtMs?.let { "hub offline ${duration(facts.nowMs - it)}" }

    private fun agents(report: StatusReport): String = when (report.agents) {
        0L -> "no agents"
        else -> "${count(report.agents, "agent")}, ${if (report.blocked == 0L) "none" else report.blocked} blocked"
    }

    private fun herdr(h: HerdrReport): String = when (h.state) {
        HerdrReport.OK -> listOfNotNull("herdr", h.version, "ok.").joinToString(" ")
        HerdrReport.NOT_INSTALLED -> "no herdr."
        HerdrReport.CHECKING -> "herdr not checked yet."
        else -> "herdr ${h.state.replace('_', ' ')}."
    }

    private fun toUpload(n: Int): String = when (n) {
        0 -> "no Turns wait to upload."
        1 -> "1 Turn waits to upload."
        else -> "$n Turns wait to upload."
    }

    private fun count(n: Long, what: String) = when (n) {
        0L -> "no ${what}s"
        1L -> "1 $what"
        else -> "$n ${what}s"
    }
}
