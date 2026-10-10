package com.walnutgeek.stsloop.core.grammar

/** One word of a transcript: its normalised [text], and where it sits in the original (`[start, end)`). */
internal data class Word(val text: String, val start: Int, val end: Int)

/**
 * How the phrase grammar reads text, case- and punctuation-insensitively.
 *
 * A word is a run of letters, digits and apostrophes; everything else
 * (spaces, commas, dashes, colons, full stops) only separates words, so
 * "house-project" is the two words of "house project". A word is
 * lower-cased and loses its apostrophes ("Don't" → "dont"), so a curly or
 * straight apostrophe, or none, reads the same. A run with no letter or digit
 * is not a word.
 */
internal object Words {
    fun of(text: String): List<Word> {
        val out = mutableListOf<Word>()
        var i = 0
        while (i < text.length) {
            if (!isWordChar(text[i])) {
                i++
                continue
            }
            val start = i
            while (i < text.length && isWordChar(text[i])) i++
            val norm = StringBuilder()
            for (k in start until i) if (text[k].isLetterOrDigit()) norm.append(text[k].lowercaseChar())
            if (norm.isNotEmpty()) out += Word(norm.toString(), start, i)
        }
        return out
    }

    /** The normalised words of [text]. */
    fun normalise(text: String): List<String> = of(text).map { it.text }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '\u2019'
}
