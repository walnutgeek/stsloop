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

    @Test
    fun `parse inverts format`() {
        for (ms in listOf(0L, 1_000, 951_782_400_005, 1_791_296_527_431, 4_107_542_399_999)) {
            assertEquals(ms, UtcTimestamp.parse(UtcTimestamp.format(ms)))
        }
    }

    @Test
    fun `parse rejects anything format would not write`() {
        val bad = listOf(
            "", "2026-10-06T14:22:07Z", "2026-10-06T14:22:07.43Z", "2026-02-30T00:00:00.000Z",
            "2026-10-06T24:00:00.000Z", "2026-13-01T00:00:00.000Z", "2026-10-06 14:22:07.431Z", "x2026-10-06T14:22:07.431Z",
        )
        for (s in bad) assertEquals(null, UtcTimestamp.parse(s), s)
    }

    @Test
    fun `a Turn directory name gives back its started_at and id`() {
        assertEquals(1_791_296_527_431L to "a3f1c9", parseTurnDirectoryName(turnDirectoryName(1_791_296_527_431, "a3f1c9")))
        assertEquals(null, parseTurnDirectoryName("2026-10-06T14:22:07.431Z-A3F1C9"))
        assertEquals(null, parseTurnDirectoryName("2026-10-06T14:22:07.431Z-"))
        assertEquals(null, parseTurnDirectoryName("sessions"))
        assertEquals(null, parseTurnDirectoryName("0f22ab"))
    }
}
