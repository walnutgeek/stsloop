package com.walnutgeek.stsloop.core.summary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SummaryMarksTest {
    @TempDir
    lateinit var files: File

    private val a = "2026-10-14T07:02:11.120Z-a3f1c9"
    private val b = "2026-10-14T09:00:00.000Z-000002"

    @Test
    fun `a Bucket never summarised has no mark`() {
        assertNull(SummaryMarks(files).since("errands"))
    }

    @Test
    fun `a mark is kept per Bucket, and survives a new instance`() {
        SummaryMarks(files).move("errands", a)
        SummaryMarks(files).move("work", b)
        val marks = SummaryMarks(files)
        assertEquals(a, marks.since("errands"))
        assertEquals(b, marks.since("work"))
        assertNull(marks.since("inbox"))
    }

    @Test
    fun `a mark only moves forward`() {
        val marks = SummaryMarks(files)
        marks.move("errands", b)
        marks.move("errands", a)
        assertEquals(b, marks.since("errands"))
        assertEquals(b, SummaryMarks(files).since("errands"))
    }

    @Test
    fun `an unreadable file reads as no marks, and the next move rewrites it`() {
        File(files, SummaryMarks.FILE).writeText("{ not json")
        val marks = SummaryMarks(files)
        assertNull(marks.since("errands"))
        marks.move("errands", a)
        assertEquals(a, SummaryMarks(files).since("errands"))
    }

    @Test
    fun `a value that is not a Turn directory name is ignored`() {
        File(files, SummaryMarks.FILE).writeText("""{"marks": {"errands": "yesterday", "work": "$a", "home": 3}}""")
        val marks = SummaryMarks(files)
        assertNull(marks.since("errands"))
        assertNull(marks.since("home"))
        assertEquals(a, marks.since("work"))
    }

    @Test
    fun `a mark that cannot be written does not move`() {
        val blocked = File(files, "not-a-dir").apply { writeText("a file where the directory should be") }
        val marks = SummaryMarks(blocked)
        assertThrows<java.io.IOException> { marks.move("errands", a) }
        assertNull(marks.since("errands"))
    }

    @Test
    fun `in memory, marks are kept for the instance only`() {
        val marks = SummaryMarks(null)
        marks.move("errands", a)
        assertEquals(a, marks.since("errands"))
    }
}
