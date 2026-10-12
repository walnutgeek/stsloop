package com.walnutgeek.stsloop.core.settings

import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.turn.Timings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimingsSettingsTest {
    private fun saved(e: Edit): String {
        assertTrue(e is Edit.Save, "expected a save, got $e")
        return (e as Edit.Save).text
    }

    private fun refused(e: Edit): String {
        assertTrue(e is Edit.Refused, "expected a refusal, got $e")
        return (e as Edit.Refused).problem
    }

    @Test
    fun `shows what a Session would use, and what the file has wrong`() {
        val s = TimingsSettings("""{"trailing_silence_ms": 900, "guard_ms": -1}""")
        assertEquals(900, s.timings.trailingSilenceMs)
        assertEquals(Timings().focusTimeoutMs, s.timings.focusTimeoutMs)
        assertEquals(listOf("guard_ms"), s.rejected.map { it.substringBefore(':') })
    }

    @Test
    fun `no file means the defaults`() {
        assertEquals(Timings(), TimingsSettings(null).timings)
    }

    @Test
    fun `an edit is written as a file the Session reads back`() {
        val text = saved(TimingsSettings(null).edit(trailingSilenceMs = 1200, focusTimeoutMs = 300_000))
        assertEquals(Timings(trailingSilenceMs = 1200, focusTimeoutMs = 300_000), Timings.parse(text).timings)
        assertEquals(emptyList<String>(), Timings.parse(text).rejected)
    }

    @Test
    fun `an edit keeps every other key of an adb-written file, rejected ones included`() {
        val adb = """{"guard_ms": 0, "speech_threshold": 0.6, "trailing_silence_ms": 900, "bogus": 1}"""
        val text = saved(TimingsSettings(adb).edit(focusTimeoutMs = 60_000))
        assertEquals(
            mapOf("guard_ms" to 0L, "speech_threshold" to 0.6, "trailing_silence_ms" to 900L, "bogus" to 1L, "focus_timeout_ms" to 60_000L),
            Json.parseObject(text),
        )
        assertEquals(Timings(guardMs = 0, speechThreshold = 0.6f, trailingSilenceMs = 900, focusTimeoutMs = 60_000), Timings.parse(text).timings)
    }

    @Test
    fun `an unchanged value is not written`() {
        val text = saved(TimingsSettings("{}").edit(trailingSilenceMs = Timings().trailingSilenceMs, focusTimeoutMs = 60_000))
        assertEquals(setOf("focus_timeout_ms"), Json.parseObject(text).keys)
    }

    @Test
    fun `a file that is not JSON is never overwritten, so an adb typo is not lost`() {
        assertTrue(refused(TimingsSettings("{not json").edit(trailingSilenceMs = 1000)).contains(Timings.FILE))
    }

    @Test
    fun `nothing changed writes nothing`() {
        assertEquals(Edit.Unchanged, TimingsSettings(null).edit(trailingSilenceMs = Timings().trailingSilenceMs))
        assertEquals(Edit.Unchanged, TimingsSettings("""{"guard_ms": 0}""").edit())
    }

    @Test
    fun `trailing silence on the screen is bounded to 0,3 to 10 seconds`() {
        saved(TimingsSettings(null).edit(trailingSilenceMs = 300))
        saved(TimingsSettings(null).edit(trailingSilenceMs = 10_000))
        assertTrue(refused(TimingsSettings(null).edit(trailingSilenceMs = 299)).contains("0.3"))
        assertTrue(refused(TimingsSettings(null).edit(trailingSilenceMs = 10_001)).contains("10"))
    }

    @Test
    fun `the focus timeout is bounded as the file is, 10 seconds to an hour`() {
        saved(TimingsSettings(null).edit(focusTimeoutMs = 10_000))
        saved(TimingsSettings(null).edit(focusTimeoutMs = 3_600_000))
        refused(TimingsSettings(null).edit(focusTimeoutMs = 9_999))
        refused(TimingsSettings(null).edit(focusTimeoutMs = 3_600_001))
    }

    @Test
    fun `an adb value outside the screen's bounds is left alone when it is not edited`() {
        val text = saved(TimingsSettings("""{"trailing_silence_ms": 100}""").edit(trailingSilenceMs = 100, focusTimeoutMs = 20_000))
        assertEquals(100, Timings.parse(text).timings.trailingSilenceMs)
    }

    @Test
    fun `seconds are read as typed, to the millisecond`() {
        assertEquals(1500, Seconds.parse("1.5"))
        assertEquals(1500, Seconds.parse(" 1.50 "))
        assertEquals(120_000, Seconds.parse("120"))
        assertEquals(250, Seconds.parse("0.25"))
        assertEquals(500, Seconds.parse(".5"))
        assertEquals(1500, Seconds.parse("1,5"), "a decimal comma, as many keyboards type it")
        assertNull(Seconds.parse(""))
        assertNull(Seconds.parse("-1"))
        assertNull(Seconds.parse("1.2345"))
        assertNull(Seconds.parse("abc"))
        assertNull(Seconds.parse("99999999999"))
    }

    @Test
    fun `seconds are shown without trailing zeros`() {
        assertEquals("1.5", Seconds.format(1500))
        assertEquals("120", Seconds.format(120_000))
        assertEquals("0.25", Seconds.format(250))
        assertEquals("1.001", Seconds.format(1001))
    }
}
