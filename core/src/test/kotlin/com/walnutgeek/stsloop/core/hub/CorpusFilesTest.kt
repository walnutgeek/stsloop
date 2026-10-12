package com.walnutgeek.stsloop.core.hub

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.Base64

class CorpusFilesTest {
    @TempDir
    lateinit var root: File

    private val corpus get() = File(root, "corpus")

    private val fixtureOffer get() = Protocol.decode(Fixtures.text("v1", "turn_offer/basic.json")) as TurnOffer

    /** The fixture Turn, written as the phone writes it. */
    private fun writeFixtureTurn(): File {
        val dir = File(corpus, fixtureOffer.turn).apply { mkdirs() }
        File(dir, "turn.json").writeText(fixtureOffer.turnJson)
        val pieces = listOf("first", "last").map { Protocol.decode(Fixtures.text("v1", "turn_audio/$it.json")) as TurnAudio }
        File(dir, "audio.wav").writeBytes(pieces.fold(ByteArray(0)) { acc, p -> acc + Base64.getDecoder().decode(p.data) })
        return dir
    }

    @Test
    fun `a Turn on disk is offered exactly as the offer fixture, its Recording hashed`() {
        writeFixtureTurn()
        assertEquals(fixtureOffer, CorpusDirectory(corpus).offer(fixtureOffer.turn))
    }

    @Test
    fun `its Recording is read piece by piece`() {
        val dir = writeFixtureTurn()
        val source = CorpusDirectory(corpus)
        val audio = File(dir, "audio.wav").readBytes()
        assertArrayEquals(audio.copyOfRange(32, 60), source.audio(fixtureOffer.turn, 32, 28))
        assertArrayEquals(audio.copyOfRange(0, 4), CorpusDirectory(corpus).audio(fixtureOffer.turn, 0, 4))
        assertThrows<IOException> { source.audio(fixtureOffer.turn, 50, 20) }
    }

    @Test
    fun `only published Turn directories are listed, oldest first`() {
        val older = "2026-10-06T14:22:00.000Z-0dd5e7"
        val newer = writeFixtureTurn().name
        File(corpus, older).mkdirs()
        File(corpus, "$older/turn.json").writeText("{}")
        File(corpus, "sessions").mkdirs()
        File(corpus, "sessions/2026-10-06T14:00:00.000Z-0f22ab.jsonl").writeText("{}\n")
        File(corpus, "2026-10-06T14:23:00.000Z-aaaaaa").mkdirs() // no turn.json: not a Turn
        File(corpus, ".stfolder").mkdirs()
        assertEquals(listOf(older, newer), CorpusDirectory(corpus).turns())
    }

    @Test
    fun `a missing Corpus has no Turns`() {
        assertEquals(emptyList<String>(), CorpusDirectory(File(root, "nope")).turns())
    }

    @Test
    fun `the Recording is the file turn json names, as corpus_load reads it`() {
        val name = "2026-10-06T14:22:00.000Z-0dd5e7"
        val dir = File(corpus, name).apply { mkdirs() }
        File(dir, "take.wav").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "audio.wav").writeBytes(byteArrayOf(9))
        File(dir, "turn.json").writeText("""{"audio": {"file": "take.wav"}}""")
        assertEquals(3, CorpusDirectory(corpus).offer(name).bytes)
        File(dir, "turn.json").writeText("""{"audio": {"file": "../take.wav"}}""")
        assertEquals(1, CorpusDirectory(corpus).offer(name).bytes)
    }

    @Test
    fun `a Turn that cannot be read is an IOException`() {
        val name = "2026-10-06T14:22:00.000Z-0dd5e7"
        val dir = File(corpus, name).apply { mkdirs() }
        File(dir, "turn.json").writeText("{not json")
        assertThrows<IOException> { CorpusDirectory(corpus).offer(name) }
        File(dir, "turn.json").writeText("{}")
        assertThrows<IOException> { CorpusDirectory(corpus).offer(name) } // no audio.wav
        assertThrows<IOException> { CorpusDirectory(corpus).offer("../$name") }
    }

    @Test
    fun `offering reads the Turn and changes nothing`() {
        val dir = writeFixtureTurn()
        val before = dir.listFiles()!!.associate { it.name to (it.readBytes().toList() to it.lastModified()) }
        CorpusDirectory(corpus).apply {
            offer(fixtureOffer.turn)
            audio(fixtureOffer.turn, 0, 60)
        }
        assertEquals(before, dir.listFiles()!!.associate { it.name to (it.readBytes().toList() to it.lastModified()) })
    }

    @Test
    fun `the ledger keeps whole lines only`() {
        val file = File(root, "hub/uploaded.txt")
        val ledger = UploadLedgerFile(file)
        assertEquals(emptySet<String>(), ledger.uploaded())
        ledger.add("2026-10-06T14:22:00.000Z-0dd5e7")
        ledger.add("2026-10-06T14:22:07.431Z-a3f1c9")
        file.appendText("2026-10-06T14:23:00.000Z-b4") // a crash mid-append
        assertEquals(setOf("2026-10-06T14:22:00.000Z-0dd5e7", "2026-10-06T14:22:07.431Z-a3f1c9"), UploadLedgerFile(file).uploaded())
    }
}
