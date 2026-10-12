package com.walnutgeek.stsloop.core.hub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProtocolTest {
    @Test
    fun `a frame that is not JSON, or not a typed object, is a BadMessage with no type`() {
        for (frame in listOf("{not json", "[]", "{}", """{"type": ""}""", """{"type": 3}""")) {
            assertEquals(null, assertThrows<BadMessage>(frame) { Protocol.decode(frame) }.messageType)
        }
    }

    @Test
    fun `a known message that breaks its schema is a BadMessage of its type`() {
        val e = assertThrows<BadMessage> { Protocol.decode("""{"type": "welcome", "protocol": {"major": "1", "minor": 0}, "capabilities": []}""") }
        assertEquals("welcome", e.messageType)
    }

    @Test
    fun `a command whose Primitive its Descriptor does not list is refused`() {
        val frame = """{"type": "descriptors", "buckets": [{"id": "a", "name": "a", "aliases": [], "type": "agent",
            "primitives": ["request"], "commands": [{"id": "send", "phrases": ["send"], "primitive": "draft"}]}]}"""
        assertThrows<BadMessage> { Protocol.decode(frame) }
    }

    @Test
    fun `a command's reply_within_ms is a positive integer, or absent`() {
        fun command(extra: String) = """{"type": "descriptors", "buckets": [{"id": "a", "name": "a", "aliases": [], "type": "agent",
            "primitives": ["request"], "commands": [{"id": "read", "phrases": ["read it"], "primitive": "request"$extra}]}]}"""
        fun within(extra: String) = (Protocol.decode(command(extra)) as Descriptors).buckets.single().commands.single().replyWithinMs
        assertEquals(null, within(""))
        assertEquals(30_000L, within(""", "reply_within_ms": 30000"""))
        for (bad in listOf("0", "-5", "\"30000\"", "1.5", "true")) {
            assertThrows<BadMessage>(bad) { Protocol.decode(command(""", "reply_within_ms": $bad""")) }
        }
    }

    @Test
    fun `a pairing needs a WebSocket url and a long URL-safe token`() {
        val token = "a".repeat(32)
        Protocol.decode("""{"type": "pairing", "protocol": {"major": 1, "minor": 0}, "url": "ws://h:7717/", "token": "$token"}""")
        assertThrows<BadMessage> { Protocol.decode("""{"type": "pairing", "protocol": {"major": 1, "minor": 0}, "url": "http://h/", "token": "$token"}""") }
        assertThrows<BadMessage> { Protocol.decode("""{"type": "pairing", "protocol": {"major": 1, "minor": 0}, "url": "ws://h/", "token": "short"}""") }
    }

    @Test
    fun `a pairing never shows its token in a log line`() =
        assertFalse("secret" in Pairing(Protocol.VERSION, "ws://h/", "secret".repeat(6)).toString())
}
