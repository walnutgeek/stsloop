package com.walnutgeek.stsloop.core.recognisability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NameCheckStoreTest {
    @TempDir
    lateinit var files: File

    @Test
    fun `no file is no results, and nothing is written for no change`() {
        val s = NameCheckStore(files)
        assertEquals(NameCheckResults.EMPTY, s.read())
        s.update { it }
        assertFalse(File(files, NameCheckResults.FILE).exists())
    }

    @Test
    fun `each update re-reads the file, so a result and an announcement both survive`() {
        val check = NameCheckStore(files)
        val session = NameCheckStore(files)
        check.update { it.with("stsloop", "m", listOf(Heard("v", "St. Loop.")), 1L) }
        session.update { it.announced(listOf("stsloop"), "m") }
        check.update { it.with("errands", "m", listOf(Heard("v", "errands")), 2L) }
        val r = NameCheckStore(files).read()
        assertEquals(setOf(NameCheckResults.Announced("stsloop", "m")), r.announced)
        assertEquals(listOf("stsloop", "errands"), r.results.map { it.phrase })
    }

    @Test
    fun `an unreadable file reads as no results, and is replaced by the next update`() {
        File(files, NameCheckResults.FILE).writeText("{oops")
        val s = NameCheckStore(files)
        assertEquals(NameCheckResults.EMPTY, s.read())
        s.update { it.announced(listOf("work"), "m") }
        assertEquals(setOf(NameCheckResults.Announced("work", "m")), s.read().announced)
    }
}
