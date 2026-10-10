package com.walnutgeek.stsloop.core.grammar

import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Declaration
import com.walnutgeek.stsloop.core.DeclarationPosition

/**
 * The phrase grammar (`docs/mvp.md`, "Phrase grammar"): pure string matching
 * against a transcript. No inference, no model, no thresholds.
 *
 * A **Declaration** is a Bucket's name or alias ([BucketConfig]) at the very
 * start or the very end of the transcript, read as words (see [Words]: case,
 * punctuation, dashes, apostrophe style and Unicode composition are ignored):
 *
 * ```
 * [fillers] ALIAS [fillers] content          → leading
 * content [fillers] ALIAS [fillers]          → trailing
 * ```
 *
 * Corpus labels are permanent training data, so anything short of a clear
 * Declaration is left unclassified:
 * - An alias anywhere else is not a Declaration: "I need to work on the roof"
 *   is not in `work`.
 * - Aliases of **different** Buckets at the two ends are not a Declaration.
 *   The **same** Bucket at both ends is one leading Declaration, with both
 *   aliases taken out of the content.
 * - An utterance that is only aliases and fillers is not a Declaration.
 *
 * [FILLERS] around the alias are skipped; inside the content they stay. Where
 * several aliases fit at one end, the one with the most words wins ("house
 * project" over "house"), then the longer spelling.
 *
 * The content runs from its first word to its last, cut from the transcript
 * after [Words.prepare], so the engine's casing and the punctuation *inside*
 * it are kept and punctuation around it is dropped. An engine that only
 * writes upper case (the current one) is lower-cased. The transcript itself is
 * never changed.
 */
class PhraseGrammar(buckets: BucketConfig) {
    /** A Declaration and the content left once it is taken out. */
    internal data class Declared(val declaration: Declaration, val content: String)

    /** Forms longest first: most words, then most letters. */
    private val forms: List<Pair<List<String>, String>> = buckets.forms.entries
        .map { it.key to it.value }
        .sortedWith(compareByDescending<Pair<List<String>, String>> { it.first.size }.thenByDescending { it.first.sumOf(String::length) })

    /** The same forms with their words reversed, for matching from the end. */
    private val reversedForms = forms.map { (form, bucket) -> form.reversed() to bucket }

    /**
     * What [transcript] (exactly as the engine produced it) is: a Note in its
     * Bucket when Declared, otherwise unclassified.
     */
    fun classify(transcript: String?): Classification {
        val d = transcript?.let { declaration(it) } ?: return Classification.UNCLASSIFIED
        return Classification.declared(d.declaration, d.content)
    }

    /** The Declaration in [transcript], or null when there is none. */
    internal fun declaration(transcript: String): Declared? {
        val text = Words.prepare(transcript)
        val words = Words.of(text)
        val texts = words.map { it.text }
        val n = texts.size
        val lead = matchStart(texts, reversed = false)
        val trail = matchStart(texts.asReversed(), reversed = true)
        // Indices of the content's words, [from, to).
        val (found, from, to) = when {
            lead != null && trail != null && n - trail.aliasEnd < lead.aliasEnd ->
                // The two ends are the same words (the whole utterance is one alias).
                Triple(lead, lead.contentFrom, n)
            lead != null && trail != null -> {
                if (lead.bucket != trail.bucket) return null
                Triple(lead, lead.contentFrom, n - trail.contentFrom)
            }
            lead != null -> Triple(lead, lead.contentFrom, n)
            trail != null -> Triple(trail.copy(position = DeclarationPosition.TRAILING), 0, n - trail.contentFrom)
            else -> return null
        }
        if (from >= to) return null
        val content = text.substring(words[from].start, words[to - 1].end)
        return Declared(
            Declaration(found.bucket, found.position, found.matched),
            if (text.any { it.isLowerCase() }) content else content.lowercase(),
        )
    }

    /**
     * An alias at the start of [words]: its Bucket, the words heard, where the
     * alias ends and where the content starts (after fillers on both sides).
     */
    private data class Match(
        val bucket: String,
        val matched: String,
        val aliasEnd: Int,
        val contentFrom: Int,
        val position: DeclarationPosition = DeclarationPosition.LEADING,
    )

    /** Matches at the start of [words]; the trailing case passes the words [reversed]. */
    private fun matchStart(words: List<String>, reversed: Boolean): Match? {
        val forms = if (reversed) reversedForms else forms
        var at = 0
        while (at < words.size) {
            val hit = forms.firstOrNull { (form, _) -> at + form.size <= words.size && words.subList(at, at + form.size) == form }
            if (hit != null) {
                val (form, bucket) = hit
                val end = at + form.size
                var content = end
                while (content < words.size && words[content] in FILLERS) content++
                // The heard words read forwards, whichever end they were matched from.
                val heard = if (reversed) form.reversed() else form
                return Match(bucket, heard.joinToString(" "), end, content)
            }
            if (words[at] !in FILLERS) return null
            at++
        }
        return null
    }

    companion object {
        /**
         * Words skipped between a Declaration and the content, and before a
         * leading or after a trailing one: hesitations, plus "so" and "okay",
         * which people use to start or close a dictation.
         */
        val FILLERS: Set<String> = setOf("um", "umm", "uh", "uhm", "er", "erm", "ah", "hmm", "mm", "so", "okay", "ok")
    }
}
