package com.walnutgeek.stsloop.core.hub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HubAliasesTest {
    private val agents = Protocol.decode(Fixtures.text("v1", "descriptors/agents.json")) as Descriptors

    @Test
    fun `the phone's aliases replace the hub's, by Bucket id`() {
        val mine = HubAliases(mapOf("herdr:stsloop" to listOf("loop agent")))
        val d = mine.applyTo(agents)!!
        assertEquals(listOf("loop agent"), d.buckets[0].aliases)
        assertEquals(agents.buckets[1], d.buckets[1], "a Bucket with no aliases of the phone's keeps the hub's")
        assertEquals(agents.buckets[0].copy(aliases = listOf("loop agent")), d.buckets[0])
    }

    @Test
    fun `no snapshot stays none, and no aliases change nothing`() {
        assertEquals(null, HubAliases(mapOf("x" to listOf("y"))).applyTo(null))
        assertSame(agents, HubAliases.NONE.applyTo(agents))
    }

    @Test
    fun `round trips through its file`() {
        val a = HubAliases(mapOf("herdr:stsloop" to listOf("loop agent", "the \"loop\""), "hub" to emptyList()))
        assertEquals(HubAliases.Parsed(a, emptyList()), HubAliases.parse(a.toJson()))
    }

    @Test
    fun `a bad file is read per entry`() {
        val p = HubAliases.parse("""{"aliases": {"a": ["x", 3, "  "], "b": "y", "c": ["z"]}, "extra": 1}""")
        assertEquals(HubAliases(mapOf("a" to listOf("x"), "c" to listOf("z"))), p.aliases)
        assertEquals(4, p.rejected.size, p.rejected.toString())
    }

    @Test
    fun `a file that is not JSON means none`() {
        val p = HubAliases.parse("nope")
        assertEquals(HubAliases.NONE, p.aliases)
        assertEquals(1, p.rejected.size)
        assertEquals(HubAliases.NONE, HubAliases.parse("""{"aliases": []}""").aliases)
    }
}
