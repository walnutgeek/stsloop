package com.walnutgeek.stsloop.core.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JsonTest {
    @Test
    fun `parses objects, arrays and scalars`() {
        val v = Json.parse("""{ "a": 1, "b": [true, false, null], "c": "x", "d": -2.5e1, "e": {} }""")
        assertEquals(
            mapOf("a" to 1L, "b" to listOf(true, false, null), "c" to "x", "d" to -25.0, "e" to emptyMap<String, Any?>()),
            v,
        )
    }

    @Test
    fun `whitespace around the document is fine`() {
        assertEquals(listOf<Any?>(), Json.parse(" \n\t[ ]\r\n"))
        assertNull(Json.parse("null"))
    }

    @Test
    fun `decodes string escapes including surrogate pairs`() {
        assertEquals("q\"b\\s/\b\u000c\n\r\t\u00e9\ud83d\ude00", Json.parse(""""q\"b\\s\/\b\f\n\r\t\u00e9\ud83d\ude00""""))
    }

    @Test
    fun `keeps non-ASCII text written raw`() {
        assertEquals("купить шурупы", Json.parse("\"купить шурупы\""))
    }

    @Test
    fun `large integers stay exact`() {
        assertEquals(1_791_296_527_431L, Json.parse("1791296527431"))
    }

    @Test
    fun `rejects malformed documents`() {
        for (bad in listOf(
            "", "{", "[1,]", "{\"a\" 1}", "{\"a\":1,}", "\"open", "tru", "01", "1.", "-", "{} {}",
            "\"\\x\"", "\"\\u12\"", "\"a\nb\"", "{1:2}", "[1 2]",
        )) {
            assertThrows<JsonException>("should reject: $bad") { Json.parse(bad) }
        }
    }

    @Test
    fun `deep nesting is rejected rather than overflowing the stack`() {
        assertThrows<JsonException> { Json.parse("[".repeat(100_000)) }
    }
}
