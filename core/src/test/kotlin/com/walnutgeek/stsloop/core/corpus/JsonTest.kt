package com.walnutgeek.stsloop.core.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `a unicode escape takes exactly four hex digits`() {
        assertEquals("\u00ff\u00AB", Json.parse("\"\\u00ff\\u00AB\""))
        assertEquals("\u00ab1", Json.parse("\"\\u00ab1\""))
        for (bad in listOf("\"\\u-001\"", "\"\\u+abc\"", "\"\\u 123\"", "\"\\u12g4\"", "\"\\u012\"")) {
            assertThrows<JsonException>("should reject: $bad") { Json.parse(bad) }
        }
    }

    @Test
    fun `duplicate keys are rejected`() {
        val e = assertThrows<JsonException> { Json.parse("""{ "a": 1, "b": { "c": 2, "c": 3 } }""") }
        assertTrue(e.message!!.contains("duplicate key \"c\""), e.message)
        assertEquals(mapOf("a" to mapOf("c" to 1L), "b" to mapOf("c" to 2L)), Json.parse("""{ "a": { "c": 1 }, "b": { "c": 2 } }"""))
    }

    @Test
    fun `object keys keep document order`() {
        assertEquals(listOf("z", "a", "m"), Json.parseObject("""{ "z": 1, "a": 2, "m": 3 }""").keys.toList())
    }

    @Test
    fun `parseObject requires an object at the root`() {
        assertEquals(mapOf("a" to null), Json.parseObject("""{ "a": null }"""))
        for (bad in listOf("[]", "1", "\"x\"", "null")) {
            assertThrows<JsonException>("should reject: $bad") { Json.parseObject(bad) }
        }
    }

    @Test
    fun `an integer too large for a Long becomes a Double`() {
        assertEquals(1e20, Json.parse("100000000000000000000"))
        assertEquals(-0.0, Json.parse("-0.0"))
        assertEquals(0L, Json.parse("-0"))
    }

    @Test
    fun `a JsonException is an IllegalArgumentException carrying the offset`() {
        val e = assertThrows<JsonException> { Json.parse("[1, x]") }
        assertTrue(e is IllegalArgumentException)
        assertTrue(e.message!!.endsWith("at offset 4"), e.message)
    }

    @Test
    fun `deep nesting is rejected rather than overflowing the stack`() {
        assertThrows<JsonException> { Json.parse("[".repeat(100_000)) }
    }
}
