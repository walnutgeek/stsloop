package com.walnutgeek.stsloop.core.hub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** What "hub status" (short) and "hub details" (long) say, online from the hub's report, offline from what the phone kept. */
class HubReportTest {
    private val welcome = Protocol.decode(Fixtures.text("v1", "welcome/basic.json")) as Welcome
    private val herdr = Protocol.decode(Fixtures.text("v1", "status_report/herdr.json")) as StatusReport
    private val unreachable = HubFailure.Unreachable("100.101.102.103:7717")
    private val minute = 60_000L
    private val now = 1_760_000_000_000L

    private fun online(connectedMinutesAgo: Long = 40, backlog: Int? = 3) = HubFacts(
        HubStatus.Online(welcome), offline = false, reach = HubReach.EMPTY.connected(now - connectedMinutesAgo * minute),
        agents = 3, backlog = backlog, nowMs = now,
    )

    private fun offline(reach: HubReach, agents: Int? = 6, status: HubStatus = HubStatus.Offline("x", unreachable), backlog: Int? = 3) =
        HubFacts(status, offline = true, reach = reach, agents = agents, backlog = backlog, nowMs = now)

    @Test
    fun `online, short`() {
        val report = StatusReport("r", agents = 6, blocked = 2)
        assertEquals("hub connected, 6 agents, 2 blocked.", HubReport.short(online(), report))
        assertEquals("hub connected, 1 agent, none blocked.", HubReport.short(online(), report.copy(agents = 1, blocked = 0)))
        assertEquals("hub connected, no agents.", HubReport.short(online(), report.copy(agents = 0, blocked = 0)))
    }

    @Test
    fun `online, short, says herdr only when it is in trouble`() {
        val report = StatusReport("r", agents = 0, blocked = 0, herdr = HerdrReport(HerdrReport.PROTOCOL_MISMATCH, "0.8.2"))
        assertEquals("hub connected, no agents. herdr protocol mismatch.", HubReport.short(online(), report))
        assertEquals(
            "hub connected, no agents. herdr not answering.",
            HubReport.short(online(), report.copy(herdr = HerdrReport(HerdrReport.NOT_ANSWERING))),
        )
        for (state in listOf(HerdrReport.OK, HerdrReport.NOT_INSTALLED, HerdrReport.CHECKING)) {
            assertEquals("hub connected, no agents.", HubReport.short(online(), report.copy(herdr = HerdrReport(state))), state)
        }
    }

    @Test
    fun `online, long`() {
        assertEquals(
            "hub connected for 40 minutes. 3 agents, 1 blocked. herdr 0.9.1 ok. Claude Code hooks installed. 3 Turns wait to upload.",
            HubReport.long(online(), herdr),
        )
    }

    @Test
    fun `online, long, each herdr state and the hooks`() {
        val bare = StatusReport("r", agents = 0, blocked = 0)
        fun said(h: HerdrReport?, hooks: Boolean? = null) = HubReport.long(online(backlog = null), bare.copy(herdr = h, hooksInstalled = hooks))
        assertEquals("hub connected for 40 minutes. no agents.", said(null))
        assertEquals("hub connected for 40 minutes. no agents. herdr ok.", said(HerdrReport(HerdrReport.OK)))
        assertEquals("hub connected for 40 minutes. no agents. herdr protocol mismatch.", said(HerdrReport(HerdrReport.PROTOCOL_MISMATCH, "0.8.2")))
        assertEquals("hub connected for 40 minutes. no agents. herdr not answering.", said(HerdrReport(HerdrReport.NOT_ANSWERING, "0.9.1")))
        assertEquals("hub connected for 40 minutes. no agents. no herdr.", said(HerdrReport(HerdrReport.NOT_INSTALLED)))
        assertEquals("hub connected for 40 minutes. no agents. herdr not checked yet.", said(HerdrReport(HerdrReport.CHECKING)))
        assertEquals("hub connected for 40 minutes. no agents. herdr restarting soon.", said(HerdrReport("restarting_soon")), "a state this phone does not know")
        assertEquals("hub connected for 40 minutes. no agents. Claude Code hooks not installed.", said(null, hooks = false))
    }

    @Test
    fun `online, long, a hub that has not told the phone when it connected`() {
        val facts = online().copy(reach = HubReach.EMPTY)
        assertEquals("hub connected. 3 agents, 1 blocked.", HubReport.long(facts.copy(backlog = null), herdr.copy(herdr = null, hooksInstalled = null)))
    }

    @Test
    fun `the upload backlog`() {
        val bare = StatusReport("r", agents = 0, blocked = 0)
        assertEquals("hub connected for 40 minutes. no agents. no Turns wait to upload.", HubReport.long(online(backlog = 0), bare))
        assertEquals("hub connected for 40 minutes. no agents. 1 Turn waits to upload.", HubReport.long(online(backlog = 1), bare))
    }

    @Test
    fun `offline, short`() {
        val lost = HubReach.EMPTY.connected(now - 60 * minute).left(now - 12 * minute).failed(unreachable, now - minute)
        assertEquals("hub offline for 12 minutes, last saw 6 agents.", HubReport.short(offline(lost), null))
        assertEquals("hub offline for 12 minutes, last saw 1 agent.", HubReport.short(offline(lost, agents = 1), null))
        assertEquals("hub offline for 12 minutes, last saw no agents.", HubReport.short(offline(lost, agents = 0), null))
        assertEquals("hub offline for 12 minutes.", HubReport.short(offline(lost, agents = null), null))
    }

    @Test
    fun `offline, short, a hub never reached`() {
        assertEquals("hub offline, never connected.", HubReport.short(offline(HubReach.EMPTY.failed(unreachable, now)), null))
    }

    @Test
    fun `offline, long, names the last failure`() {
        val lost = HubReach.EMPTY.connected(now - 3 * 60 * minute).left(now - 65 * minute)
        assertEquals(
            "hub offline for 1 hour 5 minutes. ${unreachable.text} last saw 6 agents. 3 Turns wait to upload.",
            HubReport.long(offline(lost.failed(unreachable, now - minute)), null),
        )
        assertEquals(
            "hub offline for 1 hour 5 minutes. ${HubFailure.TokenRefused.text} last saw 6 agents. 3 Turns wait to upload.",
            HubReport.long(offline(lost, status = HubStatus.Unauthorised), null),
            "the link's own failure, before reach.json has it",
        )
        assertEquals(
            "hub offline for 1 hour 5 minutes. last saw 6 agents.",
            HubReport.long(offline(lost, status = HubStatus.Offline("closed 1006"), backlog = null), null),
            "lost, and no retry has failed yet",
        )
    }

    @Test
    fun `the first try of a Session is not yet offline`() {
        val facts = offline(HubReach.EMPTY.connected(now - minute)).copy(status = HubStatus.Connecting, offline = false)
        assertEquals("connecting to the hub.", HubReport.short(facts, null))
        assertEquals("connecting to the hub. 3 Turns wait to upload.", HubReport.long(facts, null))
    }

    @Test
    fun `no hub paired`() {
        val facts = HubFacts(HubStatus.Unpaired, offline = true, reach = HubReach.EMPTY, agents = null, backlog = null, nowMs = now)
        assertEquals("no hub paired.", HubReport.short(facts, null))
        assertEquals("no hub paired. pair one in settings.", HubReport.long(facts, null))
    }

    @Test
    fun `how long, in words`() {
        for ((ms, said) in listOf(
            -5L to "for less than a minute",
            59_999L to "for less than a minute",
            minute to "for 1 minute",
            12 * minute to "for 12 minutes",
            60 * minute to "for 1 hour",
            65 * minute to "for 1 hour 5 minutes",
            (23 * 60 + 59) * minute to "for 23 hours 59 minutes",
            24 * 60 * minute to "for 1 day",
            (3 * 24 * 60 + 70) * minute to "for 3 days",
        )) assertEquals(said, HubReport.duration(ms), "$ms ms")
    }

    @Test
    fun `the facts as the store keeps them`(@TempDir files: File) {
        val store = HubStore(files)
        store.pair(Protocol.decode(Fixtures.text("v1", "pairing/basic.json")) as Pairing)
        store.saveDescriptors(Protocol.decode(Fixtures.text("v1", "descriptors/herdr.json")) as Descriptors)
        store.updateReach { it.connected(5).left(9) }
        val a = "2026-10-14T09:00:00.000Z-aaaaaa"
        val b = "2026-10-14T09:01:00.000Z-bbbbbb"
        store.uploads().add(a)
        val corpus = object : UploadSource {
            override fun turns() = listOf(a, b)
            override fun offer(name: String) = throw UnsupportedOperationException()
            override fun audio(name: String, offset: Long, length: Int) = throw UnsupportedOperationException()
        }
        val offline = HubStatus.Offline("x", unreachable)
        assertEquals(HubFacts(offline, true, HubReach.EMPTY.connected(5).left(9), 3, 1, now), HubFacts.read(offline, true, store, corpus, backlog = true, nowMs = now))
        assertEquals(null, HubFacts.read(offline, true, store, corpus, backlog = false, nowMs = now).backlog, "hub status does not count")
        File(files, "hub/${HubStore.DESCRIPTORS}").writeText("garbage")
        assertEquals(null, HubFacts.read(offline, true, store, null, backlog = true, nowMs = now).agents, "an unreadable cache")
    }

    @Test
    fun `agents counted from the cached Descriptors`() {
        val herdrBuckets = Protocol.decode(Fixtures.text("v1", "descriptors/herdr.json")) as Descriptors
        assertEquals(3, HubReport.agents(herdrBuckets))
        assertEquals(0, HubReport.agents(Protocol.decode(Fixtures.text("v1", "descriptors/stub.json")) as Descriptors))
        assertEquals(null, HubReport.agents(null))
    }
}
