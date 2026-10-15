package com.walnutgeek.stsloop.core.summary

import com.walnutgeek.stsloop.core.UtcTimestamp
import com.walnutgeek.stsloop.core.corpus.ListedTurn
import com.walnutgeek.stsloop.core.hub.SummaryNote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The range rule of "summarize bucket" (#76), at the `:core` seam: which Notes a summary covers. */
class NoteRangeTest {
    private val now = UtcTimestamp.parse("2026-10-14T18:00:00.000Z")!!
    private val hour = 3_600_000L

    /** A Turn directory name [hoursAgo] before [now], with a distinct id. */
    private fun name(hoursAgo: Double, id: String) = "${UtcTimestamp.format(now - (hoursAgo * hour).toLong())}-$id"

    private fun note(hoursAgo: Double, id: String, bucket: String = "errands", content: String = "note $id") =
        ListedTurn(name(hoursAgo, id), kind = "note", bucket = bucket, content = content, transcript = content.uppercase())

    private fun scratch(hoursAgo: Double, id: String, target: String?) =
        ListedTurn(name(hoursAgo, id), kind = "command", command = "scratch_that", tombstones = target)

    private fun unclassified(hoursAgo: Double, id: String, transcript: String?) =
        ListedTurn(name(hoursAgo, id), kind = "unclassified", transcript = transcript)

    private fun range(turns: List<ListedTurn>, since: String? = null, bucket: String = "errands", cap: Int = NoteRange.CAP) =
        NoteRange.of(turns.sortedByDescending { it.directoryName }.asSequence(), bucket, since, now, cap)

    @Test
    fun `the first summary covers the bucket's Notes of the last 24 hours, oldest first`() {
        val r = range(listOf(note(30.0, "000001"), note(23.0, "000002"), note(2.0, "000003"), note(1.0, "000004", bucket = "work")))
        assertEquals(listOf(SummaryNote(name(23.0, "000002"), "note 000002"), SummaryNote(name(2.0, "000003"), "note 000003")), r.notes)
        assertEquals(null, r.since)
        assertFalse(r.more)
        assertEquals(name(2.0, "000003"), r.newest)
    }

    @Test
    fun `a later summary covers the Notes since the newest one the last summary covered, however old`() {
        val last = name(40.0, "000002")
        val r = range(listOf(note(50.0, "000001"), note(40.0, "000002"), note(39.0, "000003"), note(0.5, "000004")), since = last)
        assertEquals(listOf(name(39.0, "000003"), name(0.5, "000004")), r.notes.map { it.turn })
        assertEquals(last, r.since)
    }

    @Test
    fun `nothing since the last summary is an empty range`() {
        val r = range(listOf(note(3.0, "000001")), since = name(3.0, "000001"))
        assertTrue(r.notes.isEmpty())
        assertEquals(null, r.newest)
    }

    @Test
    fun `only the newest Notes up to the cap are covered, and more says some were left out`() {
        val turns = (1..7).map { note(it.toDouble(), "00000$it") }
        val r = range(turns, cap = 5)
        assertEquals((5 downTo 1).map { name(it.toDouble(), "00000$it") }, r.notes.map { it.turn })
        assertTrue(r.more)
        assertFalse(range(turns.take(5), cap = 5).more)
    }

    @Test
    fun `the cap applies to a later summary too`() {
        val turns = (1..7).map { note(it.toDouble(), "00000$it") }
        val r = range(turns, since = name(100.0, "000000"), cap = 3)
        assertEquals(3, r.notes.size)
        assertTrue(r.more)
    }

    @Test
    fun `the default cap is about fifty Notes`() {
        val turns = (1..60).map { note(it / 10.0, it.toString(16).padStart(6, '0')) }
        val r = range(turns)
        assertEquals(50, r.notes.size)
        assertTrue(r.more)
    }

    @Test
    fun `a tombstoned Note is left out, and does not count towards the cap`() {
        val dropped = note(2.0, "000002")
        val r = range(listOf(note(3.0, "000001"), dropped, scratch(1.9, "000003", dropped.directoryName), note(1.0, "000004")), cap = 2)
        assertEquals(listOf(name(3.0, "000001"), name(1.0, "000004")), r.notes.map { it.turn })
        assertFalse(r.more)
    }

    @Test
    fun `a scratch that with nothing to drop, or one that is not a command Turn, tombstones nothing`() {
        val n = note(2.0, "000001")
        val r = range(listOf(n, scratch(1.0, "000002", null), ListedTurn(name(0.5, "000003"), kind = "note", bucket = "work", content = "x", tombstones = n.directoryName)))
        assertEquals(listOf(n.directoryName), r.notes.map { it.turn })
    }

    @Test
    fun `a Note that says it was tombstoned (an old Turn's tombstoned_by) is left out too`() {
        val r = range(listOf(note(2.0, "000001").copy(tombstonedBy = name(1.0, "000002")), note(1.0, "000003")))
        assertEquals(listOf(name(1.0, "000003")), r.notes.map { it.turn })
    }

    @Test
    fun `Turns that are not the bucket's Notes are not covered`() {
        val r = range(
            listOf(
                note(5.0, "000001", bucket = "work"),
                ListedTurn(name(4.0, "000002"), kind = "command", command = "focus", focus = "errands"),
                ListedTurn(name(3.0, "000003"), kind = "draft", bucket = "errands", content = "a draft"),
                ListedTurn(name(2.0, "000004"), problem = "unreadable turn.json"),
                unclassified(1.0, "000005", "BUY MILK"),
            ),
        )
        assertTrue(r.notes.isEmpty())
    }

    @Test
    fun `a Note without content reads its transcript, and one with no words is left out`() {
        val r = range(
            listOf(
                ListedTurn(name(3.0, "000001"), kind = "note", bucket = "errands", transcript = "ERRANDS BUY MILK"),
                note(2.0, "000002", content = " "),
            ),
        )
        assertEquals(listOf(SummaryNote(name(3.0, "000001"), "errands buy milk")), r.notes)
    }

    @Test
    fun `the Inbox covers the unlabelled Turns with words, fillers aside`() {
        val r = range(
            listOf(
                unclassified(4.0, "000001", "BUY MILK"),
                unclassified(3.0, "000002", "UM"),
                unclassified(2.5, "000003", ""),
                unclassified(2.0, "000004", null),
                note(1.0, "000005"),
                ListedTurn(name(0.5, "000006"), kind = "command", command = "repeat", transcript = "REPEAT"),
            ),
            bucket = "inbox",
        )
        assertEquals(listOf(SummaryNote(name(4.0, "000001"), "buy milk")), r.notes)
    }

    @Test
    fun `reading stops at the start of the range`() {
        var read = 0
        val turns = (1..100).map { note(it.toDouble(), it.toString(16).padStart(6, '0')) }.sortedByDescending { it.directoryName }
        val r = NoteRange.of(turns.asSequence().onEach { read++ }, "errands", since = turns[3].directoryName, nowMs = now)
        assertEquals(3, r.notes.size)
        assertEquals(4, read)
    }
}
