package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.corpus.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executor

/** Turns the test hands the uploader; [failing] ones cannot be read. */
class FakeSource : UploadSource {
    val offers = sortedMapOf<String, TurnOffer>()
    val audio = mutableMapOf<String, ByteArray>()
    val failing = mutableSetOf<String>()
    var reads = 0

    fun add(name: String, bytes: ByteArray, turnJson: String = """{"id": "${name.substringAfterLast('-')}"}""") {
        offers[name] = TurnOffer(name, turnJson, sha256(bytes), bytes.size.toLong())
        audio[name] = bytes
    }

    override fun turns(): List<String> = offers.keys.toList()

    override fun offer(name: String): TurnOffer {
        if (name in failing) throw IOException("cannot read $name")
        return offers.getValue(name)
    }

    override fun audio(name: String, offset: Long, length: Int): ByteArray {
        reads++
        if (name in failing) throw IOException("cannot read $name")
        return audio.getValue(name).copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

class FakeLedger(vararg names: String) : UploadLedger {
    val names = names.toMutableList()
    override fun uploaded(): Set<String> = names.toSet()
    override fun add(name: String) { names += name }
}

/** Runs tasks only when the test says, so work posted to it is visibly not done on the caller's thread. */
class QueueExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { tasks += command }
    fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    val pending: Int get() = tasks.size
}

class CorpusUploaderTest {
    private val source = FakeSource()
    private val ledger = FakeLedger()
    private val sent = mutableListOf<Message>()
    private var sendOk = true
    private val executor = QueueExecutor()
    private val uploader = CorpusUploader(source, ledger, executor, send = { m -> sendOk && sent.add(m) }, pieceBytes = 4)

    private val corpusWelcome = Welcome(Protocol.VERSION, listOf("descriptors", "corpus"))

    private fun name(i: Int) = "2026-10-06T14:22:0$i.000Z-00000$i"

    private fun online(welcome: Welcome = corpusWelcome) {
        uploader.status(HubStatus.Online(welcome))
        executor.runAll()
    }

    private fun hub(ack: TurnAck) {
        uploader.received(ack)
        executor.runAll()
    }

    private fun send(turn: String, offset: Long) = hub(TurnAck(turn, TurnAck.SEND, offset = offset))
    private fun stored(turn: String) = hub(TurnAck(turn, TurnAck.STORED))

    private fun offered(): List<String> = sent.filterIsInstance<TurnOffer>().map { it.turn }

    /** Plays the hub's side of whole uploads until nothing more is offered. */
    private fun storeEverything() {
        var handled = 0
        while (handled < sent.size) {
            when (val m = sent[handled++]) {
                is TurnOffer -> send(m.turn, 0)
                is TurnAudio -> {
                    val next = m.offset + Base64.getDecoder().decode(m.data).size
                    if (m.last) stored(m.turn) else send(m.turn, next)
                }
                else -> Unit
            }
        }
    }

    // --- exchanges.json, replayed against the uploader -------------------------

    @TestFactory
    fun `every upload exchange, replayed from the hub's side, has the phone send exactly the phone's steps`(): List<DynamicTest> =
        Fixtures.versions().flatMap { version ->
            Fixtures.exchanges(version)
                .filter { e -> e.steps.any { (it["fixture"] as String?)?.startsWith("turn_offer/") == true } }
                .map { exchange -> DynamicTest.dynamicTest("$version: ${exchange.name}") { replay(version, exchange) } }
        }

    private fun replay(version: String, exchange: Fixtures.Exchange) {
        fun message(step: Map<String, Any?>) = Protocol.decode(Fixtures.text(version, step["fixture"] as String))
        val phoneSteps = exchange.steps.filter { it["from"] == "phone" && "fixture" in it }.mapNotNull(::message).filter { it !is Hello }
        // The phone's Corpus holds each Turn the exchange offers, with the Recording its pieces carry,
        // gathered from every exchange (one that only offers a held Turn sends no pieces).
        val source = FakeSource()
        val pieces = Fixtures.exchanges(version).flatMap { it.steps }.filter { it["from"] == "phone" && "fixture" in it }
            .mapNotNull(::message).filterIsInstance<TurnAudio>().distinct()
        for (offer in phoneSteps.filterIsInstance<TurnOffer>()) {
            source.offers[offer.turn] = offer
            source.audio[offer.turn] = pieces.filter { it.turn == offer.turn }.sortedBy { it.offset }
                .fold(ByteArray(0)) { acc, p -> acc + Base64.getDecoder().decode(p.data) }
            assertEquals(offer.bytes, source.audio.getValue(offer.turn).size.toLong())
        }
        val piece = phoneSteps.filterIsInstance<TurnAudio>().firstOrNull()?.let { Base64.getDecoder().decode(it.data).size } ?: 32
        val sent = mutableListOf<Message>()
        val ledger = FakeLedger()
        val uploader = CorpusUploader(source, ledger, Runnable::run, send = { sent.add(it) }, pieceBytes = piece)
        for (step in exchange.steps.filter { it["from"] == "hub" && "fixture" in it }) {
            when (val m = message(step)) {
                is Welcome -> uploader.status(HubStatus.Online(m))
                is TurnAck -> uploader.received(m)
                else -> Unit
            }
        }
        assertEquals(phoneSteps.map { Json.parse(Protocol.encode(it)) }, sent.map { Json.parse(Protocol.encode(it)) })
        val acks = exchange.steps.filter { it["from"] == "hub" && "fixture" in it }.mapNotNull(::message).filterIsInstance<TurnAck>()
        assertEquals(acks.filter { it.status == TurnAck.STORED }.map { it.turn }, ledger.names)
    }

    // --- what goes up, and when ------------------------------------------------

    @Test
    fun `the backlog uploads oldest first, skipping what the hub already acknowledged`() {
        source.add(name(1), ByteArray(3))
        source.add(name(2), ByteArray(3))
        source.add(name(3), ByteArray(3))
        ledger.names += name(2)
        online()
        storeEverything()
        assertEquals(listOf(name(1), name(3)), offered())
        assertEquals(listOf(name(2), name(1), name(3)), ledger.names)
    }

    @Test
    fun `a Recording goes up in pieces of the piece size, the last one marked`() {
        val audio = ByteArray(10) { it.toByte() }
        source.add(name(1), audio)
        online()
        storeEverything()
        val pieces = sent.filterIsInstance<TurnAudio>()
        assertEquals(listOf(0L, 4L, 8L), pieces.map { it.offset })
        assertEquals(listOf(false, false, true), pieces.map { it.last })
        assertEquals(audio.toList(), pieces.flatMap { Base64.getDecoder().decode(it.data).toList() })
    }

    @Test
    fun `an empty Recording goes up as one empty last piece`() {
        source.add(name(1), ByteArray(0))
        online()
        send(name(1), 0)
        assertEquals(TurnAudio.of(name(1), 0, ByteArray(0), last = true), sent.last())
    }

    @Test
    fun `one piece is in flight at a time, the next sent only once the hub asks`() {
        source.add(name(1), ByteArray(10))
        source.add(name(2), ByteArray(10))
        online()
        assertEquals(1, sent.size, "the offer, and nothing more until it is answered")
        send(name(1), 0)
        assertEquals(2, sent.size)
        assertEquals(listOf(name(1)), offered(), "the next Turn waits for this one to be stored")
    }

    @Test
    fun `the hub picks the offset each piece starts at`() {
        source.add(name(1), ByteArray(10) { it.toByte() })
        online()
        send(name(1), 6)
        assertEquals(TurnAudio.of(name(1), 6, byteArrayOf(6, 7, 8, 9), last = true), sent.last())
    }

    @Test
    fun `a Turn the hub already holds is recorded without sending its Recording`() {
        source.add(name(1), ByteArray(10))
        online()
        stored(name(1))
        assertEquals(listOf(name(1)), ledger.names)
        assertEquals(0, source.reads)
    }

    @Test
    fun `a Turn published during the Session is offered once the backlog is done, or at once when idle`() {
        source.add(name(1), ByteArray(3))
        online()
        source.add(name(2), ByteArray(3))
        uploader.published()
        executor.runAll()
        assertEquals(listOf(name(1)), offered(), "still busy with the first")
        storeEverything()
        assertEquals(listOf(name(1), name(2)), offered())
        source.add(name(3), ByteArray(3))
        uploader.published()
        executor.runAll()
        assertEquals(name(3), offered().last())
    }

    @Test
    fun `nothing is uploaded to a hub that did not agree to corpus`() {
        source.add(name(1), ByteArray(3))
        online(Welcome(Protocol.VERSION, listOf("descriptors")))
        uploader.published()
        executor.runAll()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `nothing is uploaded until the hub is online`() {
        source.add(name(1), ByteArray(3))
        uploader.published()
        uploader.status(HubStatus.Connecting)
        executor.runAll()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `every call only posts work, and none of it runs on the caller's thread`() {
        source.add(name(1), ByteArray(3))
        uploader.status(HubStatus.Online(corpusWelcome))
        uploader.received(TurnAck(name(1), TurnAck.SEND, offset = 0))
        uploader.published()
        assertTrue(sent.isEmpty())
        assertEquals(0, source.reads)
        assertEquals(3, executor.pending)
    }

    @Test
    fun `a connection lost mid-Turn offers that Turn again from the start on the next welcome`() {
        source.add(name(1), ByteArray(10))
        online()
        send(name(1), 0)
        uploader.status(HubStatus.Offline("closed 1006"))
        executor.runAll()
        send(name(1), 4) // a late ack from the old connection: ignored
        assertEquals(2, sent.size)
        online()
        assertEquals(listOf(name(1), name(1)), offered())
        storeEverything()
        assertEquals(listOf(name(1)), ledger.names)
    }

    @Test
    fun `a rejected Turn is skipped for the rest of the Session, and offered again in the next`() {
        source.add(name(1), ByteArray(3))
        source.add(name(2), ByteArray(3))
        online()
        hub(TurnAck(name(1), TurnAck.REJECTED, reason = "conflict"))
        assertEquals(listOf(name(1), name(2)), offered())
        storeEverything()
        online() // a reconnect in the same Session
        assertEquals(listOf(name(1), name(2)), offered())
        val next = CorpusUploader(source, ledger, Runnable::run, send = { sent.add(it) })
        next.status(HubStatus.Online(corpusWelcome))
        assertEquals(name(1), offered().last())
        assertEquals(listOf(name(2)), ledger.names)
    }

    @Test
    fun `a status the phone does not know reads as a rejection`() {
        source.add(name(1), ByteArray(3))
        source.add(name(2), ByteArray(3))
        online()
        hub(Protocol.decode(Fixtures.text("v1", "turn_ack/future-status.json").replace("a3f1c9", "000001").replace("14:22:07.431Z", "14:22:01.000Z")) as TurnAck)
        assertEquals(listOf(name(1), name(2)), offered())
        assertTrue(ledger.names.isEmpty())
    }

    @Test
    fun `an offset outside the Recording reads as a rejection`() {
        source.add(name(1), ByteArray(3))
        source.add(name(2), ByteArray(3))
        online()
        send(name(1), 4)
        assertEquals(listOf(name(1), name(2)), offered())
    }

    @Test
    fun `an answer for another Turn is ignored`() {
        source.add(name(1), ByteArray(3))
        online()
        stored(name(2))
        send(name(2), 0)
        assertTrue(ledger.names.isEmpty())
        assertEquals(1, sent.size)
    }

    @Test
    fun `a Turn that cannot be read is skipped, and the rest still go up`() {
        source.add(name(1), ByteArray(3))
        source.add(name(2), ByteArray(3))
        source.add(name(3), ByteArray(3))
        source.failing += name(1)
        online()
        storeEverything()
        assertEquals(listOf(name(2), name(3)), ledger.names)
        source.failing += name(3)
        source.failing -= name(1)
        val again = CorpusUploader(source, FakeLedger(), Runnable::run, send = { sent.add(it) }, pieceBytes = 4)
        sent.clear()
        again.status(HubStatus.Online(corpusWelcome))
        again.received(TurnAck(name(1), TurnAck.SEND, offset = 0))
        assertEquals(TurnAudio::class, sent.last()::class)
        source.failing += name(1)
        again.received(TurnAck(name(1), TurnAck.SEND, offset = 0))
        assertEquals(name(2), offered().last(), "a read failing mid-Turn skips to the next")
    }

    @Test
    fun `a send that fails stops the upload until the next welcome`() {
        source.add(name(1), ByteArray(3))
        sendOk = false
        online()
        sendOk = true
        uploader.published()
        executor.runAll()
        assertTrue(sent.isEmpty())
        online()
        assertEquals(listOf(name(1)), offered())
    }

    @Test
    fun `it counts what it stored this Session`() {
        source.add(name(1), ByteArray(3))
        source.add(name(2), ByteArray(3))
        online()
        storeEverything()
        assertEquals(2, uploader.stored)
    }
}
