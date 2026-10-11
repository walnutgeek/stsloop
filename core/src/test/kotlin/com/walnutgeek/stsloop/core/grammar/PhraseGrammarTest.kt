package com.walnutgeek.stsloop.core.grammar

import com.walnutgeek.stsloop.core.BucketSource
import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Declaration
import com.walnutgeek.stsloop.core.DeclarationPosition.LEADING
import com.walnutgeek.stsloop.core.DeclarationPosition.TRAILING
import com.walnutgeek.stsloop.core.TurnKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PhraseGrammarTest {
    private val grammar = PhraseGrammar(BucketConfig.DEFAULT)

    /** Asserts [text] declares [bucket] at [position], heard as [matched], leaving [content]. */
    private fun declared(text: String, bucket: String, position: com.walnutgeek.stsloop.core.DeclarationPosition, matched: String, content: String) {
        val c = grammar.classify(text)
        assertEquals(TurnKind.NOTE, c.kind, text)
        assertEquals(Declaration(bucket, position, matched), c.declaration, text)
        assertEquals(bucket, c.bucket, text)
        assertEquals(BucketSource.DECLARATION, c.bucketSource, text)
        assertEquals(content, c.content, text)
    }

    /** Asserts [text] is not Declared: unclassified, with no label of any kind and no content. */
    private fun undeclared(text: String, g: PhraseGrammar = grammar) {
        assertEquals(Classification.UNCLASSIFIED, g.classify(text), text)
    }

    // --- the mvp.md examples ---

    @Test
    fun `mvp leading example`() = declared("errands, order roofing screws", "errands", LEADING, "errands", "order roofing screws")

    @Test
    fun `mvp trailing example`() = declared("order roofing screws, errands", "errands", TRAILING, "errands", "order roofing screws")

    @Test
    fun `mvp dashed example with a multi-word alias`() =
        declared("house project — check the joist spacing", "house-project", LEADING, "house project", "check the joist spacing")

    @Test
    fun `mvp undeclared example is unlabelled`() = undeclared("order roofing screws")

    @Test
    fun `I need to work on the roof does not land in work`() = undeclared("I need to work on the roof")

    @Test
    fun `I NEED TO WORK ON THE ROOF in raw engine output does not land in work either`() =
        undeclared("I NEED TO WORK ON THE ROOF")

    @Test
    fun `the corpus example in raw engine output`() =
        declared("ERRANDS ORDER ROOFING SCREWS", "errands", LEADING, "errands", "order roofing screws")

    // --- anchoring ---

    @Test
    fun `an alias in the middle is not a Declaration`() = undeclared("buy milk for errands tomorrow")

    @Test
    fun `an alias inside a word is not a Declaration`() = undeclared("workout plan for monday")

    @Test
    fun `an alias that starts a longer word is not a Declaration at the end`() = undeclared("call about the networking")

    @Test
    fun `a leading alias with no separator`() = declared("work fix the build", "work", LEADING, "work", "fix the build")

    @Test
    fun `a trailing alias with no separator`() = declared("fix the build work", "work", TRAILING, "work", "fix the build")

    @Test
    fun `a trailing dash`() = declared("fix the build - work", "work", TRAILING, "work", "fix the build")

    @Test
    fun `a colon after the leading alias`() = declared("Ideas: a podcast lane", "ideas", LEADING, "ideas", "a podcast lane")

    @Test
    fun `a trailing alias followed by a full stop`() = declared("Order roofing screws. Errands.", "errands", TRAILING, "errands", "Order roofing screws")

    @Test
    fun `leading and trailing whitespace is ignored`() = declared("  work   fix the build  ", "work", LEADING, "work", "fix the build")

    // --- case and punctuation ---

    @Test
    fun `matching ignores case`() = declared("ErRaNdS buy milk", "errands", LEADING, "errands", "buy milk")

    @Test
    fun `matching ignores punctuation between the words of an alias`() =
        declared("house, project: check the joists", "house-project", LEADING, "house project", "check the joists")

    @Test
    fun `an apostrophe does not split a word`() = declared("idea don't park on the left", "ideas", LEADING, "idea", "don't park on the left")

    @Test
    fun `a curly apostrophe reads as a straight one`() = declared("idea don\u2019t park on the left", "ideas", LEADING, "idea", "don't park on the left")

    @Test
    fun `a modifier-letter apostrophe reads as a straight one`() = declared("idea don\u02bct park", "ideas", LEADING, "idea", "don't park")

    @Test
    fun `an apostrophe in an alias matches any apostrophe in the transcript`() {
        val g = PhraseGrammar(BucketConfig(listOf(Bucket("kids", listOf("kid\u2019s stuff")))))
        assertEquals("kids", g.classify("kid's stuff buy crayons").bucket)
        assertEquals("kids", g.classify("KIDS STUFF BUY CRAYONS").bucket)
    }

    @Test
    fun `a decomposed accent in the transcript matches a composed alias`() {
        val g = PhraseGrammar(BucketConfig(listOf(Bucket("cafe", listOf("caf\u00e9")))))
        val c = g.classify("cafe\u0301 order beans")
        assertEquals("cafe", c.bucket)
        assertEquals("order beans", c.content)
    }

    @Test
    fun `a decomposed accent in an alias matches a composed transcript`() {
        val g = PhraseGrammar(BucketConfig(listOf(Bucket("cafe", listOf("cafe\u0301")))))
        assertEquals("cafe", g.classify("caf\u00e9 order beans").bucket)
    }

    @Test
    fun `content is cut from the NFC form of the transcript`() {
        val g = PhraseGrammar(BucketConfig(listOf(Bucket("food", emptyList()))))
        assertEquals("cr\u00e8me br\u00fbl\u00e9e", g.classify("food cre\u0300me bru\u0302le\u0301e").content)
    }

    // --- STT spelling variants ---

    @Test
    fun `a hyphenated multi-word alias`() =
        declared("house-project check the joists", "house-project", LEADING, "house project", "check the joists")

    @Test
    fun `a multi-word alias run together`() =
        declared("houseproject check the joists", "house-project", LEADING, "houseproject", "check the joists")

    @Test
    fun `a multi-word alias run together at the end`() =
        declared("check the joists HOUSEPROJECT", "house-project", TRAILING, "houseproject", "check the joists")

    @Test
    fun `a two-word alias also matches run together`() = declared("work log shipped the parser", "work", LEADING, "work log", "shipped the parser")

    @Test
    fun `the singular alias`() = declared("errand pick up the parcel", "errands", LEADING, "errand", "pick up the parcel")

    @Test
    fun `the canonical name is an alias even when not listed`() {
        val g = PhraseGrammar(BucketConfig(listOf(Bucket("garden", listOf("yard")))))
        assertEquals("garden", g.classify("garden water the beds").bucket)
        assertEquals("garden", g.classify("yard water the beds").bucket)
    }

    @Test
    fun `a hyphenated canonical name matches spoken as words`() {
        val g = PhraseGrammar(BucketConfig(listOf(Bucket("house-project", emptyList()))))
        assertEquals("house-project", g.classify("house project check the joists").bucket)
    }

    // --- longest alias wins ---

    @Test
    fun `the longer alias wins over its prefix at the start`() =
        declared("house project check the joists", "house-project", LEADING, "house project", "check the joists")

    @Test
    fun `the longer alias wins over its suffix at the end`() =
        declared("paint the front of the house", "house-project", TRAILING, "the house", "paint the front of")

    @Test
    fun `the longer alias wins across Buckets`() {
        val g = PhraseGrammar(BucketConfig(listOf(Bucket("work", listOf("work")), Bucket("worklog", listOf("work log")))))
        val c = g.classify("work log shipped the parser")
        assertEquals("worklog", c.bucket)
        assertEquals("shipped the parser", c.content)
        assertEquals("work", g.classify("work shipped the parser").bucket)
    }

    @Test
    fun `the shorter alias still matches when the longer does not fit`() =
        declared("house paint the fence", "house-project", LEADING, "house", "paint the fence")

    // --- filler words ---

    @Test
    fun `a filler between a leading alias and the content is dropped`() =
        declared("errands um order roofing screws", "errands", LEADING, "errands", "order roofing screws")

    @Test
    fun `a filler between the content and a trailing alias is dropped`() =
        declared("order roofing screws uh errands", "errands", TRAILING, "errands", "order roofing screws")

    @Test
    fun `a filler before a leading alias is skipped`() =
        declared("okay, work: fix the build", "work", LEADING, "work", "fix the build")

    @Test
    fun `a filler after a trailing alias is skipped`() =
        declared("fix the build, work, okay", "work", TRAILING, "work", "fix the build")

    @Test
    fun `several fillers in a row are all skipped`() =
        declared("um so errands uh um buy milk", "errands", LEADING, "errands", "buy milk")

    @Test
    fun `fillers inside the content are kept`() =
        declared("errands buy um milk", "errands", LEADING, "errands", "buy um milk")

    @Test
    fun `a filler is not an alias, so a filler-led utterance with no alias stays undeclared`() = undeclared("so I was thinking")

    @Test
    fun `the filler list is the documented one`() =
        assertEquals(setOf("um", "umm", "uh", "uhm", "er", "erm", "ah", "hmm", "mm", "so", "okay", "ok"), PhraseGrammar.FILLERS)

    // --- an utterance that is only a Bucket name ---

    @Test
    fun `only a Bucket name is not a Declaration`() = undeclared("errands")

    @Test
    fun `only a multi-word Bucket name is not a Declaration`() = undeclared("House project.")

    @Test
    fun `only a Bucket name and fillers is not a Declaration`() = undeclared("um errands okay")

    @Test
    fun `two different Bucket names and nothing else are not a Declaration`() = undeclared("errands work")

    @Test
    fun `the same Bucket name twice and nothing else is not a Declaration`() = undeclared("errands errands")

    @Test
    fun `the same Bucket name twice around a filler is not a Declaration`() = undeclared("errands um errands")

    // --- both ends ---

    @Test
    fun `different Buckets at the two ends are not a Declaration`() = undeclared("work, call the roofer about the house")

    @Test
    fun `different Buckets at the two ends behind fillers are not a Declaration`() = undeclared("okay work fix it, errands um")

    @Test
    fun `the same Bucket at both ends declares the leading one and takes both out of the content`() =
        declared("errands buy milk errands", "errands", LEADING, "errands", "buy milk")

    @Test
    fun `the same Bucket by different aliases at both ends is one Declaration`() =
        declared("shopping, buy milk - errands.", "errands", LEADING, "shopping", "buy milk")

    @Test
    fun `an alias whose words overlap at both ends is still only one alias`() = undeclared("work log")

    // --- content ---

    @Test
    fun `content keeps the engine's casing and inner punctuation when it has lower case`() =
        declared("Errands — order 3 roofing screws, the long ones", "errands", LEADING, "errands", "order 3 roofing screws, the long ones")

    @Test
    fun `punctuation around the content is dropped, whichever end the Declaration is at`() {
        declared("Errands: order screws.", "errands", LEADING, "errands", "order screws")
        declared("\"Order screws!\" — errands", "errands", TRAILING, "errands", "Order screws")
    }

    @Test
    fun `content of an all-caps engine is lower-cased`() =
        declared("WORK FIX THE BUILD", "work", LEADING, "work", "fix the build")

    @Test
    fun `content of a trailing Declaration keeps inner punctuation`() =
        declared("call Bob, then Alice — work", "work", TRAILING, "work", "call Bob, then Alice")

    @Test
    fun `digits count as words`() = declared("errands 12 screws", "errands", LEADING, "errands", "12 screws")

    @Test
    fun `the raw transcript is not changed`() {
        val raw = "ERRANDS ORDER ROOFING SCREWS"
        grammar.classify(raw)
        assertEquals("ERRANDS ORDER ROOFING SCREWS", raw)
    }

    @Test
    fun `an undeclared transcript gets no content, so nothing derived from it can be mistaken for a label`() =
        assertNull(grammar.classify("ORDER ROOFING SCREWS").content)

    // --- what is not a Note ---

    @Test
    fun `no transcript is unclassified`() {
        val c = grammar.classify(null)
        assertEquals(TurnKind.UNCLASSIFIED, c.kind)
        assertNull(c.declaration)
        assertNull(c.content)
    }

    @Test
    fun `an empty transcript is unclassified`() = assertEquals(TurnKind.UNCLASSIFIED, grammar.classify("").kind)

    @Test
    fun `a transcript of only punctuation is unclassified`() = assertEquals(TurnKind.UNCLASSIFIED, grammar.classify(" ... ").kind)

    @Test
    fun `with no Buckets every transcript is unclassified`() = undeclared("errands buy milk", PhraseGrammar(BucketConfig(emptyList())))

    @Test
    fun `scratch that is a command, not a Note`() = assertEquals(TurnKind.COMMAND, grammar.classify("SCRATCH THAT").kind)

    // --- the parser on its own ---

    @Test
    fun `declaration returns null for an undeclared transcript`() = assertNull(grammar.declaration("order roofing screws"))

    @Test
    fun `declaration returns the Declaration and its content`() {
        val d = grammar.declaration("order roofing screws, errands")!!
        assertEquals(Declaration("errands", TRAILING, "errands"), d.declaration)
        assertEquals("order roofing screws", d.content)
    }
}
