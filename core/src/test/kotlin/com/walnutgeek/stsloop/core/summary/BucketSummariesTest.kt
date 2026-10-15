package com.walnutgeek.stsloop.core.summary

import com.walnutgeek.stsloop.core.UtcTimestamp
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.ListedTurn
import com.walnutgeek.stsloop.core.hub.Fixtures
import com.walnutgeek.stsloop.core.hub.Protocol
import com.walnutgeek.stsloop.core.hub.Message
import com.walnutgeek.stsloop.core.hub.Reply
import com.walnutgeek.stsloop.core.hub.Summarize
import com.walnutgeek.stsloop.core.hub.SummaryNote
import com.walnutgeek.stsloop.core.hub.HubRequests
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** "summarize bucket" (#76) for one Session: the hub when it can summarise, the phone alone otherwise. */
class BucketSummariesTest {
    private val now = UtcTimestamp.parse("2026-10-14T18:00:00.000Z")!!
    private fun name(minutesAgo: Int, id: String) = "${UtcTimestamp.format(now - minutesAgo * 60_000L)}-$id"
    private fun note(minutesAgo: Int, id: String, text: String, bucket: String = "errands") =
        ListedTurn(name(minutesAgo, id), kind = "note", bucket = bucket, content = text)

    private var corpus = listOf(
        note(50, "000001", "order roofing screws"),
        note(40, "000002", "pick up the dry cleaning."),
        note(30, "000003", "call the vet"),
        note(20, "000004", "return the library books"),
        note(10, "000005", "plan the week", bucket = "work"),
    )
    private val marks = SummaryMarks(null)
    private var summarising = true
    private var connected = true
    private val sent = mutableListOf<Message>()
    private val said = mutableListOf<String>()
    private val heard = mutableListOf<(Boolean) -> Unit>()
    private var clock = 0L
    private var ids = 0
    private val summaries = BucketSummaries(
        notes = { corpus.sortedByDescending { it.directoryName }.asSequence() },
        marks = marks,
        hubSummarises = { summarising },
        send = { m -> connected.also { if (it) sent += m } },
        say = { text, then -> said += text; heard += then },
        clockMs = { clock },
        wallClockMs = { now },
        newId = { "s${++ids}" },
    )

    private fun ask(bucket: String = "errands"): String? = summaries.summarize(summaries.range(bucket))

    @Test
    fun `with a hub that summarises, the Notes go to it and nothing is said yet`() {
        assertNull(ask())
        val s = sent.single() as Summarize
        assertEquals("s1", s.id)
        assertEquals("errands", s.bucket)
        assertNull(s.since)
        assertFalse(s.more)
        assertEquals(
            listOf("order roofing screws", "pick up the dry cleaning.", "call the vet", "return the library books"),
            s.notes.map(SummaryNote::text),
        )
        assertEquals(1, summaries.pending)
        assertTrue(said.isEmpty())
    }

    @Test
    fun `the hub's summary is said, and the mark moves only once it has been heard`() {
        ask()
        assertTrue(summaries.replied(Reply("s1", "Errands: four things.")))
        assertEquals(listOf("Errands: four things."), said)
        assertNull(marks.since("errands"), "not before it is spoken")
        heard.single()(true)
        assertEquals(name(20, "000004"), marks.since("errands"))
        assertEquals(0, summaries.pending)
    }

    @Test
    fun `a summary never heard (the Session ended first) leaves the mark where it was`() {
        ask()
        summaries.replied(Reply("s1", "Errands: four things."))
        heard.single()(false)
        assertNull(marks.since("errands"))
    }

    @Test
    fun `the next summary covers only the Notes said since the last one heard`() {
        ask()
        summaries.replied(Reply("s1", "Errands: four things."))
        heard.single()(true)
        corpus = corpus + note(5, "000006", "buy stamps")
        ask()
        val s = sent.last() as Summarize
        assertEquals(name(20, "000004"), s.since)
        assertEquals(listOf("buy stamps"), s.notes.map(SummaryNote::text))
    }

    @Test
    fun `the hub's fallback is said, but the Notes do not count as summarised`() {
        ask()
        summaries.replied(Reply("s1", "errands: 4 notes since last time; …", reason = Reply.FALLBACK))
        heard.single()(true)
        assertNull(marks.since("errands"))
    }

    @Test
    fun `a reply that is not a summary's is left for others`() {
        ask()
        assertFalse(summaries.replied(Reply("other", "status.")))
        assertEquals(1, summaries.pending)
    }

    @Test
    fun `hub offline, the phone reads the count and the latest three Notes itself`() {
        connected = false
        assertEquals(
            "errands: 4 notes in the last day; the latest three: pick up the dry cleaning. call the vet. return the library books.",
            ask(),
        )
        assertTrue(sent.isEmpty())
        assertNull(marks.since("errands"), "a raw reading is not a summary")
    }

    @Test
    fun `a hub without the summaries capability gets nothing, and the phone answers alone`() {
        summarising = false
        assertEquals("work: one note in the last day: plan the week.", ask("work"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `no answer within the timeout says so, then reads the Notes raw`() {
        ask()
        clock = BucketSummaries.REPLY_TIMEOUT_MS - 1
        summaries.tick()
        assertTrue(said.isEmpty())
        clock = BucketSummaries.REPLY_TIMEOUT_MS
        summaries.tick()
        assertEquals(
            listOf("${HubRequests.NO_ANSWER} errands: 4 notes in the last day; the latest three: pick up the dry cleaning. call the vet. return the library books."),
            said,
        )
        heard.single()(true)
        assertNull(marks.since("errands"))
        assertFalse(summaries.replied(Reply("s1", "too late.")), "a late reply is ignored")
    }

    @Test
    fun `nothing said since the last summary is said so, and nothing goes to the hub`() {
        marks.move("errands", name(20, "000004"))
        assertEquals("nothing new in errands since last time.", ask())
        assertTrue(sent.isEmpty())
        corpus = emptyList()
        assertEquals("no notes in work in the last day.", ask("work"))
    }

    @Test
    fun `the fallback counts the Notes it was not given, and says the Bucket in words`() {
        val r = NoteRange("house-project", since = "2026-10-13T00:00:00.000Z-000001", notes = listOf(SummaryNote(name(1, "000009"), "measure the hatch")), more = true)
        assertEquals("house project: more than 1 note since last time; the latest one: measure the hatch.", BucketSummaries.fallback(r))
        val two = r.copy(notes = r.notes + SummaryNote(name(0, "00000a"), "ask about permits?"), more = false)
        assertEquals("house project: 2 notes since last time: measure the hatch. ask about permits.", BucketSummaries.fallback(two))
        assertEquals("the inbox: one note in the last day: buy milk.", BucketSummaries.fallback(NoteRange("inbox", null, listOf(SummaryNote(name(0, "00000b"), "buy milk")), false)))
    }

    @Test
    fun `what the phone sends is the summarize fixture, and it reads the Notes raw as the hub's fallback does`() {
        val fixture = Protocol.decode(Fixtures.text("v1", "summarize/basic.json")) as Summarize
        corpus = fixture.notes.map { n -> ListedTurn(n.turn, kind = "note", bucket = "errands", content = n.text) } +
            ListedTurn(fixture.since!!, kind = "note", bucket = "errands", content = "summarised already")
        marks.move("errands", fixture.since!!)
        val at = UtcTimestamp.parse("2026-10-14T18:00:00.000Z")!!
        val phone = BucketSummaries(
            notes = { corpus.sortedByDescending { it.directoryName }.asSequence() }, marks = marks, hubSummarises = { true },
            send = { m -> sent += m; true }, say = { _, _ -> }, clockMs = { 0 }, wallClockMs = { at }, newId = { "b7e2d0" },
        )
        val range = phone.range("errands")!!
        assertNull(phone.summarize(range))
        assertEquals(Fixtures.json("v1", "summarize/basic.json"), Json.parse(Protocol.encode(sent.single())))
        val hubFallback = Protocol.decode(Fixtures.text("v1", "reply/summary-fallback.json")) as Reply
        assertEquals(hubFallback.say, BucketSummaries.fallback(range))
    }

    @Test
    fun `a Corpus that cannot be read is said, and nothing is sent`() {
        val broken = BucketSummaries(
            notes = { throw java.io.IOException("gone") }, marks = marks, hubSummarises = { true },
            send = { m -> sent += m; true }, say = { _, _ -> }, clockMs = { 0 }, wallClockMs = { now },
        )
        assertNull(broken.range("errands"))
        assertEquals(BucketSummaries.CANNOT_READ, broken.summarize(null))
        assertTrue(sent.isEmpty())
    }
}
