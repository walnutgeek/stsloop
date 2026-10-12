package com.walnutgeek.stsloop.core.recognisability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerdictTest {
    private fun heard(vararg texts: String) = texts.mapIndexed { i, t -> Heard("v$i", t) }

    @Test
    fun `a transcript is the phrase when its words are, as the grammar reads them`() {
        assertTrue(Verdict.isPhrase("house project", "House project."))
        assertTrue(Verdict.isPhrase("house project", "HOUSEPROJECT"), "run together, as the grammar also hears it")
        assertTrue(Verdict.isPhrase("errands", "Um, errands."), "fillers around it, as the grammar skips them")
        assertFalse(Verdict.isPhrase("errands", "Errants."))
        assertFalse(Verdict.isPhrase("errands", "errands please"), "anything more is not the phrase alone")
        assertFalse(Verdict.isPhrase("stsloop", ""))
    }

    @Test
    fun `a transcript that is another phrase of the same Bucket counts, as the grammar would focus it all the same`() {
        val v = Verdict("worklog", heard("Work log.", "worklog"), also = listOf("work", "work log"))
        assertEquals(Level.HEARD, v.level)
        assertEquals(Level.SOMETIMES, Verdict("worklog", heard("Work log.", "worklog")).level)
    }

    @Test
    fun `heard in every variant is heard`() {
        val v = Verdict("errands", heard("Errands.", "errands", "ERRANDS"))
        assertEquals(Level.HEARD, v.level)
        assertEquals(3, v.passes)
        assertFalse(v.flagged)
        assertEquals(emptyList<String>(), v.misheardAs)
    }

    @Test
    fun `heard in some variants is flagged as sometimes misheard`() {
        val v = Verdict("errands", heard("Errands.", "Errants.", "errands"))
        assertEquals(Level.SOMETIMES, v.level)
        assertTrue(v.flagged)
        assertEquals(listOf("Errants."), v.misheardAs)
    }

    @Test
    fun `heard in none is flagged as misheard, each mishearing listed once`() {
        val v = Verdict("stsloop", heard("St. Loop.", "", "St. Loop."))
        assertEquals(Level.MISHEARD, v.level)
        assertTrue(v.flagged)
        assertEquals(listOf("St. Loop.", ""), v.misheardAs)
    }
}
