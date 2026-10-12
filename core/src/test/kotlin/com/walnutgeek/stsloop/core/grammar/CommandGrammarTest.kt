package com.walnutgeek.stsloop.core.grammar

import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CommandInvocation
import com.walnutgeek.stsloop.core.TurnKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Whole-utterance commands in the phrase grammar (`docs/phase0.md`, "Commands"). */
class CommandGrammarTest {
    private val grammar = PhraseGrammar(BucketConfig.DEFAULT)

    private fun command(text: String, command: Command, matched: String) {
        assertEquals(Classification.command(CommandInvocation(command, matched)), grammar.classify(text), text)
    }

    private fun notACommand(text: String, g: PhraseGrammar = grammar) {
        val c = g.classify(text)
        assertEquals(null, c.command, text)
        assert(c.kind != TurnKind.COMMAND) { text }
    }

    @Test
    fun `scratch that is a command`() = command("scratch that", Command.SCRATCH_THAT, "scratch that")

    @Test
    fun `discard that is the same command`() = command("discard that", Command.SCRATCH_THAT, "discard that")

    @Test
    fun `the engine's upper case and edge spaces are read as words`() =
        command(" SCRATCH THAT ", Command.SCRATCH_THAT, "scratch that")

    @Test
    fun `punctuation is ignored`() = command("Scratch that.", Command.SCRATCH_THAT, "scratch that")

    @Test
    fun `hesitations at either end still make a whole-utterance command`() {
        command("um, scratch that", Command.SCRATCH_THAT, "scratch that")
        command("okay scratch that uh", Command.SCRATCH_THAT, "scratch that")
    }

    @Test
    fun `a Note containing the phrase is stored, not obeyed`() {
        notACommand("scratch that idea about the roof")
        notACommand("I said scratch that")
        notACommand("please scratch that")
        notACommand("scratch that scratch that")
    }

    @Test
    fun `a Declared Note whose content is the phrase stays a Note`() {
        val c = grammar.classify("errands, scratch that")
        assertEquals(TurnKind.NOTE, c.kind)
        assertEquals("errands", c.bucket)
        assertEquals("scratch that", c.content)
    }

    @Test
    fun `half the phrase is not a command`() {
        notACommand("scratch")
        notACommand("that")
        notACommand("scratch this")
    }

    @Test
    fun `only fillers or nothing is not a command`() {
        notACommand("um")
        notACommand("")
        assertEquals(Classification.UNCLASSIFIED, grammar.classify(null))
    }

    @Test
    fun `a command beats a Bucket alias that happens to be the same words`() {
        val g = PhraseGrammar(BucketConfig(listOf(Bucket("scratch", listOf("scratch that")))))
        assertEquals(TurnKind.COMMAND, g.classify("scratch that").kind)
        // Mid-utterance it is an ordinary Declaration again.
        assertEquals("scratch", g.classify("scratch that buy screws").bucket)
    }

    // --- repeat, what bucket, stop listening (#14) ---

    @Test
    fun `each phrase of each command is that command`() {
        command("REPEAT", Command.REPEAT, "repeat")
        command("SAY THAT AGAIN", Command.REPEAT, "say that again")
        command("WHAT BUCKET", Command.WHAT_BUCKET, "what bucket")
        command("WHICH BUCKET", Command.WHAT_BUCKET, "which bucket")
        command("STOP LISTENING", Command.STOP_LISTENING, "stop listening")
        command("END SESSION", Command.STOP_LISTENING, "end session")
    }

    @Test
    fun `fillers around them are allowed as for scratch that`() {
        command("okay, repeat", Command.REPEAT, "repeat")
        command("um which bucket", Command.WHAT_BUCKET, "which bucket")
        command("OK STOP LISTENING", Command.STOP_LISTENING, "stop listening")
    }

    @Test
    fun `a Note merely containing repeat is stored, not obeyed`() {
        notACommand("repeat the prescription order")
        notACommand("call mom and repeat the question")
        notACommand("REPEAT REPEAT")
        notACommand("please say that again")
        notACommand("what bucket is the roof in")
        notACommand("stop listening to that podcast")
        notACommand("end session notes for the meeting")
        // A Declared Note whose content is a command phrase stays a Note.
        assertEquals(TurnKind.NOTE, grammar.classify("errands, repeat").kind)
        assertEquals(TurnKind.NOTE, grammar.classify("work end session").kind)
    }

    @Test
    fun `near misses the recognizer may produce are Notes, not fuzzy commands`() {
        // No fuzzy matching: ambiguity resolves toward storing.
        notACommand("REPEAT IT")
        notACommand("WHAT BUCKETS")
        notACommand("STOP LISTEN")
        notACommand("STOPPED LISTENING")
        notACommand("AND SESSION")
    }

    @Test
    fun `no phrase belongs to two commands`() {
        val phrases = Command.entries.flatMap { it.phrases }
        assertEquals(phrases.size, phrases.toSet().size)
    }
}
