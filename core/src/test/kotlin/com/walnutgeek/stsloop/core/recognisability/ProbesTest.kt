package com.walnutgeek.stsloop.core.recognisability

import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.grammar.Bucket
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.hub.Fixtures
import com.walnutgeek.stsloop.core.hub.HubProvider
import com.walnutgeek.stsloop.core.hub.Protocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProbesTest {
    private val agents = Protocol.decode(Fixtures.text("v1", "descriptors/agents.json")) as Descriptors
    private val herdr = Protocol.decode(Fixtures.text("v1", "descriptors/herdr.json")) as Descriptors

    @Test
    fun `every Bucket name and alias is a Bucket probe, spoken as its words`() {
        val p = Probes.of(HubProvider.registry(BucketConfig.DEFAULT, null))
        val buckets = p.filter { it.kind == ProbeKind.BUCKET }
        assertEquals(
            listOf("errands", "errand", "shopping", "house project", "house", "the house", "work", "worklog", "work log", "ideas", "idea", "thought"),
            buckets.map { it.key },
        )
        // A Bucket's name is spoken as words: TTS would read the dash.
        assertEquals("house project", buckets.first { it.owner == "house-project" }.spoken)
        assertEquals(setOf("errands", "house-project", "work", "ideas"), buckets.map { it.owner }.toSet())
    }

    @Test
    fun `the fixed commands and the Inbox are command probes, which the person cannot rename`() {
        val p = Probes.of(HubProvider.registry(BucketConfig.DEFAULT, null))
        val commands = p.filter { it.kind == ProbeKind.COMMAND }.map { it.key }
        assertEquals(Command.entries.flatMap { it.phrases } + "inbox", commands)
        assertEquals("where_am_i", p.first { it.key == "which bucket" }.owner)
    }

    @Test
    fun `hub Buckets and the commands the phone runs for them are probed, each phrase once`() {
        val p = Probes.of(HubProvider.registry(BucketConfig.DEFAULT, herdr))
        val stsloop = p.filter { it.owner == "stsloop agent" }
        assertEquals(listOf("stsloop agent", "stsloop"), stsloop.map { it.key })
        assertTrue(stsloop.all { it.kind == ProbeKind.BUCKET && it.hub })
        // "status" is declared by every agent: spoken once.
        assertEquals(1, p.count { it.key == "status" })
        val blocked = p.single { it.key == "whats blocked" }
        assertEquals(ProbeKind.COMMAND, blocked.kind)
        assertEquals("what's blocked", blocked.spoken)
        assertEquals("blocked", blocked.owner)
        assertEquals(p.size, p.map { it.key }.toSet().size)
    }

    @Test
    fun `a command whose Primitive the phone does not run is not listened for, so not probed`() {
        val p = Probes.of(HubProvider.registry(BucketConfig.DEFAULT, agents))
        assertTrue(p.none { it.key == "read it back" }, "a draft command")
    }

    @Test
    fun `an alias that is also a command phrase is listed once, as the Bucket's`() {
        val config = BucketConfig(listOf(Bucket("notes", listOf("repeat"))))
        val p = Probes.of(HubProvider.registry(config, null))
        assertEquals(ProbeKind.BUCKET, p.single { it.key == "repeat" }.kind)
    }

    @Test
    fun `apostrophes are spoken, other punctuation is not`() {
        val config = BucketConfig(listOf(Bucket("Sam's list", listOf("to-do: urgent"))))
        val p = Probes.of(HubProvider.registry(config, null))
        assertEquals(listOf("Sam's list", "to do urgent"), p.filter { it.kind == ProbeKind.BUCKET }.map { it.spoken })
        assertEquals(listOf("sams list", "to do urgent"), p.filter { it.kind == ProbeKind.BUCKET }.map { it.key })
    }
}
