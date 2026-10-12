package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.Transcript
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnAudio
import com.walnutgeek.stsloop.core.TurnInProgress
import com.walnutgeek.stsloop.core.TurnKind
import com.walnutgeek.stsloop.core.TurnVad
import com.walnutgeek.stsloop.core.grammar.Bucket
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.grammar.Primitive
import com.walnutgeek.stsloop.core.testmode.TurnTest
import com.walnutgeek.stsloop.core.turn.CloseReason
import com.walnutgeek.stsloop.core.turn.CorpusSink
import com.walnutgeek.stsloop.core.turn.Focus
import com.walnutgeek.stsloop.core.turn.SessionCommands
import com.walnutgeek.stsloop.core.turn.SttTiming
import com.walnutgeek.stsloop.core.turn.Transcription
import com.walnutgeek.stsloop.core.turn.Utterance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Hub Buckets join the registry (ADR-0005): named, they take Focus like a capture Bucket, cached or live. */
class HubBucketsTest {
    private val stub = Protocol.decode(Fixtures.text("v1", "descriptors/stub.json")) as Descriptors
    private val agents = Protocol.decode(Fixtures.text("v1", "descriptors/agents.json")) as Descriptors
    private val future = Protocol.decode(Fixtures.text("v1", "descriptors/future-primitive.json")) as Descriptors

    @Test
    fun `hub Buckets come after the capture Buckets, marked as the hub's`() {
        val r = HubProvider.registry(BucketConfig.DEFAULT, stub)
        assertEquals(listOf("inbox", "errands", "house-project", "work", "ideas", "hub"), r.buckets.map { it.name })
        val hub = r.named("hub")!!
        assertTrue(hub.hub)
        assertEquals(listOf("the hub"), hub.aliases)
        assertFalse(r.named("errands")!!.hub)
        assertFalse(r.takesNotes(hub), "the stub uses no Primitive, so nothing is filed in it")
    }

    @Test
    fun `with no Descriptors cached there are only the capture Buckets`() =
        assertEquals(listOf("inbox", "errands", "house-project", "work", "ideas"), HubProvider.registry(BucketConfig.DEFAULT, null).buckets.map { it.name })

    @Test
    fun `only the Primitives the phone knows are kept, and the Bucket stays`() {
        val podcast = HubProvider.registry(BucketConfig.DEFAULT, future).named("the daily show")!!
        assertEquals(setOf(Primitive.REQUEST), podcast.primitives)
        assertTrue(podcast.degraded)
        assertEquals(listOf("status"), podcast.commands.map { it.id }, "only its request commands are kept")
    }

    @Test
    fun `a hub Bucket whose name a capture Bucket has is dropped, and reported`() {
        val config = BucketConfig(listOf(Bucket("hub", emptyList())))
        val r = HubProvider.registry(config, stub)
        assertFalse(r.named("hub")!!.hub)
        assertTrue(r.rejected.any { "hub" in it }, r.rejected.toString())
    }

    @Test
    fun `naming the stub hub Bucket, or its alias, focuses it`() {
        val grammar = PhraseGrammar(HubProvider.registry(BucketConfig.DEFAULT, stub))
        for (said in listOf("Hub.", "the hub", "um, the hub")) {
            val c = grammar.classify(said, focus = null).command!!
            assertEquals(Command.FOCUS, c.command, said)
            assertEquals("hub", c.focus)
        }
        assertEquals(Classification.UNCLASSIFIED, grammar.classify("hub buy milk"), "a Bucket without note is never Declared into")
    }

    @Test
    fun `a new snapshot replaces the hub Buckets the grammar hears`() {
        val grammar = PhraseGrammar(HubProvider.registry(BucketConfig.DEFAULT, stub))
        grammar.replace(HubProvider.registry(BucketConfig.DEFAULT, agents))
        assertEquals("stsloop agent", grammar.classify("the loop agent").command!!.focus)
        assertNull(grammar.classify("the hub").command)
    }

    @Test
    fun `Focus follows the grammar's Buckets, and ignores a Bucket that has gone`() {
        val grammar = PhraseGrammar(HubProvider.registry(BucketConfig.DEFAULT, null))
        val focus = Focus({ grammar.buckets }, timeoutMs = 120_000)
        assertFalse(focus.set("hub", atMs = 0))
        assertEquals("inbox", focus.bucket.name)
        grammar.replace(HubProvider.registry(BucketConfig.DEFAULT, stub))
        assertTrue(focus.set("hub", atMs = 0))
        assertEquals("hub", focus.bucket.name)
    }

    // --- the loop's replies -----------------------------------------------------

    private class Writer : CorpusWriter {
        override fun begin(id: String, sessionId: String, startedAtMs: Long, sampleRate: Int) = object : TurnInProgress {
            override fun append(samples: ShortArray, count: Int) {}
            override fun finish(appVersion: String, vad: TurnVad?, transcript: Transcript?, classification: Classification?, test: TurnTest?) =
                Turn(id, sessionId, startedAtMs, TurnAudio("audio.wav", "x", sampleRate, 100), appVersion, vad, transcript, classification)
            override fun abandon() {}
        }
    }

    private val said = mutableListOf<String?>()
    private val turns = mutableListOf<Turn>()
    private var at = 0L

    private fun sink(online: () -> Boolean, hub: Descriptors? = stub) = CorpusSink(
        Writer(), "s", 0, 1000, "t", PhraseGrammar(HubProvider.registry(BucketConfig.DEFAULT, hub)),
        hubOnline = online,
        listener = object : CorpusSink.Listener {
            override fun published(turn: Turn, utterance: Utterance, timing: SttTiming?, say: String?) {
                turns += turn
                said += say
            }
        },
    )

    private fun CorpusSink.hear(text: String) {
        val u = Utterance(at, at + 500, 400, 100, CloseReason.SILENCE)
        at += 1000
        closed(u, ShortArray(500), Transcription(Transcript(text, "e", "m", 0, 0), SttTiming(1, 1, 1)))
    }

    @Test
    fun `naming the hub Bucket says its name while the hub is online`() {
        val s = sink(online = { true })
        s.hear("THE HUB")
        assertEquals(listOf("hub."), said)
        assertEquals("hub", turns.single().classification!!.command!!.focus)
        s.hear("WHERE AM I")
        assertEquals("hub.", said.last())
    }

    @Test
    fun `with the hub down, a cached hub Bucket still takes Focus and the loop says hub offline`() {
        val s = sink(online = { false })
        s.hear("HUB")
        assertEquals("hub. ${SessionCommands.HUB_OFFLINE}", said.single())
        s.hear("WHERE AM I")
        assertEquals("hub. ${SessionCommands.HUB_OFFLINE}", said.last())
        s.hear("ROOF NEEDS SCREWS")
        assertEquals(TurnKind.UNCLASSIFIED, turns.last().kind, "the stub takes no Notes")
        assertEquals("ROOF NEEDS SCREWS", said.last())
    }

    @Test
    fun `capture Buckets never mention the hub`() {
        val s = sink(online = { false })
        s.hear("ERRANDS")
        assertEquals("errands.", said.single())
    }

    @Test
    fun `an offline hub never stalls turn-taking, the loop only reads the link status`() {
        // A link that has never connected, whose transport would fail the test if the loop touched it.
        val link = HubLink(
            Protocol.decode(Fixtures.text("v1", "pairing/basic.json")) as Pairing,
            { _, _, _ -> error("the loop must not connect") },
            { _, _ -> error("the loop must not schedule") },
        )
        val s = sink(online = { link.online })
        repeat(3) { s.hear("THE HUB") }
        assertEquals(List(3) { "hub. ${SessionCommands.HUB_OFFLINE}" }, said)
    }
}
