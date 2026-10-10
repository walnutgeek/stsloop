package com.walnutgeek.stsloop.core.turn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TimingsTest {
    private fun refused(json: String): String =
        assertThrows<IllegalArgumentException> { Timings.parse(json) }.message!!

    @Test
    fun `defaults are the mvp starting points`() {
        val t = Timings()
        assertEquals(1500, t.trailingSilenceMs)
        assertEquals(300, t.guardMs)
        assertEquals(60_000, t.maxUtteranceMs)
        assertEquals(300, t.minUtteranceMs)
        assertEquals(300, t.preRollMs)
        assertEquals(0.5f, t.speechThreshold)
        assertEquals(0.35f, t.releaseThreshold)
    }

    @Test
    fun `an empty object means all defaults`() {
        assertEquals(Timings(), Timings.parse("{}"))
    }

    @Test
    fun `whitespace and newlines are allowed anywhere`() {
        assertEquals(Timings(), Timings.parse("\n  {\r\n\t}\n"))
    }

    @Test
    fun `each key overrides only itself`() {
        assertEquals(Timings(trailingSilenceMs = 900), Timings.parse("""{"trailing_silence_ms": 900}"""))
        assertEquals(Timings(guardMs = 0), Timings.parse("""{"guard_ms": 0}"""))
        assertEquals(Timings(maxUtteranceMs = 30_000), Timings.parse("""{"max_utterance_ms": 30000}"""))
        assertEquals(Timings(minUtteranceMs = 450), Timings.parse("""{"min_utterance_ms": 450}"""))
        assertEquals(Timings(preRollMs = 0), Timings.parse("""{"pre_roll_ms": 0}"""))
        assertEquals(Timings(speechThreshold = 0.6f), Timings.parse("""{"speech_threshold": 0.6}"""))
        assertEquals(Timings(releaseThreshold = 0.2f), Timings.parse("""{"release_threshold": 0.2}"""))
    }

    @Test
    fun `several keys together`() {
        assertEquals(
            Timings(trailingSilenceMs = 2000, minUtteranceMs = 500),
            Timings.parse("""{ "trailing_silence_ms": 2000, "min_utterance_ms": 500 }"""),
        )
    }

    @Test
    fun `toJson round-trips`() {
        val t = Timings(trailingSilenceMs = 1234, speechThreshold = 0.55f, releaseThreshold = 0.4f)
        assertEquals(t, Timings.parse(t.toJson()))
        assertEquals(Timings(), Timings.parse(Timings().toJson()))
    }

    @Test
    fun `toJson names every key`() {
        val json = Timings().toJson()
        for (key in Timings.KEYS) assertTrue(json.contains("\"$key\""), "$key missing from $json")
    }

    @Test
    fun `an unknown key is refused by name, so a typo is not silently ignored`() {
        assertTrue(refused("""{"trailing_silense_ms": 900}""").contains("trailing_silense_ms"))
    }

    @Test
    fun `a duplicate key is refused`() {
        assertTrue(refused("""{"guard_ms": 1, "guard_ms": 2}""").contains("guard_ms"))
    }

    @Test
    fun `a string value is refused`() {
        refused("""{"guard_ms": "300"}""")
    }

    @Test
    fun `a fractional millisecond value is refused`() {
        assertTrue(refused("""{"guard_ms": 300.5}""").contains("guard_ms"))
    }

    @Test
    fun `a negative duration is refused`() {
        refused("""{"guard_ms": -1}""")
    }

    @Test
    fun `zero trailing silence is refused`() {
        refused("""{"trailing_silence_ms": 0}""")
    }

    @Test
    fun `min must be below max`() {
        refused("""{"min_utterance_ms": 5000, "max_utterance_ms": 5000}""")
    }

    @Test
    fun `max is capped at ten minutes`() {
        refused("""{"max_utterance_ms": 600001}""")
        assertEquals(600_000, Timings.parse("""{"max_utterance_ms": 600000}""").maxUtteranceMs)
    }

    @Test
    fun `thresholds must be strictly between 0 and 1`() {
        refused("""{"speech_threshold": 0}""")
        refused("""{"speech_threshold": 1}""")
        refused("""{"release_threshold": 0, "speech_threshold": 0.5}""")
    }

    @Test
    fun `release threshold may not exceed speech threshold`() {
        refused("""{"speech_threshold": 0.4, "release_threshold": 0.45}""")
        assertEquals(0.4f, Timings.parse("""{"speech_threshold": 0.4, "release_threshold": 0.4}""").releaseThreshold)
    }

    @Test
    fun `exponent notation is accepted for thresholds`() {
        assertEquals(0.5f, Timings.parse("""{"speech_threshold": 5e-1}""").speechThreshold)
    }

    @Test
    fun `malformed json is refused`() {
        refused("")
        refused("[]")
        refused("{")
        refused("""{"guard_ms" 300}""")
        refused("""{"guard_ms": 300,}""")
        refused("""{"guard_ms": 300} trailing""")
        refused("""{guard_ms: 300}""")
    }

    @Test
    fun `a huge integer is refused rather than overflowing`() {
        refused("""{"guard_ms": 99999999999999999999}""")
    }
}
