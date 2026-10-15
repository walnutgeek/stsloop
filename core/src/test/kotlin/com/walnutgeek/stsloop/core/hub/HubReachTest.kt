package com.walnutgeek.stsloop.core.hub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HubReachTest {
    private val unreachable = HubFailure.Unreachable("100.101.102.103:7717")
    private val welcome = Protocol.decode(Fixtures.text("v1", "welcome/basic.json")) as Welcome

    @Test
    fun `nothing tried yet, no nudge`() {
        assertFalse(HubReach.EMPTY.nudge)
        assertEquals("Not connected yet.", HubReach.EMPTY.line { "t$it" })
    }

    @Test
    fun `a failure nudges, and the line says which and when`() {
        val r = HubReach.EMPTY.failed(unreachable, 5)
        assertTrue(r.nudge)
        assertEquals("Last try failed (t5). ${unreachable.text}", r.line { "t$it" })
    }

    @Test
    fun `a connection clears the failure and says when`() {
        val r = HubReach.EMPTY.failed(unreachable, 5).connected(7)
        assertFalse(r.nudge)
        assertNull(r.failure)
        assertEquals("Connected (t7).", r.line { "t$it" })
    }

    @Test
    fun `Ignore silences the nudge while the failure kind stays the same`() {
        val ignored = HubReach.EMPTY.failed(unreachable, 5).ignore()
        assertFalse(ignored.nudge)
        val again = ignored.failed(HubFailure.Unreachable("100.101.102.103:7717"), 9)
        assertFalse(again.nudge, "the same kind, failing again")
        assertEquals(9L, again.lastFailure?.atMs)
        assertEquals("Last try failed (t9). ${unreachable.text}", again.line { "t$it" }, "the line still says it")
    }

    @Test
    fun `Ignore ends when the failure kind changes, even back to the ignored kind`() {
        val changed = HubReach.EMPTY.failed(unreachable, 5).ignore().failed(HubFailure.NoWelcome, 6)
        assertTrue(changed.nudge)
        assertTrue(changed.failed(unreachable, 7).nudge, "Ignore was for the run of failures that changed")
    }

    @Test
    fun `Ignore ends once a connection succeeds`() {
        val r = HubReach.EMPTY.failed(unreachable, 5).ignore().connected(6).failed(unreachable, 7)
        assertTrue(r.nudge)
    }

    @Test
    fun `Ignore with no failure changes nothing`() {
        assertEquals(HubReach.EMPTY, HubReach.EMPTY.ignore())
        val connected = HubReach.EMPTY.connected(3)
        assertEquals(connected, connected.ignore())
    }

    @Test
    fun `a status is recorded only when it is a welcome or a failure`() {
        val r = HubReach.EMPTY
        assertEquals(r, r.record(HubStatus.Connecting, 1))
        assertEquals(r, r.record(HubStatus.Offline("stopped"), 1))
        assertEquals(r, r.record(HubStatus.Unpaired, 1))
        assertEquals(r.connected(1), r.record(HubStatus.Online(welcome), 1))
        assertEquals(r.failed(HubFailure.TokenRefused, 2), r.record(HubStatus.Unauthorised, 2))
        assertEquals(r.failed(unreachable, 3), r.record(HubStatus.Offline("x", unreachable), 3))
    }

    @Test
    fun `the record reads back from its JSON`() {
        val records = listOf(
            HubReach.EMPTY,
            HubReach.EMPTY.connected(1_760_000_000_000),
            HubReach.EMPTY.connected(1_760_000_000_000).failed(HubFailure.ProtocolRefused(null, "No."), 1_760_000_001_000).ignore(),
            HubReach.EMPTY.failed(unreachable, 1_760_000_002_000),
        )
        for (r in records) assertEquals(r, HubReach.parse(r.toJson()))
    }

    @Test
    fun `a broken file reads as nothing tried, and a failure it cannot read is dropped`() {
        assertEquals(HubReach.EMPTY, HubReach.parse("{not json"))
        val noFailure = HubReach.parse("""{"connected_at": "2025-10-09T08:53:20.000Z", "failure": {"kind": "solar_flare"}, "failed_at": "2025-10-09T08:53:21.000Z", "ignored": "solar_flare"}""")
        assertEquals(HubReach.EMPTY.connected(1_760_000_000_000), noFailure)
    }

    @Test
    fun `the store keeps the record across restarts, and pairing again or forgetting drops it`(@TempDir files: File) {
        val pairing = Protocol.decode(Fixtures.text("v1", "pairing/basic.json")) as Pairing
        val store = HubStore(files)
        store.pair(pairing)
        assertEquals(HubReach.EMPTY, store.reach())
        store.updateReach { it.failed(unreachable, 5).ignore() }
        assertEquals(HubReach.EMPTY.failed(unreachable, 5).ignore(), HubStore(files).reach(), "a new store, as after a restart")
        store.pair(pairing) // the same hub, scanned again
        assertEquals(HubReach.EMPTY, store.reach())
        store.updateReach { it.failed(unreachable, 5) }
        store.forget()
        assertEquals(HubReach.EMPTY, store.reach())
        File(files, "hub/${HubStore.REACH}").writeText("garbage")
        assertEquals(HubReach.EMPTY, store.reach(), "an unreadable record nudges no one")
    }
}
