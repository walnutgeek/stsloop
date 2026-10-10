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
 * punctuation and dashes are ignored):
 *
 * ```
 * [fillers] ALIAS [fillers] content          → leading
 * content [fillers] ALIAS [fillers]          → trailing
 * ```
 *
 * - An alias anywhere else is not a Declaration: "I need to work on the roof"
 *   is not in `work`.
 * - [FILLERS] around the alias are skipped; inside the content they stay.
 * - Where several aliases fit at one end, the one with the most words wins
 *   ("house project" over "house"), then the longer spelling.
 * - An alias at both ends declares the leading one; the trailing one stays in
 *   the content. A Bucket named first is the deliberate pattern.
 * - An utterance that is only an alias (and fillers) is not a Declaration: a
 *   Note with no content is not worth a Bucket, and it is likelier a false
 *   start than a thought.
 *
 * The content is the transcript between the Declaration's words, cut from the
 * original text, so the engine's own casing and punctuation inside it are
 * kept. An engine that only writes upper case (the current one) is
 * lower-cased. The transcript itself is never changed.
 */
class PhraseGrammar(private val buckets: BucketConfig) {
    /** A Declaration and the content left once it is taken out. */
    data class Declared(val declaration: Declaration, val content: String)

    /** Forms longest first: most words, then most letters. */
    private val forms: List<Pair<List<String>, String>> = buckets.forms.entries
        .map { it.key to it.value }
        .sortedWith(compareByDescending<Pair<List<String>, String>> { it.first.size }.thenByDescending { it.first.sumOf(String::length) })

    /**
     * What [transcript] (exactly as the engine produced it) is. Nothing heard
     * is unclassified; anything else is a Note, with a Bucket when it is
     * Declared.
     */
    fun classify(transcript: String?): Classification {
        if (transcript == null) return Classification.UNCLASSIFIED
        val words = Words.of(transcript)
        if (words.isEmpty()) return Classification.UNCLASSIFIED
        declaration(transcript, words)?.let { return Classification.declared(it.declaration, it.content) }
        return Classification.undeclared(content(transcript, transcript.trim()))
    }

    /** The Declaration in [transcript], or null when there is none. */
    fun declaration(transcript: String): Declared? = declaration(transcript, Words.of(transcript))

    private fun declaration(text: String, words: List<Word>): Declared? {
        val texts = words.map { it.text }
        val leading = match(texts, DeclarationPosition.LEADING)
        val found = leading ?: match(texts, DeclarationPosition.TRAILING) ?: return null
        val (bucket, from, to, contentRange) = found
        if (contentRange.isEmpty()) return null
        val matched = texts.subList(from, to).joinToString(" ")
        val span = text.substring(words[contentRange.first].start, words[contentRange.last].end)
        return Declared(Declaration(bucket, found.position, matched), content(text, span))
    }

    /** An alias at one end: the Bucket, its words `[from, to)`, and the content's word indices. */
    private data class Match(
        val bucket: String,
        val from: Int,
        val to: Int,
        val content: IntRange,
        val position: DeclarationPosition,
    )

    private fun match(words: List<String>, position: DeclarationPosition): Match? {
        val leading = position == DeclarationPosition.LEADING
        // Walk in from this end over fillers until an alias fits.
        var edge = if (leading) 0 else words.size // leading: alias starts here; trailing: alias ends here
        while (if (leading) edge < words.size else edge > 0) {
            val hit = forms.firstOrNull { (form, _) ->
                if (leading) {
                    edge + form.size <= words.size && words.subList(edge, edge + form.size) == form
                } else {
                    edge - form.size >= 0 && words.subList(edge - form.size, edge) == form
                }
            }
            if (hit != null) {
                val (form, bucket) = hit
                return if (leading) {
                    var start = edge + form.size
                    while (start < words.size && words[start] in FILLERS) start++
                    Match(bucket, edge, edge + form.size, start until words.size, position)
                } else {
                    var end = edge - form.size
                    while (end > 0 && words[end - 1] in FILLERS) end--
                    Match(bucket, edge - form.size, edge, 0 until end, position)
                }
            }
            val next = if (leading) words[edge] else words[edge - 1]
            if (next !in FILLERS) return null
            edge += if (leading) 1 else -1
        }
        return null
    }

    /** [span] of [text] as Note content: lower-cased when the engine wrote no lower case at all. */
    private fun content(text: String, span: String): String = if (text.any { it.isLowerCase() }) span else span.lowercase()

    companion object {
        /**
         * Words skipped between a Declaration and the content, and before a
         * leading or after a trailing one: hesitations, plus "so" and "okay",
         * which people use to start or close a dictation.
         */
        val FILLERS: Set<String> = setOf("um", "umm", "uh", "uhm", "er", "erm", "ah", "hmm", "mm", "so", "okay", "ok")
    }
}
