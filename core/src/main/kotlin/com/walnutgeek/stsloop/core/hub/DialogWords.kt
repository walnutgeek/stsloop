package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.grammar.Descriptor
import com.walnutgeek.stsloop.core.grammar.Dialog
import com.walnutgeek.stsloop.core.grammar.DialogQuestion

/**
 * How the loop says an agent's question dialog and the answer being put
 * together for it (#80). The hub says the whole dialog for "what does it say"
 * in the same words (`hub/src/stsloop_hub/dialogs.py`, `reading`).
 */
object DialogWords {
    private val NUMBERS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

    /** 1 → "one": how an option or a question is numbered aloud. */
    fun number(n: Int): String = NUMBERS.getOrNull(n - 1) ?: n.toString()

    /** A question's options: "option one: Rebase; option two: Merge.". */
    fun options(q: DialogQuestion): String = q.options.withIndex().joinToString("; ", postfix = ".") { (i, label) -> "option ${number(i + 1)}: $label" }

    /** The question at [index] of [dialog], with its options; numbered when the dialog has more than one. */
    fun question(dialog: Dialog, index: Int): String {
        val q = dialog.questions[index]
        val said = "${q.question} ${options(q)}"
        return if (dialog.questions.size > 1) "question ${number(index + 1)}: $said" else said
    }

    /** One choice as read back: "option two: Merge", or "your answer: …". */
    fun choice(q: DialogQuestion, c: Choice): String =
        if (c.option != null) "option ${number(c.option)}: ${q.options[c.option - 1]}" else "your answer: ${c.text}"

    /** A whole answer as read back, and as a send's text: its choices, in order, joined by "; ". */
    fun answer(dialog: Dialog, choices: List<Choice>): String = choices.withIndex().joinToString("; ") { (i, c) -> choice(dialog.questions[i], c) }

    /** The first phrase of [bucket]'s command [id] (the one the loop teaches), or [fallback] when it declares none. */
    fun phrase(bucket: Descriptor?, id: String, fallback: String): String =
        bucket?.commands?.firstOrNull { it.id == id }?.phrases?.firstOrNull() ?: fallback
}
