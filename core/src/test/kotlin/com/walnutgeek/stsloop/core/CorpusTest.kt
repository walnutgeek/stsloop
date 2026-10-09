package com.walnutgeek.stsloop.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CorpusTest {
    @Test
    fun `corpus schema starts at 1`() {
        assertEquals(1, CORPUS_SCHEMA)
    }
}
