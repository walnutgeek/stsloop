package com.walnutgeek.stsloop.core.recognisability

import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.grammar.Words

/** What the recogniser made of a phrase in one [variant] (a voice at a speech rate, [Variant.id]). */
data class Heard(val variant: String, val text: String)

/** How reliably a phrase is heard, over every variant it was spoken in. */
enum class Level {
    /** Heard as the phrase in every variant. */
    HEARD,

    /** Heard in some variants, misheard in others. */
    SOMETIMES,

    /** Misheard in every variant. */
    MISHEARD,
}

/**
 * The check's judgment of one [phrase] from what the recogniser [heard]: a
 * transcript counts when the grammar would take it as that phrase said alone
 * ([isPhrase]), or as one of the phrases [also] meaning the same thing (the
 * Bucket's other aliases: "worklog" heard as "work log" still focuses work).
 * Only a phrase heard in **every** variant is reliable; anything less is
 * [flagged], because the loop's grammar is exact, so a phrase misheard one
 * time in six is a lone alias that goes to the Inbox one time in six.
 */
data class Verdict(val phrase: String, val heard: List<Heard>, val also: List<String> = emptyList()) {
    private fun counts(text: String) = isPhrase(phrase, text) || also.any { isPhrase(it, text) }

    val passes: Int = heard.count { counts(it.text) }

    val level: Level = when (passes) {
        heard.size -> Level.HEARD
        0 -> Level.MISHEARD
        else -> Level.SOMETIMES
    }

    val flagged: Boolean get() = level != Level.HEARD

    /** Each transcript that was not the phrase, once, in the order heard. */
    val misheardAs: List<String> get() = heard.map { it.text }.filterNot(::counts).distinct()

    companion object {
        /**
         * Whether [transcript] is [phrase] on its own, as the grammar matches
         * a command or a lone alias: the same words ([Words]), or a phrase of
         * several words run together, with [PhraseGrammar.FILLERS] at either end aside.
         */
        fun isPhrase(phrase: String, transcript: String): Boolean {
            val said = Words.normalise(transcript)
                .dropWhile { it in PhraseGrammar.FILLERS }
                .dropLastWhile { it in PhraseGrammar.FILLERS }
            return said.isNotEmpty() && said in BucketConfig.formsOf(phrase)
        }
    }
}
