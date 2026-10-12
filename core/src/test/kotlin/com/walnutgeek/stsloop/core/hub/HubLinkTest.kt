package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.corpus.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** A clock and timers the test moves by hand. */
class FakeScheduler : Scheduler {
    private class Timer(val at: Long, val task: Runnable) : Cancellable {
        var cancelled = false
        override fun cancel() { cancelled = true }
    }

    var now = 0L
        private set
    private val timers = mutableListOf<Timer>()

    override fun schedule(delayMs: Long, task: Runnable): Cancellable = Timer(now + delayMs, task).also { timers += it }

    /** Timers still to fire, as delays from now. */
    fun pending(): List<Long> = timers.filter { !it.cancelled }.map { it.at - now }.sorted()

    fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = timers.filter { !it.cancelled && it.at <= until }.minByOrNull { it.at } ?: break
            timers -= next
            now = next.at
            next.task.run()
        }
        now = until
    }
}

/** Records every connection the link opens; the test plays the hub's side through each socket's events. */
class FakeTransport : HubTransport {
    inner class Socket(val url: String, val token: String, val events: HubSocket.Events) : HubSocket {
        val sent = mutableListOf<String>()
        var closedWith: Int? = null
        override fun send(text: String): Boolean = closedWith == null && sent.add(text)
        override fun close(code: Int, reason: String) { if (closedWith == null) closedWith = code }
    }

    val sockets = mutableListOf<Socket>()
    val last: Socket get() = sockets.last()

    override fun open(url: String, token: String, events: HubSocket.Events): HubSocket = Socket(url, token, events).also { sockets += it }
}

class HubLinkTest {
    private val pairing = Protocol.decode(Fixtures.text("v1", "pairing/basic.json")) as Pairing
    private val transport = FakeTransport()
    private val scheduler = FakeScheduler()
    private val statuses = mutableListOf<HubStatus>()
    private val snapshots = mutableListOf<Descriptors>()
    private val link = link(pairing)

    private fun link(pairing: Pairing?) = HubLink(pairing, transport, scheduler, object : HubLink.Listener {
        override fun status(status: HubStatus) { statuses += status }
        override fun descriptors(descriptors: Descriptors) { snapshots += descriptors }
    })

    private fun hub(rel: String) = transport.last.events.received(Fixtures.text("v1", rel))

    private fun welcomed() {
        link.start()
        transport.last.events.opened()
        hub("welcome/basic.json")
    }

    // --- exchanges.json, replayed against the phone --------------------------

    @TestFactory
    fun `every exchange, replayed from the hub's side, leaves the phone where the fixtures say`(): List<DynamicTest> =
        Fixtures.versions().flatMap { version ->
            Fixtures.exchanges(version).map { exchange -> DynamicTest.dynamicTest("$version: ${exchange.name}") { replay(version, exchange) } }
        }

    private fun replay(version: String, exchange: Fixtures.Exchange) {
        val transport = FakeTransport()
        val scheduler = FakeScheduler()
        val snapshots = mutableListOf<Descriptors>()
        val replies = mutableListOf<Reply>()
        val link = HubLink(pairing, transport, scheduler, object : HubLink.Listener {
            override fun descriptors(descriptors: Descriptors) { snapshots += descriptors }
            override fun reply(reply: Reply) { replies += reply }
        })
        link.start()
        val socket = transport.last
        assertEquals(pairing.url, socket.url)
        assertEquals(pairing.token, socket.token, "the phone always connects with its paired token")
        // A phone cannot be idle (it says hello on open), but it must not give up on the hub sooner than the hub on it.
        for (idle in exchange.steps.mapNotNull { it["idle_ms"] as Long? }) assertTrue(HubLink.WELCOME_TIMEOUT_MS >= idle)
        val hubSteps = exchange.steps.filter { it["from"] == "hub" }
        if (hubSteps.first()["http"] == null) {
            socket.events.opened()
            // Whatever the exchange's phone says, this phone's one message is its hello.
            assertEquals(listOf(Fixtures.json(version, "hello/basic.json")), socket.sent.map(Json::parse))
        }
        var refusal: Refusal? = null
        var welcomed = false
        for (step in exchange.steps) {
            if (step["from"] == "phone") {
                // After the welcome the phone's requests are its own, sent through the link: fixture for fixture.
                val message = (step["fixture"] as String?)?.let { Protocol.decode(Fixtures.text(version, it)) }
                if (welcomed && message is Request) {
                    assertTrue(link.send(message))
                    assertEquals(Fixtures.json(version, step["fixture"] as String), Json.parse(socket.sent.last()))
                }
                continue
            }
            when {
                "http" in step -> socket.events.failed((step["http"] as Long).toInt(), null)
                "close" in step -> socket.events.closed((step["close"] as Long).toInt(), "")
                else -> {
                    val message = Protocol.decode(Fixtures.text(version, step["fixture"] as String))
                    if (message is Refusal) refusal = message
                    if (message is Welcome) welcomed = true
                    socket.events.received(Fixtures.text(version, step["fixture"] as String))
                }
            }
        }
        val last = hubSteps.last()
        when {
            "http" in last -> {
                assertEquals(HubStatus.Unauthorised, link.status)
                assertEquals(emptyList<Long>(), scheduler.pending(), "a wrong token is not retried")
            }
            "close" in last && refusal != null -> {
                assertEquals(HubStatus.Refused(refusal), link.status)
                assertEquals(emptyList<Long>(), scheduler.pending(), "a refusal is not retried")
            }
            "close" in last -> {
                assertInstanceOf(HubStatus.Offline::class.java, link.status)
                assertEquals(listOf(HubLink.FIRST_RETRY_MS), scheduler.pending(), "a plain close is retried")
            }
            else -> {
                assertInstanceOf(HubStatus.Online::class.java, link.status)
                val sent = hubSteps.mapNotNull { it["fixture"] as String? }.map { Protocol.decode(Fixtures.text(version, it)) }
                assertEquals(sent.filterIsInstance<Descriptors>(), snapshots)
                assertEquals(sent.filterIsInstance<Reply>(), replies)
            }
        }
        assertEquals(1, transport.sockets.size)
    }

    // --- the link itself ---------------------------------------------------------

    @Test
    fun `with no pairing the link is Unpaired and opens nothing`() {
        val unpaired = link(null)
        unpaired.start()
        assertEquals(HubStatus.Unpaired, unpaired.status)
        assertTrue(transport.sockets.isEmpty())
    }

    @Test
    fun `a welcome makes the link Online and its Descriptors reach the listener`() {
        welcomed()
        assertTrue(link.online)
        hub("descriptors/stub.json")
        assertEquals("hub", snapshots.single().buckets.single().name)
        assertEquals(listOf(HubStatus.Connecting, HubStatus.Online(Protocol.decode(Fixtures.text("v1", "welcome/basic.json")) as Welcome)), statuses)
    }

    @Test
    fun `a request is sent only while the hub is online, and replies reach the listener`() {
        val replies = mutableListOf<Reply>()
        val link = HubLink(pairing, transport, scheduler, object : HubLink.Listener {
            override fun reply(reply: Reply) { replies += reply }
        })
        val request = Protocol.decode(Fixtures.text("v1", "request/status.json")) as Request
        assertFalse(link.send(request), "not started")
        link.start()
        transport.last.events.opened()
        transport.last.events.received(Fixtures.text("v1", "reply/status.json"))
        assertFalse(link.send(request), "not welcomed yet")
        assertEquals(1, transport.last.sent.size, "only the hello")
        assertTrue(replies.isEmpty(), "a reply before the welcome is ignored")
        transport.last.events.received(Fixtures.text("v1", "welcome/basic.json"))
        assertTrue(link.send(request))
        assertEquals(Fixtures.json("v1", "request/status.json"), Json.parse(transport.last.sent.last()))
        transport.last.events.received(Fixtures.text("v1", "reply/extras.json"))
        assertEquals(listOf(Protocol.decode(Fixtures.text("v1", "reply/status.json"))), replies)
        transport.last.events.closed(1006, "gone")
        assertFalse(link.send(request), "offline")
    }

    @Test
    fun `Descriptors before the welcome, unknown messages and broken frames are ignored`() {
        link.start()
        transport.last.events.opened()
        hub("descriptors/stub.json")
        transport.last.events.received("{not json")
        hub("message/future-type.json")
        assertEquals(HubStatus.Connecting, link.status)
        hub("welcome/basic.json")
        transport.last.events.received("""{"type": "descriptors"}""")
        assertTrue(snapshots.isEmpty())
        assertTrue(link.online)
    }

    @Test
    fun `a lost connection is retried with a growing delay, reset by the next welcome`() {
        welcomed()
        transport.last.events.closed(1006, "gone")
        assertEquals(HubStatus.Offline("closed 1006 gone"), link.status)
        assertEquals(listOf(1_000L), scheduler.pending())
        scheduler.advance(1_000)
        assertEquals(2, transport.sockets.size)
        transport.last.events.failed(null, java.io.IOException("unreachable"))
        assertEquals(listOf(2_000L), scheduler.pending())
        scheduler.advance(2_000)
        transport.last.events.failed(null, null)
        assertEquals(listOf(4_000L), scheduler.pending())
        scheduler.advance(4_000)
        transport.last.events.opened()
        hub("welcome/basic.json")
        transport.last.events.closed(1000, "replaced")
        assertEquals(listOf(1_000L), scheduler.pending())
    }

    @Test
    fun `the retry delay stops growing at its cap`() {
        link.start()
        repeat(12) {
            transport.last.events.failed(null, null)
            scheduler.advance(scheduler.pending().single())
        }
        transport.last.events.failed(null, null)
        assertEquals(listOf(HubLink.MAX_RETRY_MS), scheduler.pending())
    }

    @Test
    fun `no welcome in time closes the connection and retries`() {
        link.start()
        transport.last.events.opened()
        scheduler.advance(HubLink.WELCOME_TIMEOUT_MS)
        assertEquals(1000, transport.sockets[0].closedWith)
        assertInstanceOf(HubStatus.Offline::class.java, link.status)
        scheduler.advance(HubLink.FIRST_RETRY_MS)
        assertEquals(2, transport.sockets.size)
    }

    @Test
    fun `the hub is offline only once a connection has failed, until the next welcome`() {
        assertTrue(link.offline, "not started")
        link.start()
        assertFalse(link.offline, "the first connection is still being tried")
        transport.last.events.failed(null, null)
        assertTrue(link.offline)
        scheduler.advance(HubLink.FIRST_RETRY_MS)
        assertEquals(HubStatus.Connecting, link.status)
        assertTrue(link.offline, "a retry is not news")
        transport.last.events.opened()
        hub("welcome/basic.json")
        assertFalse(link.offline)
        assertTrue(link(null).offline, "unpaired")
    }

    @Test
    fun `a welcome in another major is a refusal, not retried`() {
        link.start()
        transport.last.events.opened()
        transport.last.events.received("""{"type": "welcome", "protocol": {"major": 2, "minor": 0}, "capabilities": []}""")
        val refused = link.status as HubStatus.Refused
        assertEquals("unsupported_protocol", refused.refusal.reason)
        assertEquals(1000, transport.last.closedWith)
        assertEquals(emptyList<Long>(), scheduler.pending())
    }

    @Test
    fun `a lost connection's late events change nothing`() {
        link.start()
        transport.last.events.opened()
        scheduler.advance(HubLink.WELCOME_TIMEOUT_MS)
        // The socket the link gave up on reports its close, then fails, as OkHttp may.
        transport.last.events.closed(1000, "no welcome")
        transport.last.events.failed(null, null)
        assertEquals(listOf(HubLink.FIRST_RETRY_MS), scheduler.pending())
        welcomedAgainAfter(HubLink.FIRST_RETRY_MS)
        transport.last.events.closed(1006, "")
        transport.last.events.failed(null, null)
        assertEquals(listOf(HubLink.FIRST_RETRY_MS), scheduler.pending())
    }

    private fun welcomedAgainAfter(ms: Long) {
        scheduler.advance(ms)
        transport.last.events.opened()
        hub("welcome/basic.json")
    }

    @Test
    fun `a network change reconnects at once, replacing a connection that may be dead`() {
        welcomed()
        link.networkChanged()
        assertEquals(1000, transport.sockets[0].closedWith)
        assertEquals(2, transport.sockets.size)
        assertEquals(HubStatus.Connecting, link.status)
        // The old socket's late close changes nothing.
        transport.sockets[0].events.closed(1000, "replaced by a newer connection")
        assertEquals(HubStatus.Connecting, link.status)
        transport.last.events.opened()
        hub("welcome/basic.json")
        assertTrue(link.online)
    }

    @Test
    fun `a network change while waiting to retry retries at once`() {
        link.start()
        transport.last.events.failed(null, null)
        scheduler.advance(500)
        link.networkChanged()
        assertEquals(2, transport.sockets.size)
        assertEquals(listOf(HubLink.WELCOME_TIMEOUT_MS), scheduler.pending())
    }

    @Test
    fun `a refused or unauthorised link waits for the next start, network changes or not`() {
        link.start()
        transport.last.events.opened()
        hub("refusal/unsupported-major.json")
        transport.last.events.closed(1008, "unsupported_protocol")
        link.networkChanged()
        assertEquals(1, transport.sockets.size)
        assertInstanceOf(HubStatus.Refused::class.java, link.status)
        link.stop()
        link.start()
        transport.last.events.failed(401, null)
        link.networkChanged()
        scheduler.advance(60_000)
        assertEquals(2, transport.sockets.size)
        assertEquals(HubStatus.Unauthorised, link.status)
    }

    @Test
    fun `stop closes the connection, cancels every retry and ignores late events`() {
        welcomed()
        link.stop()
        assertEquals(1000, transport.last.closedWith)
        transport.last.events.closed(1006, "")
        hub("descriptors/stub.json")
        scheduler.advance(600_000)
        assertEquals(1, transport.sockets.size)
        assertTrue(snapshots.isEmpty())
        assertEquals(HubStatus.Offline("stopped"), link.status)
    }
}
