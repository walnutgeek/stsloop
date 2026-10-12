package com.walnutgeek.stsloop.core.recognisability

import com.walnutgeek.stsloop.core.speech.RecognitionStream
import com.walnutgeek.stsloop.core.speech.StreamingRecognizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NameCheckerTest {
    private val rate = 16_000

    /** "Hears" whatever was synthesised: the samples encode the text's index in [said]. */
    private class Echoing(val said: MutableList<String>) : StreamingRecognizer {
        val fed = mutableListOf<FloatArray>()
        var released = 0
        override val engine = "fake"
        override val model = "fake"
        override fun open(): RecognitionStream = object : RecognitionStream {
            private val got = mutableListOf<Float>()
            override fun accept(samples: FloatArray) {
                fed += samples
                got += samples.toList()
            }
            override fun finish(): String = got.firstOrNull { it != 0f }?.let { said[it.toInt() - 1] } ?: ""
            override fun release() { released++ }
        }
        override fun close() {}
    }

    private val said = mutableListOf<String>()
    private val stt = Echoing(said)

    /** Synthesises "<phrase> at <variant>" as a run of one non-zero sample value. */
    private val synth = PhraseSynth { phrase, variant ->
        said += "$phrase at ${variant.id}"
        FloatArray(rate / 2) { said.size.toFloat() }
    }

    @Test
    fun `each variant is spoken into a fresh stream between Silences, and what it heard is kept`() {
        val heard = NameChecker(stt, rate).check(Probes.probe("errands")!!, listOf(Variant("a", 1.0f), Variant("b", 0.8f)), synth) { false }
        assertEquals(listOf(Heard("a@1.0", "errands at a@1.0"), Heard("b@0.8", "errands at b@0.8")), heard)
        assertEquals(2, stt.released)
        // Silence, the phrase, Silence: as an utterance reaches the recogniser between Silences.
        assertEquals(listOf(NameChecker.LEAD_MS, 500, NameChecker.TRAIL_MS, NameChecker.LEAD_MS, 500, NameChecker.TRAIL_MS), stt.fed.map { it.size * 1000 / rate })
    }

    @Test
    fun `the variants are each voice at each rate, up to two voices`() {
        assertEquals(
            listOf("x@0.8", "x@1.0", "x@1.25", "y@0.8", "y@1.0", "y@1.25"),
            NameChecker.variants(listOf("x", "y", "z")).map { it.id },
        )
    }

    @Test
    fun `told to stop between variants, it gives up the phrase so nothing half-checked is kept`() {
        var calls = 0
        val heard = NameChecker(stt, rate).check(Probes.probe("errands")!!, listOf(Variant("a", 1f), Variant("b", 1f), Variant("c", 1f)), synth) { calls++ >= 1 }
        assertNull(heard)
        assertEquals(1, said.size)
    }

    @Test
    fun `a variant the voice cannot make is skipped, and none at all is no result`() {
        val two = listOf(Variant("a", 1f), Variant("b", 1f))
        val some = PhraseSynth { p, v -> if (v.voice == "b") null else synth.synthesize(p, v) }
        assertEquals(listOf("a@1.0"), NameChecker(stt, rate).check(Probes.probe("work")!!, two, some) { false }!!.map { it.variant })
        assertNull(NameChecker(stt, rate).check(Probes.probe("work")!!, two, { _, _ -> null }) { false })
    }
}
