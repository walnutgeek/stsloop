package com.walnutgeek.stsloop.core.recognisability

import com.walnutgeek.stsloop.core.UtcTimestamp
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonException
import com.walnutgeek.stsloop.core.corpus.JsonWriter
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.BucketRegistry

/**
 * What the name-recognisability check has heard, and which flagged phrases
 * the loop has already announced. Kept in `files/recognisability.json`, so a
 * phrase is checked, and announced, once per recogniser model:
 * ```json
 * { "results": [ { "phrase": "stsloop", "model": "…/int8 greedy_search",
 *                  "heard": [ { "variant": "en-us-x-iol-local@1.0", "text": "St. Loop." } ],
 *                  "checked_at": "2026-10-12T10:00:00.000Z" } ],
 *   "announced": [ { "phrase": "stsloop", "model": "…/int8 greedy_search" } ],
 *   "learned": [ { "phrase": "stsloop", "model": "…", "alias": "STS loop", "announced": true } ],
 *   "removed": [ { "phrase": "woodglue", "model": "…", "alias": "Wood glue" } ] }
 * ```
 * It also keeps each alias learned from what was heard ([learned], #81;
 * the alias itself lives in the Bucket files, where the grammar reads it),
 * and each learned alias the person removed ([removed]), which is not
 * learned again for that phrase and model.
 * The file keeps what was heard, not a verdict, so the policy ([Verdict]) can
 * change without checking again. A result is filed by its phrase's
 * [Probes.key] and the model (`transcript.model`): a new model checks every
 * phrase again. Immutable; each change returns a new value.
 */
data class NameCheckResults(
    val results: List<Checked>,
    val announced: Set<Announced>,
    val learned: List<LearnedAlias> = emptyList(),
    val removed: List<RemovedAlias> = emptyList(),
) {
    /** What [model] heard for [phrase] (a [Probes.key]), in each variant, at [checkedAtMs]. */
    data class Checked(val phrase: String, val model: String, val heard: List<Heard>, val checkedAtMs: Long)

    /** A [phrase] (a [Probes.key]) the loop has said [model] cannot reliably hear. */
    data class Announced(val phrase: String, val model: String)

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
        val result = Checked(key, model, heard, checkedAtMs)
        val at = results.indexOfFirst { it.phrase == key && it.model == model }
        return copy(results = if (at < 0) results + result else results.toMutableList().apply { set(at, result) })
    }

    /**
     * The Bucket phrases of [probes] that [model] cannot reliably hear
     * ([Verdict.flagged]) and the loop has not announced for it yet, as
     * spoken. Commands are left out: the person cannot rename them, so they
     * are only shown on the settings screen.
     */
    fun toAnnounce(probes: List<Probe>, model: String): List<String> = probes
        .filter { it.kind == ProbeKind.BUCKET && Announced(it.key, model) !in announced && verdict(it, probes, model)?.flagged == true }
        .map { it.spoken }

    /** With [phrases] marked as announced for [model], so no later Session says them again. */
    fun announced(phrases: List<String>, model: String): NameCheckResults =
        copy(announced = announced + phrases.map { Announced(Probes.key(it), model) })

    /** With [aliases] recorded as learned, each (phrase, model, alias) once. */
    fun learned(aliases: List<LearnedAlias>): NameCheckResults {
        val out = learned.toMutableList()
        for (a in aliases) if (out.none { it.phrase == a.phrase && it.model == a.model && sameWords(it.alias, a.alias) }) out += a
        return copy(learned = out)
    }

    /**
     * With the learned [alias] removed by the person: its records go, and
     * each phrase it was learned for, or that some model heard consistently as
     * it ([AliasLearner.consistent]; an alias written over adb has no record),
     * is not learned again on that model.
     */
    fun unlearned(alias: String): NameCheckResults {
        val gone = learned.filter { sameWords(it.alias, alias) }.map { RemovedAlias(it.phrase, it.model, it.alias) } +
            results.filter { r -> AliasLearner.consistent(r.heard)?.let { sameWords(it, alias) } == true }
                .map { RemovedAlias(it.phrase, it.model, alias) }
        val out = removed.toMutableList()
        for (g in gone) if (out.none { it.phrase == g.phrase && it.model == g.model }) out += g
        return copy(learned = learned.filterNot { sameWords(it.alias, alias) }, removed = out)
    }

    /** Whether the person removed what [phrase] (a [Probes.key]) was learned as on [model]. */
    fun isRemoved(phrase: String, model: String): Boolean = removed.any { it.phrase == phrase && it.model == model }

    /**
     * Session start (#49, #81): what the loop says once on [model] over
     * [registry], and the results with it marked said. First each learned
     * alias the registry hears and no Session has announced ("stsloop agent
     * also answers to STS loop agent."), then the Bucket phrases still hard
     * to hear ([toAnnounce]). Marked before they are said, so a Session
     * stopped before its first Silence never says them, rather than every
     * Session saying them.
     */
    fun announce(registry: BucketRegistry, model: String): Pair<NameCheckResults, String?> {
        val byBucket = LinkedHashMap<String, MutableList<String>>()
        val told = mutableListOf<LearnedAlias>()
        for (l in learned) {
            if (l.model != model || l.announced) continue
            val bucket = registry.buckets.firstOrNull { b -> b.learned.any { sameWords(it, l.alias) } } ?: continue
            byBucket.getOrPut(bucket.name) { mutableListOf() } += bucket.learned.first { sameWords(it, l.alias) }
            told += l
        }
        val probes = Probes.of(registry)
        val hard = toAnnounce(probes, model)
        val after = copy(learned = learned.map { if (it in told) it.copy(announced = true) else it }).announced(hard, model)
        val say = listOfNotNull(learnedAnnouncement(byBucket), announcement(hard)).joinToString(" ").ifEmpty { null }
        return after to say
    }

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
            parts += "Hard to hear: " + l.joinToString(", ") { (p, v) -> "${quoted(p)} (heard as ${misheard(v!!)})" } + "."
        }
        verdicts.filter { it.second?.level == Level.SOMETIMES }.takeIf { it.isNotEmpty() }?.let { l ->
            parts += "Sometimes misheard: " + l.joinToString(", ") { (p, v) -> "${quoted(p)} (${v!!.passes} of ${v.heard.size}, as ${misheard(v)})" } + "."
        }
        verdicts.filter { it.second == null }.takeIf { it.isNotEmpty() }?.let { l ->
            parts += "Not checked yet: " + l.joinToString(", ") { quoted(it.first) } + "."
        }
        return parts.joinToString(" ").ifEmpty { "Heard well: all ${unique.size}." }
    }

    private fun quoted(s: String) = "\"$s\""

    /** What [v]'s phrase was heard as instead; an empty transcript is "nothing". */
    private fun misheard(v: Verdict) = v.misheardAs.joinToString(", ") { if (it.isBlank()) "nothing" else quoted(it) }

    /** The file format [parse] reads. */
    fun toJson(): String = JsonWriter.pretty(
        linkedMapOf<String, Any>(
            RESULTS to results.map { r ->
                linkedMapOf(
                    PHRASE to r.phrase,
                    MODEL to r.model,
                    HEARD to r.heard.map { linkedMapOf(VARIANT to it.variant, TEXT to it.text) },
                    CHECKED_AT to UtcTimestamp.format(r.checkedAtMs),
                )
            },
            ANNOUNCED to announced.map { linkedMapOf(PHRASE to it.phrase, MODEL to it.model) },
        ).apply {
            if (learned.isNotEmpty()) {
                put(LEARNED, learned.map { linkedMapOf(PHRASE to it.phrase, MODEL to it.model, ALIAS to it.alias, ANNOUNCED to it.announced) })
            }
            if (removed.isNotEmpty()) put(REMOVED, removed.map { linkedMapOf(PHRASE to it.phrase, MODEL to it.model, ALIAS to it.alias) })
        },
    )

    /** The result of [parse]: the results to use, and each problem found, as `"where: why"`. */
    data class Parsed(val results: NameCheckResults, val rejected: List<String>)

    companion object {
        /** Path under the app's `files/`. */
        const val FILE = "recognisability.json"

        val EMPTY = NameCheckResults(emptyList(), emptySet())

        private const val RESULTS = "results"
        private const val ANNOUNCED = "announced"
        private const val PHRASE = "phrase"
        private const val MODEL = "model"
        private const val HEARD = "heard"
        private const val VARIANT = "variant"
        private const val TEXT = "text"
        private const val CHECKED_AT = "checked_at"
        private const val LEARNED = "learned"
        private const val REMOVED = "removed"
        private const val ALIAS = "alias"

        /** Whether [a] and [b] are heard as the same words. */
        private fun sameWords(a: String, b: String) = BucketConfig.formsOf(a).any { it in BucketConfig.formsOf(b) }

        /** What the loop says, once, about the aliases it learned, by Bucket name; null when there are none. */
        fun learnedAnnouncement(byBucket: Map<String, List<String>>): String? =
            if (byBucket.isEmpty()) null else byBucket.entries.joinToString(" ") { (name, aliases) -> "$name also answers to ${aliases.joinToString(" and ")}." }

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
            val results = mutableListOf<Checked>()
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
                results += Checked(phrase, model, heard.map { it!! }, at)
            }
            val announced = linkedSetOf<Announced>()
            for ((i, raw) in (root[ANNOUNCED] as? List<*>).orEmpty().withIndex()) {
                val a = raw as? Map<*, *>
                val phrase = a?.get(PHRASE) as? String
                val model = a?.get(MODEL) as? String
                if (phrase.isNullOrEmpty() || model.isNullOrEmpty()) {
                    rejected += "$ANNOUNCED[$i]: not a phrase and a model; dropped"
                    continue
                }
                announced += Announced(Probes.key(phrase), model)
            }
            val learned = mutableListOf<LearnedAlias>()
            for ((i, raw) in (root[LEARNED] as? List<*>).orEmpty().withIndex()) {
                val a = raw as? Map<*, *>
                val phrase = a?.get(PHRASE) as? String
                val model = a?.get(MODEL) as? String
                val alias = a?.get(ALIAS) as? String
                if (phrase.isNullOrEmpty() || model.isNullOrEmpty() || alias.isNullOrBlank()) {
                    rejected += "$LEARNED[$i]: not a phrase, a model and an alias; dropped"
                    continue
                }
                learned += LearnedAlias(Probes.key(phrase), model, alias, a[ANNOUNCED] == true)
            }
            val removed = mutableListOf<RemovedAlias>()
            for ((i, raw) in (root[REMOVED] as? List<*>).orEmpty().withIndex()) {
                val a = raw as? Map<*, *>
                val phrase = a?.get(PHRASE) as? String
                val model = a?.get(MODEL) as? String
                val alias = a?.get(ALIAS) as? String
                if (phrase.isNullOrEmpty() || model.isNullOrEmpty() || alias == null) {
                    rejected += "$REMOVED[$i]: not a phrase, a model and an alias; dropped"
                    continue
                }
                removed += RemovedAlias(Probes.key(phrase), model, alias)
            }
            return Parsed(NameCheckResults(results, announced, learned, removed), rejected)
        }
    }
}
