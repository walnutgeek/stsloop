package com.walnutgeek.stsloop.core.recognisability

import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.grammar.Words

/**
 * An alias the phone learned (#81) for the Bucket phrase [phrase] (a
 * [Probes.key]: the Bucket's name or one of its aliases) on [model]: [alias]
 * is what the recogniser consistently heard instead, as it wrote it.
 * [announced] once the loop has said so.
 */
data class LearnedAlias(val phrase: String, val model: String, val alias: String, val announced: Boolean = false)

/** A learned [alias] the person removed: [phrase] is not learned again on [model]. */
data class RemovedAlias(val phrase: String, val model: String, val alias: String)

/**
 * Which aliases to learn from what the name-recognisability check heard
 * (#81; Phase 0 findings, point 4). A Bucket phrase the recogniser cannot
 * reliably hear ([Verdict.flagged]) but hears **consistently as the same
 * words** ([consistent]) gains those words as a learned alias: "stsloop
 * agent", heard 6 of 6 as "STS loop agent", is then focused by what the
 * phone really hears.
 *
 * The words are refused when the grammar already hears them as something
 * else: another Bucket's name or alias, a loop command or a command any
 * Bucket declares, or the Inbox. The phrases come from the live grammar
 * ([Probes.of] the registry), so a command a new Descriptor adds counts at
 * once. Two Buckets heard as the same words both go without, as two
 * Buckets claiming one alias do in [com.walnutgeek.stsloop.core.grammar.BucketRegistry].
 * A phrase whose learned alias the person removed is not learned again on
 * that model ([NameCheckResults.isRemoved]).
 */
object AliasLearner {
    /** At least this many variants in [OUT_OF] must be heard as the same words. */
    const val AGREE = 5
    const val OUT_OF = 6

    /** [alias] would be learned for [probe]. */
    data class Candidate(val probe: Probe, val alias: String)

    /** [alias] was not learned for [probe], because [why]. */
    data class Refusal(val probe: Probe, val alias: String, val why: String) {
        /** For the settings screen and the log. */
        val message: String
            get() {
                val heard = "Not learned: \"${probe.spoken}\" is heard as \"$alias\""
                val prefix = "\"$alias\" "
                return if (why.startsWith(prefix)) "$heard, which ${why.removePrefix(prefix)}" else "$heard. $why"
            }
    }

    /** What to [learn] on a model, and what was [refused]. */
    data class Plan(val learn: List<Candidate>, val refused: List<Refusal>)

    /**
     * The words most of [heard] agree on, as the recogniser wrote them
     * (punctuation and [PhraseGrammar.FILLERS] at either end aside), when at
     * least [AGREE] in [OUT_OF] of all variants do; else null. Nothing heard
     * never counts. Words run together count as the same words ("STSloop" is
     * "STS loop"), and the split form is returned, since it is heard both ways.
     */
    fun consistent(heard: List<Heard>): String? {
        if (heard.isEmpty()) return null
        val said = heard.map { spoken(it.text) }.filter { it.first.isNotEmpty() }
        val best = said.groupBy { it.first.joinToString("") }.values.maxByOrNull { it.size } ?: return null
        if (best.size * OUT_OF < heard.size * AGREE) return null
        val words = best.groupBy { it.first }.entries.maxWith(compareBy({ it.key.size }, { it.value.size })).key
        return best.first { it.first == words }.second
    }

    /** What [model] heard that is worth learning for the Bucket phrases of [probes], and what must be refused. */
    fun plan(results: NameCheckResults, probes: List<Probe>, model: String): Plan {
        val formsOf = probes.associateWith { BucketConfig.formsOf(it.key) }
        val found = mutableListOf<Candidate>()
        val refused = mutableListOf<Refusal>()
        for (p in probes) {
            if (p.kind != ProbeKind.BUCKET || results.isRemoved(p.key, model)) continue
            val verdict = results.verdict(p, probes, model) ?: continue
            if (!verdict.flagged) continue
            val alias = consistent(verdict.heard) ?: continue
            // Already heard as its own Bucket (a phrase heard 5 of 6 as itself): nothing to learn.
            if (probes.any { it.kind == p.kind && it.owner == p.owner && Verdict.isPhrase(it.key, alias) }) continue
            // Another Bucket's or command's phrase, in any of its forms, as the registry would see the clash.
            val forms = BucketConfig.formsOf(alias)
            val heardAs = probes.firstOrNull { o -> (o.kind != p.kind || o.owner != p.owner) && formsOf.getValue(o).any { it in forms } }
            when {
                heardAs == null -> found += Candidate(p, alias)
                heardAs.kind == ProbeKind.BUCKET -> refused += Refusal(p, alias, "\"$alias\" is already heard as ${heardAs.owner}.")
                else -> refused += Refusal(p, alias, "\"$alias\" is already the command \"${heardAs.spoken}\".")
            }
        }
        // One Bucket learns each alias once; two Buckets heard as the same words both go without.
        val learn = mutableListOf<Candidate>()
        for (c in found) {
            val forms = BucketConfig.formsOf(c.alias)
            val same = found.filter { o -> BucketConfig.formsOf(o.alias).any { it in forms } }
            val others = same.filter { it.probe.owner != c.probe.owner }
            when {
                others.isNotEmpty() -> refused += Refusal(c.probe, c.alias, "\"${c.alias}\" is also what ${others.joinToString(" and ") { it.probe.owner }} is heard as.")
                same.first() === c -> learn += c
            }
        }
        return Plan(learn, refused)
    }

    /** [text]'s words as the grammar reads them, and as the recogniser wrote them, [PhraseGrammar.FILLERS] at either end aside. */
    internal fun spoken(text: String): Pair<List<String>, String> {
        val prepared = Words.prepare(text)
        val words = Words.of(prepared)
            .dropWhile { it.text in PhraseGrammar.FILLERS }
            .dropLastWhile { it.text in PhraseGrammar.FILLERS }
        return words.map { it.text } to words.joinToString(" ") { prepared.substring(it.start, it.end) }
    }
}
