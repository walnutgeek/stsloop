package com.walnutgeek.stsloop.core.turn

import kotlin.math.floor

/**
 * The loop's timings (`docs/mvp.md`, "Timings — all tunable, none final").
 * Read at Session start from a JSON file in app storage, so they can be tuned
 * without a rebuild; every key is optional and falls back to the default here.
 *
 * - [trailingSilenceMs]: Silence that closes a human Turn.
 * - [guardMs]: guard interval after a machine Turn (no machine Turns yet; parsed so the file is complete).
 * - [maxUtteranceMs]: a Turn's Recording is closed at this length, however long the speech runs.
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
        require(trailingSilenceMs > 0) { "trailing_silence_ms must be > 0, was $trailingSilenceMs" }
        require(guardMs >= 0) { "guard_ms must be >= 0, was $guardMs" }
        require(minUtteranceMs >= 0) { "min_utterance_ms must be >= 0, was $minUtteranceMs" }
        require(maxUtteranceMs in 1..MAX_UTTERANCE_CAP_MS) {
            "max_utterance_ms must be in 1..$MAX_UTTERANCE_CAP_MS, was $maxUtteranceMs"
        }
        require(minUtteranceMs < maxUtteranceMs) {
            "min_utterance_ms ($minUtteranceMs) must be below max_utterance_ms ($maxUtteranceMs)"
        }
        require(preRollMs in 0..PRE_ROLL_CAP_MS) { "pre_roll_ms must be in 0..$PRE_ROLL_CAP_MS, was $preRollMs" }
        require(speechThreshold > 0f && speechThreshold < 1f) { "speech_threshold must be in (0, 1), was $speechThreshold" }
        require(releaseThreshold > 0f && releaseThreshold <= speechThreshold) {
            "release_threshold must be in (0, speech_threshold], was $releaseThreshold"
        }
    }

    /** The file format [parse] reads: one flat object, every key present. */
    fun toJson(): String = buildString {
        append("{\n")
        append("  \"trailing_silence_ms\": ").append(trailingSilenceMs).append(",\n")
        append("  \"guard_ms\": ").append(guardMs).append(",\n")
        append("  \"max_utterance_ms\": ").append(maxUtteranceMs).append(",\n")
        append("  \"min_utterance_ms\": ").append(minUtteranceMs).append(",\n")
        append("  \"pre_roll_ms\": ").append(preRollMs).append(",\n")
        append("  \"speech_threshold\": ").append(speechThreshold).append(",\n")
        append("  \"release_threshold\": ").append(releaseThreshold).append("\n")
        append("}\n")
    }

    companion object {
        /** File name in app storage (`files/timings.json`). */
        const val FILE = "timings.json"
        const val MAX_UTTERANCE_CAP_MS = 600_000
        const val PRE_ROLL_CAP_MS = 5_000

        val KEYS = listOf(
            "trailing_silence_ms", "guard_ms", "max_utterance_ms", "min_utterance_ms",
            "pre_roll_ms", "speech_threshold", "release_threshold",
        )

        /**
         * Parses a flat JSON object of numbers. Unknown or duplicate keys,
         * non-numbers and out-of-range values throw [IllegalArgumentException]
         * naming the problem, so a typo is never silently ignored.
         */
        fun parse(json: String): Timings {
            val fields = FlatJson(json).parseObject()
            for (key in fields.keys) require(key in KEYS) { "unknown key \"$key\"; known keys: $KEYS" }
            val d = Timings()
            return Timings(
                trailingSilenceMs = fields.ms("trailing_silence_ms") ?: d.trailingSilenceMs,
                guardMs = fields.ms("guard_ms") ?: d.guardMs,
                maxUtteranceMs = fields.ms("max_utterance_ms") ?: d.maxUtteranceMs,
                minUtteranceMs = fields.ms("min_utterance_ms") ?: d.minUtteranceMs,
                preRollMs = fields.ms("pre_roll_ms") ?: d.preRollMs,
                speechThreshold = fields["speech_threshold"]?.toFloat() ?: d.speechThreshold,
                releaseThreshold = fields["release_threshold"]?.toFloat() ?: d.releaseThreshold,
            )
        }

        private fun Map<String, Double>.ms(key: String): Int? {
            val v = this[key] ?: return null
            require(v == floor(v) && v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) {
                "$key must be a whole number of milliseconds, was $v"
            }
            return v.toInt()
        }
    }
}

/** The subset of JSON [Timings] needs: one object whose values are all numbers. */
private class FlatJson(private val s: String) {
    private var i = 0

    fun parseObject(): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        ws()
        expect('{')
        ws()
        if (peek() == '}') {
            i++
        } else {
            while (true) {
                ws()
                val key = string()
                require(key !in out) { "duplicate key \"$key\"" }
                ws()
                expect(':')
                ws()
                out[key] = number(key)
                ws()
                when (next()) {
                    ',' -> continue
                    '}' -> break
                    else -> fail("expected ',' or '}'")
                }
            }
        }
        ws()
        if (i != s.length) fail("unexpected trailing content")
        return out
    }

    private fun string(): String {
        expect('"')
        val start = i
        while (i < s.length && s[i] != '"') {
            if (s[i] == '\\') fail("escapes are not supported in keys")
            i++
        }
        if (i >= s.length) fail("unterminated string")
        return s.substring(start, i++)
    }

    private fun number(key: String): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val text = s.substring(start, i)
        return text.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: fail("value of \"$key\" must be a number, found '${if (text.isEmpty()) peek() ?: "end" else text}'")
    }

    private fun ws() {
        while (i < s.length && s[i] in " \t\r\n") i++
    }

    private fun peek(): Char? = s.getOrNull(i)

    private fun next(): Char? = s.getOrNull(i++)

    private fun expect(c: Char) {
        if (next() != c) fail("expected '$c'")
    }

    private fun fail(why: String): Nothing = throw IllegalArgumentException("timings: $why at offset ${i.coerceAtMost(s.length)}")
}
