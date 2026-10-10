package com.walnutgeek.stsloop.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UtcTimestampTest {
    @Test
    fun epoch() = assertEquals("1970-01-01T00:00:00.000Z", UtcTimestamp.format(0))

    @Test
    fun `always three millisecond digits`() =
        assertEquals("2026-10-06T14:22:07.431Z", UtcTimestamp.format(1_791_296_527_431))

    @Test
    fun `leap day`() = assertEquals("2000-02-29T00:00:00.005Z", UtcTimestamp.format(951_782_400_005))

    @Test
    fun `century non-leap year`() =
        assertEquals("2100-02-28T23:59:59.999Z", UtcTimestamp.format(4_107_542_399_999))
}
