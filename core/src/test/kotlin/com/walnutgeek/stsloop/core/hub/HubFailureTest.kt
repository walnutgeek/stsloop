package com.walnutgeek.stsloop.core.hub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HubFailureTest {
    @Test
    fun `each kind is worded to name the one thing to check`() {
        assertEquals(
            "Can't reach the hub at 100.101.102.103:7717. Is Tailscale on and is this phone in your tailnet? Is stsloop-hub serve running?",
            HubFailure.Unreachable("100.101.102.103:7717").text,
        )
        assertEquals("The hub doesn't know this phone. Run stsloop-hub init and scan again.", HubFailure.TokenRefused.text)
        assertEquals("The hub needs a newer app. Update stsloop on this phone.", HubFailure.ProtocolRefused(HubFailure.Update.APP, "x").text)
        assertEquals("This app needs a newer hub. Upgrade stsloop-hub.", HubFailure.ProtocolRefused(HubFailure.Update.HUB, "x").text)
        assertEquals("The hub refused this phone: Go away.", HubFailure.ProtocolRefused(null, "Go away.").text)
        assertEquals("The hub answered but didn't finish the handshake.", HubFailure.NoWelcome.text)
    }

    @Test
    fun `the address is the pairing URL's host and port`() {
        assertEquals("100.101.102.103:7717", HubFailure.address("ws://100.101.102.103:7717/"))
        assertEquals("hub.tail1234.ts.net:7717", HubFailure.address("wss://hub.tail1234.ts.net:7717/ws?x=1"))
        assertEquals("hub", HubFailure.address("ws://hub"))
    }

    @Test
    fun `a refusal names the side to update`() {
        val major2 = Protocol.decode(Fixtures.text("v1", "refusal/unsupported-major.json")) as Refusal
        // The hub speaks only 1 to a phone that said 2 ... but this phone speaks 1: what the hub supports decides.
        val older = Refusal("unsupported_protocol", "m", listOf(Version(Protocol.VERSION.major - 1, 3)))
        val newer = Refusal("unsupported_protocol", "m", listOf(Version(Protocol.VERSION.major + 1, 0), Version(Protocol.VERSION.major, 9)))
        assertEquals(HubFailure.Update.HUB, (HubFailure.of(older) as HubFailure.ProtocolRefused).update)
        assertEquals(HubFailure.Update.APP, (HubFailure.of(newer) as HubFailure.ProtocolRefused).update)
        assertEquals(major2.message, (HubFailure.of(major2) as HubFailure.ProtocolRefused).message)
        val badHello = Protocol.decode(Fixtures.text("v1", "refusal/bad-hello.json")) as Refusal
        assertEquals(HubFailure.Update.APP, (HubFailure.of(badHello) as HubFailure.ProtocolRefused).update)
        val unknown = HubFailure.of(Refusal("busy", "The hub is busy.")) as HubFailure.ProtocolRefused
        assertNull(unknown.update)
        assertEquals("The hub refused this phone: The hub is busy.", unknown.text)
    }

    @Test
    fun `a failure reads back from its JSON as itself`() {
        for (f in listOf(
            HubFailure.Unreachable("100.101.102.103:7717"),
            HubFailure.TokenRefused,
            HubFailure.ProtocolRefused(HubFailure.Update.APP, "m"),
            HubFailure.ProtocolRefused(null, "The hub is busy."),
            HubFailure.NoWelcome,
        )) {
            assertEquals(f, HubFailure.fromJson(f.toJson()))
        }
        assertNull(HubFailure.fromJson(mapOf("kind" to "solar_flare")))
        assertNull(HubFailure.fromJson(mapOf("kind" to "unreachable")), "no address")
    }

    @Test
    fun `a status names its failure, if it is one`() {
        val address = "100.101.102.103:7717"
        assertNull(HubStatus.Connecting.failure)
        assertNull(HubStatus.Unpaired.failure)
        assertNull(HubStatus.Offline("stopped").failure)
        assertEquals(HubFailure.NoWelcome, HubStatus.Offline("no welcome", HubFailure.NoWelcome).failure)
        assertEquals(HubFailure.Unreachable(address), HubStatus.Offline("x", HubFailure.Unreachable(address)).failure)
        assertEquals(HubFailure.TokenRefused, HubStatus.Unauthorised.failure)
        assertEquals(HubFailure.Kind.PROTOCOL_REFUSED, HubStatus.Refused(Refusal("bad_hello", "m")).failure?.kind)
    }
}
