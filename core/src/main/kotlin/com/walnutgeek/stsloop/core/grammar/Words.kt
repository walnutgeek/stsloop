package com.walnutgeek.stsloop.core.grammar

import java.text.Normalizer

/** One word of a transcript: its normalised [text], and where it sits in the prepared text (`[start, end)`). */
internal data class Word(val text: String, val start: Int, val end: Int)

/**
 * How the phrase grammar reads text, case- and punctuation-insensitively.
 *
 * Text is first [prepare]d: Unicode NFC, so a decomposed accent equals a
 * composed one, and every apostrophe (U+2019, U+02BC) becomes `'`.
 * A word is then a run of letters, digits and apostrophes; everything else
 * (spaces, commas, dashes, colons, full stops) only separates words, so
 * "house-project" is the two words of "house project". A word is
 * lower-cased and loses its apostrophes ("Don't" → "dont"). A run with no
 * letter or digit is not a word.
 */
internal object Words {
    /** NFC, with every apostrophe made `'`. Word offsets refer to this form. */
    fun prepare(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).replace('’', '\'').replace('ʼ', '\'')

    /** The words of [prepared] (already [prepare]d). */
    fun of(prepared: String): List<Word> {
        val out = mutableListOf<Word>()
        var i = 0
        while (i < prepared.length) {
            if (!isWordChar(prepared[i])) {
                i++
                continue
            }
            val start = i
            while (i < prepared.length && isWordChar(prepared[i])) i++
            val norm = StringBuilder()
            for (k in start until i) if (prepared[k].isLetterOrDigit()) norm.append(prepared[k].lowercaseChar())
            if (norm.isNotEmpty()) out += Word(norm.toString(), start, i)
        }
        return out
    }

    /** The normalised words of any [text]. */
    fun normalise(text: String): List<String> = of(prepare(text)).map { it.text }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\''
}
