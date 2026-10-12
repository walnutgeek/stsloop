package com.walnutgeek.stsloop.core.recognisability

import com.walnutgeek.stsloop.core.speech.StreamingRecognizer

/** A TTS [voice] (the engine's name for it) at a speech [rate] (1.0 is normal). */
data class Variant(val voice: String, val rate: Float) {
    /** How the variant is written in a result: `voice@rate`. */
    val id: String get() = "$voice@$rate"
}

/** Speaks a phrase as audio: `[-1, 1)` samples at the check's rate, or null when this [Variant] cannot be made. */
fun interface PhraseSynth {
    fun synthesize(phrase: String, variant: Variant): FloatArray?
}

/**
 * The name-recognisability check's core (`docs/mvp.md`; Phase 0 findings,
 * point 4): speaks a [Probe] through a [PhraseSynth] in each [Variant] and
 * feeds each into a fresh stream of [recognizer], as the loop would hear the
 * phrase said alone between two Silences. The caller loads the recognizer,
 * owns the voice, and files what was heard ([NameCheckResults]).
 *
 * Not thread-safe, like the recognizer.
 */
class NameChecker(private val recognizer: StreamingRecognizer, private val sampleRate: Int) {
    private val lead = FloatArray(sampleRate / 1000 * LEAD_MS)
    private val trail = FloatArray(sampleRate / 1000 * TRAIL_MS)

    /**
     * What the recogniser heard for [probe] in each of [variants]; a variant
     * the voice cannot make is left out. Null when [stop] says so before a
     * variant (a Session is starting), so a half-checked phrase is never
     * filed, and when no variant could be made.
     */
    fun check(probe: Probe, variants: List<Variant>, synth: PhraseSynth, stop: () -> Boolean): List<Heard>? {
        val heard = mutableListOf<Heard>()
        for (v in variants) {
            if (stop()) return null
            val samples = synth.synthesize(probe.spoken, v) ?: continue
            heard += Heard(v.id, hear(samples))
        }
        return heard.ifEmpty { null }
    }

    /** The recogniser's text for [samples], with Silence before and after. */
    fun hear(samples: FloatArray): String {
        val stream = recognizer.open()
        try {
            stream.accept(lead)
            stream.accept(samples)
            stream.accept(trail)
            return stream.finish()
        } finally {
            stream.release()
        }
    }

    companion object {
        /** Silence before the phrase. */
        const val LEAD_MS = 500

        /** Silence after it, before the recognizer's own tail padding. */
        const val TRAIL_MS = 500

        /** Each voice speaks at each of these rates: a slow, a normal and a hurried speaker. */
        val RATES = listOf(0.8f, 1.0f, 1.25f)

        /** At most this many voices: each one more costs a third more of the check's time. */
        const val MAX_VOICES = 2

        /** The variants for [voices], the preferred first: each of the first [MAX_VOICES] at each of [RATES]. */
        fun variants(voices: List<String>): List<Variant> = voices.take(MAX_VOICES).flatMap { v -> RATES.map { Variant(v, it) } }
    }
}
