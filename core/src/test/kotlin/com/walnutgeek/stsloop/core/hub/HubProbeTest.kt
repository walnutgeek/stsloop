package com.walnutgeek.stsloop.core.hub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HubProbeTest {
    private val pairing = Protocol.decode(Fixtures.text("v1", "pairing/basic.json")) as Pairing
    private val welcome = Protocol.decode(Fixtures.text("v1", "welcome/basic.json")) as Welcome
    private val herdr = Protocol.decode(Fixtures.text("v1", "descriptors/herdr.json")) as Descriptors
    private val outcomes = mutableListOf<HubProbe.Outcome>()
    private val probe = HubProbe { outcomes += it }

    @Test
    fun `the first failure ends the probe`() {
        probe.status(HubStatus.Connecting)
        probe.status(HubStatus.Offline("stopped"))
        assertTrue(outcomes.isEmpty(), "not a failure")
        probe.status(HubStatus.Offline("x", HubFailure.NoWelcome))
        probe.status(HubStatus.Unauthorised)
        assertEquals(listOf(HubProbe.Outcome.Failed(HubFailure.NoWelcome)), outcomes)
    }

    @Test
    fun `the first Descriptors after a welcome end it, connected`() {
        probe.status(HubStatus.Online(welcome))
        assertTrue(outcomes.isEmpty(), "the Descriptors follow the welcome")
        probe.descriptors(herdr)
        probe.status(HubStatus.Offline("x", HubFailure.NoWelcome))
        assertEquals(listOf(HubProbe.Outcome.Connected(herdr)), outcomes)
    }

    @Test
    fun `connected says how many agents`() {
        val agents = herdr.buckets.count { it.type == "agent" }
        assertEquals("connected, $agents agents.", HubProbe.Outcome.Connected(herdr).said)
        val one = Descriptors(herdr.buckets.filter { it.type == "agent" }.take(1))
        assertEquals("connected, 1 agent.", HubProbe.Outcome.Connected(one).said)
        assertEquals("connected, 0 agents.", HubProbe.Outcome.Connected(Descriptors(emptyList())).said)
        assertEquals(HubFailure.TokenRefused.text, HubProbe.Outcome.Failed(HubFailure.TokenRefused).said)
    }

    @Test
    fun `over a link that cannot connect, the probe fails as unreachable`() {
        val transport = FakeTransport()
        val scheduler = FakeScheduler()
        val link = HubLink(pairing, transport, scheduler, object : HubLink.Listener {
            override fun status(status: HubStatus) = probe.status(status)
            override fun descriptors(descriptors: Descriptors) = probe.descriptors(descriptors)
        })
        link.start()
        scheduler.advance(HubLink.CONNECT_TIMEOUT_MS)
        assertEquals(listOf(HubProbe.Outcome.Failed(HubFailure.Unreachable("100.101.102.103:7717"))), outcomes)
    }
}
