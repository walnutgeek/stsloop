package com.walnutgeek.stsloop.core.grammar

import com.walnutgeek.stsloop.core.BucketSource
import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CommandInvocation
import com.walnutgeek.stsloop.core.TurnKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Focus in the phrase grammar (`docs/mvp.md`, "Focus"): a lone Bucket alias, and Turns filed by Focus. */
class FocusGrammarTest {
    private val grammar = PhraseGrammar(BucketRegistry.capture(BucketConfig.DEFAULT))

    private fun focuses(text: String, bucket: String, matched: String) =
        assertEquals(Classification.command(CommandInvocation(Command.FOCUS, matched, focus = bucket)), grammar.classify(text), text)

    @Test
    fun `a Turn that is only a Bucket alias focuses that Bucket`() {
        focuses("ERRANDS", "errands", "errands")
        focuses("shopping", "errands", "shopping")
        focuses("House-Project.", "house-project", "house project")
        focuses("HOUSEPROJECT", "house-project", "houseproject")
    }

    @Test
    fun `fillers around the lone alias are allowed`() {
        focuses("um, errands", "errands", "errands")
        focuses("OKAY WORK", "work", "work")
    }

    @Test
    fun `inbox focuses the Inbox`() = focuses("INBOX", "inbox", "inbox")

    @Test
    fun `two aliases on their own focus nothing`() {
        assertEquals(Classification.UNCLASSIFIED, grammar.classify("errands errands"))
        assertEquals(Classification.UNCLASSIFIED, grammar.classify("errands work"))
    }

    @Test
    fun `the Inbox cannot be Declared into`() =
        assertEquals(Classification.UNCLASSIFIED, grammar.classify("inbox buy milk"))

    @Test
    fun `a command still beats a lone alias with the same words`() {
        val g = PhraseGrammar(BucketConfig(listOf(Bucket("scratch", listOf("scratch that")))))
        assertEquals(Command.SCRATCH_THAT, g.classify("scratch that").command?.command)
    }

    @Test
    fun `with Focus on a Bucket, a Turn that is not a command is a Note there, filed by Focus`() {
        val c = grammar.classify("ORDER ROOFING SCREWS", focus = "errands")
        assertEquals(TurnKind.NOTE, c.kind)
        assertNull(c.declaration)
        assertEquals("errands", c.bucket)
        assertEquals(BucketSource.FOCUS, c.bucketSource)
        assertEquals("order roofing screws", c.content)
    }

    @Test
    fun `a Note filed by Focus keeps a cased engine's text, without the punctuation around it`() =
        assertEquals("Order roofing screws, the 2\" ones", grammar.classify("  Order roofing screws, the 2\" ones. ", focus = "errands").content)

    @Test
    fun `a Declaration wins over Focus and files the Turn where it says`() {
        val c = grammar.classify("WORK FIX THE BUILD", focus = "errands")
        assertEquals("work", c.bucket)
        assertEquals(BucketSource.DECLARATION, c.bucketSource)
        assertEquals("fix the build", c.content)
        assertEquals("errands", grammar.classify("BUY MILK ERRANDS", focus = "work").bucket)
    }

    @Test
    fun `commands and lone aliases are not filed by Focus`() {
        assertEquals(TurnKind.COMMAND, grammar.classify("REPEAT", focus = "errands").kind)
        assertEquals("work", grammar.classify("WORK", focus = "errands").command?.focus)
    }

    @Test
    fun `nothing worth keeping is not filed by Focus`() {
        assertEquals(Classification.UNCLASSIFIED, grammar.classify("", focus = "errands"))
        assertEquals(Classification.UNCLASSIFIED, grammar.classify(" . ", focus = "errands"))
        assertEquals(Classification.UNCLASSIFIED, grammar.classify(null, focus = "errands"))
    }

    @Test
    fun `where am I and what bucket are one command`() {
        for ((text, matched) in listOf("WHERE AM I" to "where am i", "what bucket" to "what bucket", "Which bucket?" to "which bucket")) {
            assertEquals(Classification.command(CommandInvocation(Command.WHERE_AM_I, matched)), grammar.classify(text), text)
        }
    }
}
