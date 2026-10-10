package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonException
import kotlin.math.floor

/**
 * The loop's timings (`docs/mvp.md`, "Timings — all tunable, none final").
 * Read at Session start from a JSON file in app storage, so they can be tuned
 * without a rebuild; every key is optional and falls back to the default here.
 *
 * - [trailingSilenceMs]: Silence that closes a human Turn.
 * - [guardMs]: guard interval after a machine Turn (no machine Turns yet; parsed so the file is complete).
 * - [maxUtteranceMs]: a Turn's Recording is cut at this length; speech that runs on continues in the next Turn.
 * - [minUtteranceMs]: a Turn with less speech than this (coughs, door slams) is discarded.
 * - [preRollMs]: audio kept before the first speech window, so the VAD's onset latency does not clip the first syllable.
 * - [speechThreshold]: Silero probability at or above which a window starts speech.
 * - [releaseThreshold]: once capturing, a window at or above this still counts as speech (hysteresis).
 */
data class Timings(
    val trailingSilenceMs: Int = 1500,
    val guardMs: Int = 300,
    val maxUtteranceMs: Int = 60_000,
    val minUtteranceMs: Int = 300,
    val preRollMs: Int = 300,
    val speechThreshold: Float = 0.5f,
    val releaseThreshold: Float = 0.35f,
) {
    init {
        for (f in FIELDS) f.check(f.get(this).toDouble())?.let { throw IllegalArgumentException("${f.key}: $it") }
        require(releaseThreshold <= speechThreshold) {
            "release_threshold ($releaseThreshold) must not exceed speech_threshold ($speechThreshold)"
        }
        // A max cut must leave room for pre-roll plus enough speech to keep, or it
        // would cut before the onset or discard every runaway utterance.
        require(preRollMs.toLong() + minUtteranceMs < maxUtteranceMs) {
            "pre_roll_ms ($preRollMs) + min_utterance_ms ($minUtteranceMs) must be below max_utterance_ms ($maxUtteranceMs)"
        }
    }

    /** The file format [parse] reads: one flat object, every key present. */
    fun toJson(): String =
        FIELDS.joinToString(",\n", prefix = "{\n", postfix = "\n}\n") { "  \"${it.key}\": ${it.get(this)}" }

    /** The result of [parse]: the timings to use, and each rejected key as `"key: why"`. */
    data class Parsed(val timings: Timings, val rejected: List<String>)

    /** One key of the file; [check] judges a value on its own, cross-field rules live in `init`. */
    private class Field(val key: String, val get: (Timings) -> Number, val check: (Double) -> String?)

    companion object {
        /** File name in app storage (`files/timings.json`). */
        const val FILE = "timings.json"
        const val MAX_UTTERANCE_CAP_MS = 600_000
        const val MAX_UTTERANCE_FLOOR_MS = 1_000
        const val PRE_ROLL_CAP_MS = 5_000

        private fun ms(key: String, range: LongRange, get: (Timings) -> Int) = Field(key, get) { v ->
            when {
                v != floor(v) -> "must be a whole number of milliseconds, was $v"
                v < range.first || v > range.last -> "must be in ${range.first}..${range.last}, was ${v.toLong()}"
                else -> null
            }
        }

        private fun probability(key: String, get: (Timings) -> Float) = Field(key, get) { v ->
            if (v > 0.0 && v < 1.0) null else "must be strictly between 0 and 1, was $v"
        }

        private val FIELDS = listOf(
            ms("trailing_silence_ms", 1L..MAX_UTTERANCE_CAP_MS) { it.trailingSilenceMs },
            ms("guard_ms", 0L..60_000) { it.guardMs },
            ms("max_utterance_ms", MAX_UTTERANCE_FLOOR_MS.toLong()..MAX_UTTERANCE_CAP_MS) { it.maxUtteranceMs },
            ms("min_utterance_ms", 0L..MAX_UTTERANCE_CAP_MS) { it.minUtteranceMs },
            ms("pre_roll_ms", 0L..PRE_ROLL_CAP_MS) { it.preRollMs },
            probability("speech_threshold") { it.speechThreshold },
            probability("release_threshold") { it.releaseThreshold },
        )

        val KEYS: List<String> = FIELDS.map { it.key }

        /** Defaults overridden by [values] (key → already range-checked value). */
        private fun of(values: Map<String, Double>): Timings {
            val d = Timings()
            fun ms(key: String, default: Int) = values[key]?.toInt() ?: default
            fun p(key: String, default: Float) = values[key]?.toFloat() ?: default
            return Timings(
                trailingSilenceMs = ms("trailing_silence_ms", d.trailingSilenceMs),
                guardMs = ms("guard_ms", d.guardMs),
                maxUtteranceMs = ms("max_utterance_ms", d.maxUtteranceMs),
                minUtteranceMs = ms("min_utterance_ms", d.minUtteranceMs),
                preRollMs = ms("pre_roll_ms", d.preRollMs),
                speechThreshold = p("speech_threshold", d.speechThreshold),
                releaseThreshold = p("release_threshold", d.releaseThreshold),
            )
        }

        private fun tryOf(values: Map<String, Double>): Timings? = runCatching { of(values) }.getOrNull()

        /**
         * Parses a flat JSON object, falling back **per key**: an unknown key,
         * a non-number, or an out-of-range value is rejected and
         * that key keeps its default, while the other keys still apply. Keys that
         * are only valid together are accepted together; on a cross-field
         * conflict, the last-listed key whose removal resolves it is rejected.
         * A file that is not a strict JSON object (a duplicate key included)
         * yields all defaults.
         */
        fun parse(json: String): Parsed {
            val entries = try {
                Json.parseObject(json)
            } catch (e: JsonException) {
                return Parsed(Timings(), listOf("file: ${e.message}"))
            }
            val rejected = mutableListOf<String>()
            val accepted = LinkedHashMap<String, Double>()
            for ((key, raw) in entries) {
                val field = FIELDS.firstOrNull { it.key == key }
                val value = (raw as? Number)?.toDouble()
                val problem = when {
                    field == null -> "unknown key; known keys: $KEYS"
                    value == null -> "must be a number, was $raw"
                    else -> field.check(value)
                }
                if (problem == null) accepted[key] = value!! else rejected += "$key: $problem"
            }
            while (true) {
                tryOf(accepted)?.let { return Parsed(it, rejected) }
                val why = runCatching { of(accepted) }.exceptionOrNull()?.message
                val culprit = accepted.keys.reversed().firstOrNull { tryOf(accepted - it) != null } ?: accepted.keys.last()
                rejected += "$culprit: conflicts with the other timings ($why)"
                accepted.remove(culprit)
            }
        }
    }
}
