package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.corpus.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Fixture conformance (ADR-0006, `protocol/README.md`): the phone parses every
 * fixture in `protocol/`, and what it emits is fixture-equivalent (equal as
 * parsed JSON). Runs over every fixture version present.
 */
class ProtocolFixturesTest {
    @Test
    fun `there are fixtures`() {
        assertTrue("v1" in Fixtures.versions())
        assertTrue(Fixtures.paths("v1").size > 5)
    }

    @TestFactory
    fun `every fixture of a known type parses and re-encodes to its known fields`(): List<DynamicTest> =
        Fixtures.versions().flatMap { version ->
            val extras = Fixtures.equivalents(version).keys
            Fixtures.paths(version).filter { !it.startsWith("message/") }.map { rel ->
                DynamicTest.dynamicTest("$version/$rel") {
                    val message = Protocol.decode(Fixtures.text(version, rel))
                    assertNotNull(message, "a known type must parse")
                    if (rel !in extras) assertEquals(Fixtures.json(version, rel), Json.parse(Protocol.encode(message!!)))
                }
            }
        }

    @TestFactory
    fun `a message of unknown type parses to nothing`(): List<DynamicTest> =
        Fixtures.versions().flatMap { version ->
            Fixtures.paths(version).filter { it.startsWith("message/") }.map { rel ->
                DynamicTest.dynamicTest("$version/$rel") { assertNull(Protocol.decode(Fixtures.text(version, rel))) }
            }
        }

    @TestFactory
    fun `a fixture with extra fields reads as its plain twin`(): List<DynamicTest> =
        Fixtures.versions().flatMap { version ->
            Fixtures.equivalents(version).map { (fixture, readsAs) ->
                DynamicTest.dynamicTest("$version/$fixture") {
                    assertEquals(Protocol.decode(Fixtures.text(version, readsAs)), Protocol.decode(Fixtures.text(version, fixture)))
                }
            }
        }

    @Test
    fun `the phone's hello is the basic hello fixture`() =
        assertEquals(Fixtures.json("v1", "hello/basic.json"), Json.parse(Protocol.encode(Protocol.HELLO)))

    @Test
    fun `a stored pairing is the pairing fixture`() {
        val pairing = Protocol.decode(Fixtures.text("v1", "pairing/basic.json")) as Pairing
        assertEquals("ws://100.101.102.103:7717/", pairing.url)
        assertEquals(Fixtures.pairingToken("v1"), pairing.token)
        assertEquals(Fixtures.json("v1", "pairing/basic.json"), Json.parse(Protocol.encode(pairing)))
    }

    @Test
    fun `the stub hub's Descriptors parse to the stub Bucket`() {
        val stub = (Protocol.decode(Fixtures.text("v1", "descriptors/stub.json")) as Descriptors).buckets.single()
        assertEquals(HubDescriptor("hub", "hub", listOf("the hub"), "hub", emptyList(), emptyList()), stub)
    }

    @Test
    fun `an unknown Primitive is kept as its name`() {
        val podcast = (Protocol.decode(Fixtures.text("v1", "descriptors/future-primitive.json")) as Descriptors).buckets.single()
        assertEquals(listOf("request", "seek"), podcast.primitives)
        assertEquals("seek", podcast.commands.single { it.id == "skip" }.primitive)
    }

    @Test
    fun `a refusal's reason, message and supported versions are read`() {
        val refusal = Protocol.decode(Fixtures.text("v1", "refusal/unsupported-major.json")) as Refusal
        assertEquals("unsupported_protocol", refusal.reason)
        assertEquals(listOf(Version(1, 0)), refusal.supported)
    }
}
