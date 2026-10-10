package com.walnutgeek.stsloop.core

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdsTest {
    @Test
    fun `ids are six lowercase hex digits`() {
        val random = Random(42)
        repeat(1000) {
            val id = Ids.next(random)
            assertTrue(Regex("[0-9a-f]{6}").matches(id), id)
        }
    }

    @Test
    fun `ids keep leading zeros`() {
        val zeros = object : Random() {
            override fun nextBits(bitCount: Int) = 0
        }
        assertEquals("000000", Ids.next(zeros))
    }
}
