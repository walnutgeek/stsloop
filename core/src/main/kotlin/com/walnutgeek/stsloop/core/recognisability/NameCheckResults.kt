package com.walnutgeek.stsloop.core.recognisability

import com.walnutgeek.stsloop.core.UtcTimestamp
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonException
import com.walnutgeek.stsloop.core.corpus.JsonWriter

/**
 * What the name-recognisability check has heard, and which flagged phrases
 * the loop has already announced. Kept in `files/recognisability.json`, so a
 * phrase is checked once per recogniser model and announced once ever:
 * ```json
 * { "schema": 1,
 *   "results": [ { "phrase": "stsloop", "model": "…/int8 greedy_search",
 *                  "heard": [ { "variant": "en-us-x-iol-local@1.0", "text": "St. Loop." } ],
 *                  "checked_at": "2026-10-12T10:00:00.000Z" } ],
 *   "announced": [ "stsloop" ] }
 * ```
 * The file keeps what was heard, not a verdict, so the policy ([Verdict]) can
 * change without checking again. A result is filed by its phrase's
 * [Probes.key] and the model (`transcript.model`): a new model checks every
 * phrase again. Immutable; each change returns a new value.
 */
data class NameCheckResults(val results: List<Result>, val announcedPhrases: Set<String>) {
    /** What [model] heard for [phrase] (a [Probes.key]), in each variant, at [checkedAtMs]. */
    data class Result(val phrase: String, val model: String, val heard: List<Heard>, val checkedAtMs: Long)

    /** The judgment of [phrase] (as written anywhere) on [model], or null when it has not been checked there. */
    fun verdict(phrase: String, model: String): Verdict? {
        val key = Probes.key(phrase)
        return results.firstOrNull { it.phrase == key && it.model == model }?.let { Verdict(key, it.heard) }
    }

    /**
     * The judgment of [probe] on [model], counting a transcript that is
     * another phrase of the same Bucket or command among [probes] as heard.
     */
    fun verdict(probe: Probe, probes: List<Probe>, model: String): Verdict? = verdict(probe.key, model)?.let { v ->
        v.copy(also = probes.filter { it.kind == probe.kind && it.owner == probe.owner && it.key != probe.key }.map { it.key })
    }

    /** The [probes] not yet checked on [model], in order. */
    fun pending(probes: List<Probe>, model: String): List<Probe> = probes.filter { verdict(it.key, model) == null }

    /** With [heard] filed for [phrase] on [model], replacing any earlier result for both. */
    fun with(phrase: String, model: String, heard: List<Heard>, checkedAtMs: Long): NameCheckResults {
        val key = Probes.key(phrase)
        val result = Result(key, model, heard, checkedAtMs)
        val at = results.indexOfFirst { it.phrase == key && it.model == model }
        return copy(results = if (at < 0) results + result else results.toMutableList().apply { set(at, result) })
    }

    /**
     * The Bucket phrases of [probes] that [model] misheard in every variant
     * and the loop has not announced yet, as spoken. Commands are left out:
     * the person cannot rename them. So is a phrase heard only some of the
     * time ([Level.SOMETIMES]): both are shown on the settings screen, and
     * the loop speaks up only for a name it will not hear at all.
     */
    fun toAnnounce(probes: List<Probe>, model: String): List<String> = probes
        .filter { it.kind == ProbeKind.BUCKET && it.key !in announcedPhrases && verdict(it, probes, model)?.level == Level.MISHEARD }
        .map { it.spoken }

    /** With [phrases] marked as announced, so no later Session says them again. */
    fun announced(phrases: List<String>): NameCheckResults = copy(announcedPhrases = announcedPhrases + phrases.map(Probes::key))

    /**
     * One line for the settings screen about [probes] (a Bucket's name and
     * aliases, or the commands) on [model]; null [model] means no speech
     * models to check with.
     */
    fun summary(probes: List<Probe>, model: String?): String {
        if (model == null) return "Not checked yet: no speech models."
        val unique = probes.distinctBy { it.key }
        val verdicts = unique.map { it.spoken to verdict(it, unique, model) }
        val parts = mutableListOf<String>()
        verdicts.filter { it.second?.level == Level.MISHEARD }.takeIf { it.isNotEmpty() }?.let { l ->
            parts += "Hard to hear: " + l.joinToString(", ") { (p, v) -> "${q(p)} (heard as ${v!!.misheardAs.joinToString(", ") { as_(it) }})" } + "."
        }
        verdicts.filter { it.second?.level == Level.SOMETIMES }.takeIf { it.isNotEmpty() }?.let { l ->
            parts += "Sometimes misheard: " + l.joinToString(", ") { (p, v) ->
                "${q(p)} (${v!!.passes} of ${v.heard.size}, as ${v.misheardAs.joinToString(", ") { as_(it) }})"
            } + "."
        }
        verdicts.filter { it.second == null }.takeIf { it.isNotEmpty() }?.let { l ->
            parts += "Not checked yet: " + l.joinToString(", ") { q(it.first) } + "."
        }
        return parts.joinToString(" ").ifEmpty { "Heard well: all ${unique.size}." }
    }

    private fun q(s: String) = "\"$s\""

    private fun as_(text: String) = if (text.isBlank()) "nothing" else q(text)

    /** The file format [parse] reads. */
    fun toJson(): String = JsonWriter.pretty(
        linkedMapOf(
            SCHEMA to 1,
            RESULTS to results.map { r ->
                linkedMapOf(
                    PHRASE to r.phrase,
                    MODEL to r.model,
                    HEARD to r.heard.map { linkedMapOf(VARIANT to it.variant, TEXT to it.text) },
                    CHECKED_AT to UtcTimestamp.format(r.checkedAtMs),
                )
            },
            ANNOUNCED to announcedPhrases.toList(),
        ),
    )

    /** The result of [parse]: the results to use, and each problem found, as `"where: why"`. */
    data class Parsed(val results: NameCheckResults, val rejected: List<String>)

    companion object {
        /** Path under the app's `files/`. */
        const val FILE = "recognisability.json"

        val EMPTY = NameCheckResults(emptyList(), emptySet())

        private const val SCHEMA = "schema"
        private const val RESULTS = "results"
        private const val ANNOUNCED = "announced"
        private const val PHRASE = "phrase"
        private const val MODEL = "model"
        private const val HEARD = "heard"
        private const val VARIANT = "variant"
        private const val TEXT = "text"
        private const val CHECKED_AT = "checked_at"

        /** What the loop says, once, about [phrases] it cannot reliably hear; null when there are none. */
        fun announcement(phrases: List<String>): String? =
            if (phrases.isEmpty()) null else "hard to hear: ${phrases.joinToString(", ")}. try other aliases in settings."

        /**
         * Parses the file, falling back per entry: a file that is not a JSON
         * object means [EMPTY] (every phrase is checked again); a result or an
         * announced phrase that is not well-formed is dropped alone.
         */
        fun parse(json: String): Parsed {
            val root = try {
                Json.parseObject(json)
            } catch (e: JsonException) {
                return Parsed(EMPTY, listOf("file: ${e.message}"))
            }
            val rejected = mutableListOf<String>()
            val results = mutableListOf<Result>()
            for ((i, raw) in (root[RESULTS] as? List<*>).orEmpty().withIndex()) {
                val r = raw as? Map<*, *>
                val phrase = r?.get(PHRASE) as? String
                val model = r?.get(MODEL) as? String
                val heard = (r?.get(HEARD) as? List<*>)?.map { h ->
                    val m = h as? Map<*, *>
                    val v = m?.get(VARIANT) as? String
                    val t = m?.get(TEXT) as? String
                    if (v == null || t == null) null else Heard(v, t)
                }
                val at = (r?.get(CHECKED_AT) as? String)?.let(UtcTimestamp::parse)
                if (phrase.isNullOrEmpty() || model.isNullOrEmpty() || heard == null || heard.isEmpty() || null in heard || at == null) {
                    rejected += "$RESULTS[$i]: not a result; dropped"
                    continue
                }
                results += Result(phrase, model, heard.map { it!! }, at)
            }
            val announced = linkedSetOf<String>()
            for ((i, p) in (root[ANNOUNCED] as? List<*>).orEmpty().withIndex()) {
                if (p is String && p.isNotEmpty()) announced += Probes.key(p) else rejected += "$ANNOUNCED[$i]: not a phrase; dropped"
            }
            return Parsed(NameCheckResults(results, announced), rejected)
        }
    }
}
