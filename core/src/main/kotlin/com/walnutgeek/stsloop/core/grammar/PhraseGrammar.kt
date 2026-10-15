package com.walnutgeek.stsloop.core.grammar

import com.walnutgeek.stsloop.core.BucketRequest
import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CommandInvocation
import com.walnutgeek.stsloop.core.Declaration
import com.walnutgeek.stsloop.core.DeclarationPosition
import com.walnutgeek.stsloop.core.DraftCommand
import com.walnutgeek.stsloop.core.SummaryCommand

/**
 * The phrase grammar (`docs/phase0.md`, "Phrase grammar"): pure string matching
 * against a transcript. No inference, no model, no thresholds.
 *
 * A **command** ([Command]) is obeyed only when it is the whole utterance:
 * its phrase, read as words, with nothing around it but [FILLERS]. Anything
 * more is not a command, so "scratch that idea about the roof" is stored, not
 * obeyed: ambiguity resolves toward storing, because losing a thought is worse
 * than ignoring a command. A command is matched before any Declaration, so a
 * Bucket alias that happens to be a command phrase cannot shadow it.
 *
 * A **Declaration** is a Bucket's name or alias ([BucketRegistry]) at the very
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
 *
 * **Focus** (`docs/mvp.md`): a Turn that is only one Bucket alias, fillers
 * aside, is [Command.FOCUS] on that Bucket, the Inbox included ("inbox").
 * It is matched after the fixed commands and before Declarations. A Turn
 * that is neither a command nor Declared is a Note in the focused Bucket,
 * when [classify] is given one, its content running from its first word to
 * its last with [FILLERS] at either end left out; a Turn of only fillers
 * ("um") stays unclassified.
 *
 * **Bucket commands** (`docs/mvp.md`, "Commands") come from the Buckets'
 * Descriptors, never from phone code. Matched like a fixed command (the
 * whole utterance, fillers aside), after the fixed commands: first the
 * commands of the Bucket that has Focus ([classify]'s `focused`), then the
 * commands every Bucket declares `anywhere` ("what's blocked"), then Bucket
 * aliases. Each is a [Command.REQUEST] naming its Bucket and command, or,
 * for a command of the `draft` Primitive ("read it back", "send",
 * "cancel"), the [Command] its id names, with a [DraftCommand].
 *
 * **Summaries** (#76): [Command.SUMMARIZE] ("summarize bucket") names the
 * Bucket that has Focus, the Inbox included; under an agent Bucket's Focus
 * it is that Bucket's [READ] request ("what does it say") instead.
 *
 * **Drafts** (#45): under the Focus of an agent Bucket that uses the `draft`
 * Primitive ([BucketRegistry.takesDrafts]), a Turn that is neither a command
 * nor Declared is a Draft Turn ([Classification.drafted]), its content cut as
 * a focused Note's is.
 *
 * The Buckets come from a [BucketRegistry]: any Bucket in it may be focused,
 * and those using [Primitive.NOTE] (the Inbox aside) may be Declared into.
 * When the hub's Buckets change mid-Session, [replace] swaps the registry
 * whole; each [classify] reads one registry throughout. Thread-safe.
 */
class PhraseGrammar(buckets: BucketRegistry) {
    /** The phone alone: the capture Buckets of [config]. */
    constructor(config: BucketConfig) : this(BucketRegistry.capture(config))

    /** A Declaration and the content left once it is taken out. */
    internal data class Declared(val declaration: Declaration, val content: String)

    /** One registry and its Declaration forms, longest first: most words, then most letters. */
    private class Compiled(val buckets: BucketRegistry) {
        val forms: List<Pair<List<String>, String>> = buckets.declarationForms.entries
            .map { it.key to it.value }
            .sortedWith(compareByDescending<Pair<List<String>, String>> { it.first.size }.thenByDescending { it.first.sumOf(String::length) })

        /** The same forms with their words reversed, for matching from the end. */
        val reversedForms = forms.map { (form, bucket) -> form.reversed() to bucket }
    }

    @Volatile
    private var compiled = Compiled(buckets)

    /** The Buckets the grammar hears now. */
    val buckets: BucketRegistry get() = compiled.buckets

    /** From the next [classify] on, hear the Buckets of [buckets] instead. */
    fun replace(buckets: BucketRegistry) {
        compiled = Compiled(buckets)
    }

    /** Every command phrase as words → its command. */
    private val commands: Map<List<String>, Command> =
        Command.entries.flatMap { c -> c.phrases.map { Words.normalise(it) to c } }.toMap()

    /**
     * What [transcript] (exactly as the engine produced it) is: a command when
     * it is one as a whole (a lone Bucket alias, or a command of the [focused]
     * Bucket or of any Bucket anywhere, included), a Note in its Bucket when
     * Declared, a Note in [focus] (the name of the Bucket it is filed under)
     * when there is one, otherwise unclassified.
     */
    fun classify(transcript: String?, focus: String? = null, focused: String? = focus): Classification {
        if (transcript == null) return Classification.UNCLASSIFIED
        val c = compiled
        command(transcript, c, focused)?.let { return Classification.command(it) }
        declaration(transcript, c)?.let { return Classification.declared(it.declaration, it.content) }
        if (focus == null) {
            val agent = focused?.let(c.buckets::named)?.takeIf(c.buckets::takesDrafts) ?: return Classification.UNCLASSIFIED
            val content = content(transcript) ?: return Classification.UNCLASSIFIED
            return Classification.drafted(agent.name, agent.id!!, content)
        }
        val content = content(transcript) ?: return Classification.UNCLASSIFIED
        return Classification.focused(focus, content)
    }

    /** The command [transcript] is as a whole, fillers at its ends aside, or null. */
    internal fun command(transcript: String, focused: String? = null): CommandInvocation? = command(transcript, compiled, focused)

    private fun command(transcript: String, c: Compiled, focused: String?): CommandInvocation? {
        val words = Words.normalise(transcript)
        var from = 0
        var to = words.size
        while (from < to && words[from] in FILLERS) from++
        while (to > from && words[to - 1] in FILLERS) to--
        val said = words.subList(from, to)
        val matched = said.joinToString(" ")
        commands[said]?.let { return if (it == Command.SUMMARIZE) summarize(matched, c, focused) else CommandInvocation(it, matched) }
        val own = focused?.let { c.buckets.commandForms[it]?.get(said) }?.let { focused to it }
        (own ?: c.buckets.anywhereForms[said])?.let { (name, command) ->
            val bucket = c.buckets.named(name)
            val id = bucket?.id ?: return@let
            if (command.primitive == Primitive.DRAFT) {
                val draft = Command.draftOf(command.id) ?: return@let
                return CommandInvocation(draft, matched, draft = DraftCommand(name, id))
            }
            return CommandInvocation(Command.REQUEST, matched, request = BucketRequest(name, id, command.id, command.replyWithinMs))
        }
        val bucket = c.buckets.focusForms[said] ?: return null
        return CommandInvocation(Command.FOCUS, matched, focus = bucket, focusId = c.buckets.named(bucket)?.id)
    }

    /**
     * [Command.SUMMARIZE] under the Focus of [focused] (null: the Inbox). On a
     * hub Bucket that declares a [READ] request ("what does it say"), it is
     * that request; otherwise it summarises the focused Bucket's Notes.
     */
    private fun summarize(matched: String, c: Compiled, focused: String?): CommandInvocation {
        val bucket = focused?.let(c.buckets::named)
        val id = bucket?.id?.takeIf { bucket.hub }
        val read = bucket?.commands?.firstOrNull { it.id == READ && it.primitive == Primitive.REQUEST }
        if (id != null && read != null) {
            return CommandInvocation(Command.REQUEST, matched, request = BucketRequest(bucket.name, id, read.id, read.replyWithinMs))
        }
        return CommandInvocation(Command.SUMMARIZE, matched, summary = SummaryCommand(bucket?.name ?: c.buckets.inbox.name))
    }

    /** [transcript] as a Note's content: its words, fillers at either end aside, or null when nothing is left. */
    private fun content(transcript: String): String? {
        val text = Words.prepare(transcript)
        val words = Words.of(text).dropWhile { it.text in FILLERS }.dropLastWhile { it.text in FILLERS }
        if (words.isEmpty()) return null
        return cased(text, text.substring(words.first().start, words.last().end))
    }

    /** [content], cut from [text]; lower-cased when [text] is all upper case. */
    private fun cased(text: String, content: String) = if (text.any { it.isLowerCase() }) content else content.lowercase()

    /** The Declaration in [transcript], or null when there is none. */
    internal fun declaration(transcript: String): Declared? = declaration(transcript, compiled)

    private fun declaration(transcript: String, c: Compiled): Declared? {
        val text = Words.prepare(transcript)
        val words = Words.of(text)
        val texts = words.map { it.text }
        val n = texts.size
        val lead = matchStart(texts, c.forms)
        val trail = matchStart(texts.asReversed(), c.reversedForms, reversed = true)
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
            cased(text, content),
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

    /** Matches [forms] at the start of [words]; the trailing case passes the words, and the forms, [reversed]. */
    private fun matchStart(words: List<String>, forms: List<Pair<List<String>, String>>, reversed: Boolean = false): Match? {
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

        /** The command id of an agent Bucket's "what does it say", which "summarize bucket" means under its Focus. */
        const val READ = "read"
    }
}
