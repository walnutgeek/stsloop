package com.walnutgeek.stsloop.core.turn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What the loop says back: the transcript, in a form a TTS engine reads as words. */
class EchoTextTest {
    @Test
    fun `an all-caps transcript is lower-cased, so the engine does not spell it out`() {
        assertEquals("errands order roofing screws", EchoText.of("ERRANDS ORDER ROOFING SCREWS"))
    }

    @Test
    fun `apostrophes and digits survive`() {
        assertEquals("don't forget the 2 boxes", EchoText.of("DON'T FORGET THE 2 BOXES"))
    }

    @Test
    fun `a cased transcript is spoken as the engine wrote it`() {
        assertEquals("Call NASA about the ISS.", EchoText.of("Call NASA about the ISS."))
    }

    @Test
    fun `edge spaces are trimmed`() {
        assertEquals("hello", EchoText.of("  HELLO "))
    }

    @Test
    fun `nothing heard means nothing to say`() {
        assertNull(EchoText.of(null))
        assertNull(EchoText.of(""))
        assertNull(EchoText.of("   "))
    }

    @Test
    fun `a transcript with no letters or digits is not spoken`() {
        assertNull(EchoText.of(" ' "))
    }

    @Test
    fun `lower-casing ignores the default locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
            assertEquals("this idea", EchoText.of("THIS IDEA"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
