package com.walnutgeek.stsloop.core.turn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TimingsTest {
    private fun ok(json: String): Timings = Timings.parse(json).also {
        assertEquals(emptyList<String>(), it.rejected, "unexpected rejections")
    }.timings

    /** Parses [json], expecting exactly the keys [rejected] to be refused; returns the result. */
    private fun rejecting(json: String, vararg rejected: String): Timings {
        val p = Timings.parse(json)
        assertEquals(rejected.toList(), p.rejected.map { it.substringBefore(':') }, p.rejected.toString())
        return p.timings
    }

    // --- defaults and overrides ---

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
        assertEquals(Timings(), ok("{}"))
    }

    @Test
    fun `whitespace and newlines are allowed anywhere`() {
        assertEquals(Timings(), ok("\n  {\r\n\t}\n"))
    }

    @Test
    fun `each key overrides only itself`() {
        assertEquals(Timings(trailingSilenceMs = 900), ok("""{"trailing_silence_ms": 900}"""))
        assertEquals(Timings(guardMs = 0), ok("""{"guard_ms": 0}"""))
        assertEquals(Timings(maxUtteranceMs = 30_000), ok("""{"max_utterance_ms": 30000}"""))
        assertEquals(Timings(minUtteranceMs = 450), ok("""{"min_utterance_ms": 450}"""))
        assertEquals(Timings(preRollMs = 0), ok("""{"pre_roll_ms": 0}"""))
        assertEquals(Timings(speechThreshold = 0.6f), ok("""{"speech_threshold": 0.6}"""))
        assertEquals(Timings(releaseThreshold = 0.2f), ok("""{"release_threshold": 0.2}"""))
    }

    @Test
    fun `several keys together`() {
        assertEquals(
            Timings(trailingSilenceMs = 2000, minUtteranceMs = 500),
            ok("""{ "trailing_silence_ms": 2000, "min_utterance_ms": 500 }"""),
        )
    }

    @Test
    fun `keys that are only valid together are accepted together`() {
        // speech 0.3 alone would sit below the default release 0.35.
        assertEquals(Timings(speechThreshold = 0.3f, releaseThreshold = 0.2f), ok("""{"speech_threshold": 0.3, "release_threshold": 0.2}"""))
        // min 70 s alone would exceed the default max of 60 s.
        assertEquals(Timings(minUtteranceMs = 70_000, maxUtteranceMs = 120_000), ok("""{"min_utterance_ms": 70000, "max_utterance_ms": 120000}"""))
    }

    @Test
    fun `toJson round-trips`() {
        val t = Timings(trailingSilenceMs = 1234, speechThreshold = 0.55f, releaseThreshold = 0.4f)
        assertEquals(t, ok(t.toJson()))
        assertEquals(Timings(), ok(Timings().toJson()))
    }

    @Test
    fun `toJson names every key`() {
        val json = Timings().toJson()
        for (key in Timings.KEYS) assertTrue(json.contains("\"$key\""), "$key missing from $json")
        assertEquals(7, Timings.KEYS.size)
    }

    @Test
    fun `exponent notation is accepted for thresholds`() {
        assertEquals(0.5f, ok("""{"speech_threshold": 5e-1}""").speechThreshold)
    }

    // --- per-key fallback ---

    @Test
    fun `an unknown key is rejected by name and the rest still apply`() {
        val t = rejecting("""{"trailing_silense_ms": 900, "min_utterance_ms": 450}""", "trailing_silense_ms")
        assertEquals(Timings(minUtteranceMs = 450), t)
    }

    @Test
    fun `a string value rejects only that key`() {
        assertEquals(Timings(guardMs = 100), rejecting("""{"min_utterance_ms": "300", "guard_ms": 100}""", "min_utterance_ms"))
    }

    @Test
    fun `true, false and null values reject only their key`() {
        rejecting("""{"guard_ms": true, "pre_roll_ms": false, "min_utterance_ms": null}""", "guard_ms", "pre_roll_ms", "min_utterance_ms")
    }

    @Test
    fun `a duplicate key is rejected and keeps its default`() {
        assertEquals(Timings(preRollMs = 10), rejecting("""{"guard_ms": 1, "pre_roll_ms": 10, "guard_ms": 2}""", "guard_ms"))
    }

    @Test
    fun `a fractional millisecond value is rejected`() {
        assertEquals(Timings(), rejecting("""{"guard_ms": 300.5}""", "guard_ms"))
    }

    @Test
    fun `a negative duration is rejected`() {
        rejecting("""{"guard_ms": -1}""", "guard_ms")
    }

    @Test
    fun `a huge integer is rejected rather than overflowing`() {
        rejecting("""{"guard_ms": 99999999999999999999}""", "guard_ms")
    }

    @Test
    fun `zero trailing silence is rejected`() {
        rejecting("""{"trailing_silence_ms": 0}""", "trailing_silence_ms")
    }

    @Test
    fun `max is between one second and ten minutes`() {
        rejecting("""{"max_utterance_ms": 600001}""", "max_utterance_ms")
        rejecting("""{"max_utterance_ms": 999}""", "max_utterance_ms")
        assertEquals(600_000, ok("""{"max_utterance_ms": 600000}""").maxUtteranceMs)
    }

    @Test
    fun `pre-roll is capped at five seconds`() {
        rejecting("""{"pre_roll_ms": 5001}""", "pre_roll_ms")
    }

    @Test
    fun `thresholds must be strictly between 0 and 1`() {
        rejecting("""{"speech_threshold": 0}""", "speech_threshold")
        rejecting("""{"speech_threshold": 1}""", "speech_threshold")
        rejecting("""{"release_threshold": 0}""", "release_threshold")
    }

    @Test
    fun `each rejection says why`() {
        val why = Timings.parse("""{"guard_ms": -1}""").rejected.single()
        assertTrue(why.startsWith("guard_ms: ") && why.contains("-1"), why)
    }

    // --- cross-field invariants ---

    @Test
    fun `release threshold above speech threshold rejects the later key`() {
        val t = rejecting("""{"speech_threshold": 0.4, "release_threshold": 0.45}""", "release_threshold")
        assertEquals(Timings(speechThreshold = 0.4f, releaseThreshold = 0.35f), t)
        assertEquals(0.4f, ok("""{"speech_threshold": 0.4, "release_threshold": 0.4}""").releaseThreshold)
    }

    @Test
    fun `min at or above max rejects the later key`() {
        assertEquals(Timings(minUtteranceMs = 5000), rejecting("""{"min_utterance_ms": 5000, "max_utterance_ms": 5000}""", "max_utterance_ms"))
    }

    @Test
    fun `pre-roll must be shorter than max`() {
        assertThrows<IllegalArgumentException> { Timings(maxUtteranceMs = 3000, preRollMs = 3000, minUtteranceMs = 0) }
    }

    @Test
    fun `pre-roll plus min must fit inside max, so a max cut can keep its speech`() {
        assertThrows<IllegalArgumentException> { Timings(maxUtteranceMs = 2000, preRollMs = 1000, minUtteranceMs = 1000) }
        Timings(maxUtteranceMs = 2000, preRollMs = 1000, minUtteranceMs = 999)
        rejecting("""{"max_utterance_ms": 4000, "pre_roll_ms": 3000, "min_utterance_ms": 1000}""", "min_utterance_ms")
    }

    @Test
    fun `a cross-field conflict with defaults rejects the offending key alone`() {
        // min 70 s cannot fit in the default 60 s max: min is dropped, guard kept.
        assertEquals(Timings(guardMs = 50), rejecting("""{"min_utterance_ms": 70000, "guard_ms": 50}""", "min_utterance_ms"))
    }

    @Test
    fun `the constructor refuses what parse rejects`() {
        assertThrows<IllegalArgumentException> { Timings(trailingSilenceMs = 0) }
        assertThrows<IllegalArgumentException> { Timings(speechThreshold = 0.4f, releaseThreshold = 0.45f) }
        assertThrows<IllegalArgumentException> { Timings(maxUtteranceMs = 999) }
    }

    // --- malformed files ---

    @Test
    fun `malformed json falls back to all defaults with one rejection`() {
        for (bad in listOf("", "[]", "{", """{"guard_ms" 300}""", """{"guard_ms": 300,}""", """{"guard_ms": 300} trailing""", """{guard_ms: 300}""")) {
            val p = Timings.parse(bad)
            assertEquals(Timings(), p.timings, bad)
            assertEquals(1, p.rejected.size, bad)
            assertTrue(p.rejected.single().startsWith("file: "), p.rejected.single())
        }
    }
}
