package com.walnutgeek.stsloop.core.grammar

import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CommandInvocation
import com.walnutgeek.stsloop.core.TurnKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Whole-utterance commands in the phrase grammar (`docs/mvp.md`, "Commands"). */
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
}
