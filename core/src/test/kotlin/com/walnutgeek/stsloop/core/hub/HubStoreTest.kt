package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.corpus.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HubStoreTest {
    @TempDir
    lateinit var files: File

    private val pairing get() = Protocol.decode(Fixtures.text("v1", "pairing/basic.json")) as Pairing
    private val stub get() = Protocol.decode(Fixtures.text("v1", "descriptors/stub.json")) as Descriptors
    private val agents get() = Protocol.decode(Fixtures.text("v1", "descriptors/agents.json")) as Descriptors

    @Test
    fun `nothing is stored at first`() {
        val store = HubStore(files)
        assertNull(store.pairing())
        assertNull(store.descriptors())
    }

    @Test
    fun `a pairing and the Descriptors survive a restart`() {
        HubStore(files).apply {
            pair(pairing)
            saveDescriptors(stub)
        }
        val again = HubStore(files)
        assertEquals(pairing, again.pairing())
        assertEquals(stub, again.descriptors())
    }

    @Test
    fun `both are stored as their protocol messages`() {
        HubStore(files).apply {
            pair(pairing)
            saveDescriptors(stub)
        }
        assertEquals(Fixtures.json("v1", "pairing/basic.json"), Json.parse(File(files, "hub/pairing.json").readText()))
        assertEquals(Fixtures.json("v1", "descriptors/stub.json"), Json.parse(File(files, "hub/descriptors.json").readText()))
    }

    @Test
    fun `a new snapshot replaces the cached one`() {
        val store = HubStore(files)
        store.saveDescriptors(stub)
        store.saveDescriptors(agents)
        assertEquals(agents, store.descriptors())
    }

    @Test
    fun `pairing again with the same hub keeps its Descriptors, another hub drops them`() {
        val store = HubStore(files)
        store.pair(pairing)
        store.saveDescriptors(stub)
        store.pair(pairing.copy(token = "n".repeat(43)))
        assertEquals(stub, store.descriptors())
        store.pair(pairing.copy(url = "ws://100.64.0.9:7717/"))
        assertNull(store.descriptors())
    }

    @Test
    fun `the record of uploaded Turns survives a restart and a new token, but not another hub`() {
        val turn = "2026-10-06T14:22:07.431Z-a3f1c9"
        val store = HubStore(files)
        store.pair(pairing)
        store.uploads().add(turn)
        assertEquals(setOf(turn), HubStore(files).uploads().uploaded())
        store.pair(pairing.copy(token = "n".repeat(43)))
        assertEquals(setOf(turn), store.uploads().uploaded())
        store.pair(pairing.copy(url = "ws://100.64.0.9:7717/"))
        assertEquals(emptySet<String>(), store.uploads().uploaded())
        store.uploads().add(turn)
        store.forget()
        assertEquals(emptySet<String>(), store.uploads().uploaded())
    }

    @Test
    fun `forgetting the hub drops the pairing and its Descriptors`() {
        val store = HubStore(files)
        store.pair(pairing)
        store.saveDescriptors(stub)
        store.forget()
        assertNull(store.pairing())
        assertNull(store.descriptors())
        assertFalse(File(files, "hub/pairing.json").exists())
    }

    @Test
    fun `a file that is not its message is an error, not an empty store`() {
        File(files, "hub").mkdirs()
        File(files, "hub/pairing.json").writeText("""{"type": "pairing"}""")
        File(files, "hub/descriptors.json").writeText(Fixtures.text("v1", "welcome/basic.json"))
        assertThrows<BadMessage> { HubStore(files).pairing() }
        assertThrows<BadMessage> { HubStore(files).descriptors() }
    }
}
