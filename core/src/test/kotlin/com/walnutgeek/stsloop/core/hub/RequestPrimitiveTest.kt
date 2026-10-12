package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.BucketRequest
import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.Transcript
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnAudio
import com.walnutgeek.stsloop.core.TurnInProgress
import com.walnutgeek.stsloop.core.TurnKind
import com.walnutgeek.stsloop.core.TurnVad
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.grammar.Bucket
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.testmode.TurnTest
import com.walnutgeek.stsloop.core.turn.CloseReason
import com.walnutgeek.stsloop.core.turn.CorpusSink
import com.walnutgeek.stsloop.core.turn.Focus
import com.walnutgeek.stsloop.core.turn.SessionCommands
import com.walnutgeek.stsloop.core.turn.SttTiming
import com.walnutgeek.stsloop.core.turn.Transcription
import com.walnutgeek.stsloop.core.turn.Utterance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `request` Primitive at the `:core` seam: a Bucket's command phrases
 * come from its Descriptor (here the hub's `descriptors/herdr.json`), the
 * phone sends the command to the hub, and speaks the reply when it comes.
 */
class RequestPrimitiveTest {
    private val herdr = Protocol.decode(Fixtures.text("v1", "descriptors/herdr.json")) as Descriptors
    private val agents = Protocol.decode(Fixtures.text("v1", "descriptors/agents.json")) as Descriptors

    private fun grammar(hub: Descriptors? = herdr, config: BucketConfig = BucketConfig.DEFAULT) =
        PhraseGrammar(HubProvider.registry(config, hub))

    // --- the grammar --------------------------------------------------------------

    @Test
    fun `a focused Bucket's command is a request naming the Bucket and the command`() {
        val c = grammar().classify("Status.", focused = "stsloop agent").command!!
        assertEquals(Command.REQUEST, c.command)
        assertEquals("status", c.matched)
        assertEquals(BucketRequest("stsloop agent", "herdr:w8:p1", "status"), c.request)
    }

    @Test
    fun `a Bucket's command is not one while another Bucket has Focus`() {
        val g = grammar()
        assertEquals(Classification.UNCLASSIFIED, g.classify("status"))
        assertEquals(Classification.UNCLASSIFIED, g.classify("status", focused = "inbox"))
        assertEquals("status", g.classify("status", focus = "errands").content, "in a capture Bucket it is a Note")
        assertEquals("herdr:w4:p1", g.classify("status", focused = "woodglue agent").command!!.request!!.bucketId)
    }

    @Test
    fun `an anywhere command is heard whatever has Focus`() {
        val g = grammar()
        for (focused in listOf(null, "inbox", "errands", "stsloop agent", "hub")) {
            for (said in listOf("what's blocked", "WHATS BLOCKED", "um, what is blocked?")) {
                val c = g.classify(said, focus = null, focused = focused).command
                assertEquals(BucketRequest("hub", "hub", "blocked"), c?.request, "$said under $focused")
            }
        }
    }

    @Test
    fun `the phrases come from the Descriptor, not from the phone`() {
        // A made-up Provider with a made-up command: nothing in the phone knows "how many tokens".
        val custom = Descriptors(listOf(HubDescriptor(
            "x:1", "builder", emptyList(), "agent", listOf("request"),
            listOf(HubCommand("tokens", listOf("how many tokens", "token count"), "request")),
        )))
        val g = grammar(custom)
        assertEquals(BucketRequest("builder", "x:1", "tokens"), g.classify("token count", focused = "builder").command!!.request)
        assertNull(grammar(herdr).classify("token count", focused = "stsloop agent").command)
    }

    @Test
    fun `the loop's own commands win over a Bucket's`() {
        val clash = Descriptors(listOf(HubDescriptor(
            "x:1", "builder", emptyList(), "agent", listOf("request"),
            listOf(HubCommand("again", listOf("repeat"), "request", anywhere = true)),
        )))
        val r = HubProvider.registry(BucketConfig.DEFAULT, clash)
        assertEquals(Command.REPEAT, PhraseGrammar(r).classify("repeat", focused = "builder").command!!.command)
        assertTrue(r.rejected.any { "loop command" in it }, r.rejected.toString())
    }

    @Test
    fun `an anywhere phrase two Buckets declare is dropped from both`() {
        val two = Descriptors(listOf("a", "b").map { n ->
            HubDescriptor("x:$n", "builder $n", emptyList(), "agent", listOf("request"), listOf(HubCommand("q", listOf("what's up"), "request", anywhere = true)))
        })
        val r = HubProvider.registry(BucketConfig.DEFAULT, two)
        assertNull(PhraseGrammar(r).classify("what's up").command)
        assertTrue(r.rejected.any { "what's up" in it || "whats up" in it }, r.rejected.toString())
    }

    @Test
    fun `a Bucket naming a Primitive the phone does not know keeps only its request commands`() {
        val g = grammar(agents)
        assertEquals("status", g.classify("status", focused = "stsloop agent").command!!.request!!.command)
        assertEquals("read", g.classify("read it", focused = "stsloop agent").command!!.request!!.command)
        assertNull(g.classify("send", focused = "stsloop agent").command, "send is a draft command, which the phone cannot run yet")
    }

    @Test
    fun `focusing a hub Bucket records its Descriptor id`() {
        val c = grammar().classify("stsloop").command!!
        assertEquals("stsloop agent", c.focus)
        assertEquals("herdr:w8:p1", c.focusId)
        assertNull(grammar().classify("errands").command!!.focusId)
    }

    // --- the loop, end to end at the seam -------------------------------------------

    private class Writer : CorpusWriter {
        override fun begin(id: String, sessionId: String, startedAtMs: Long, sampleRate: Int) = object : TurnInProgress {
            override fun append(samples: ShortArray, count: Int) {}
            override fun finish(appVersion: String, vad: TurnVad?, transcript: Transcript?, classification: Classification?, test: TurnTest?) =
                Turn(id, sessionId, startedAtMs, TurnAudio("audio.wav", "x", sampleRate, 100), appVersion, vad, transcript, classification)
            override fun abandon() {}
        }
    }

    /** The phone's side of one Session: what the loop says, the Turns it writes, what reaches the hub. */
    private inner class Session(online: Boolean = true, hub: Descriptors? = herdr, config: BucketConfig = BucketConfig.DEFAULT) {
        val said = mutableListOf<String>()
        val turns = mutableListOf<Turn>()
        val sent = mutableListOf<Message>()
        var online = online
        var nowMs = 0L
        var at = 0L
        private var ids = 0
        val requests = HubRequests(
            send = { m -> online && sent.add(m) },
            say = { said += it },
            clockMs = { nowMs },
            newId = { "r${++ids}" },
        )
        val grammar = grammar(hub, config)
        val focus = Focus({ grammar.buckets }, timeoutMs = 120_000)
        val sink = CorpusSink(
            Writer(), "s", 0, 1000, "t", grammar, focus,
            hubOnline = { this.online },
            request = requests::request,
            listener = object : CorpusSink.Listener {
                override fun published(turn: Turn, utterance: Utterance, timing: SttTiming?, say: String?) {
                    turns += turn
                    if (say != null) said += say
                }
            },
        )

        fun hear(text: String) {
            val u = Utterance(at, at + 500, 400, 100, CloseReason.SILENCE)
            at += 1000
            sink.closed(u, ShortArray(500), Transcription(Transcript(text, "e", "m", 0, 0), SttTiming(1, 1, 1)))
        }

        fun hubSays(rel: String) = requests.replied(Protocol.decode(Fixtures.text("v1", rel)) as Reply)
    }

    @Test
    fun `status, focused on an agent, is sent to the hub and its reply is said`() {
        val s = Session()
        s.hear("STSLOOP")
        assertEquals(listOf("stsloop agent."), s.said)
        s.hear("STATUS")
        assertEquals(listOf("stsloop agent."), s.said, "nothing is said until the hub answers")
        assertEquals(listOf(Request("r1", "herdr:w8:p1", "status")), s.sent)
        val turn = s.turns.last()
        assertEquals(TurnKind.COMMAND, turn.kind)
        assertEquals(BucketRequest("stsloop agent", "herdr:w8:p1", "status"), turn.classification!!.command!!.request)
        s.hubSays("reply/status.json") // id a3f1c9: not ours
        assertEquals(1, s.requests.pending)
        s.requests.replied(Reply("r1", "stsloop agent is blocked, for 3 minutes."))
        assertEquals("stsloop agent is blocked, for 3 minutes.", s.said.last())
        assertEquals(0, s.requests.pending)
    }

    @Test
    fun `what's blocked works from the Inbox, and the request is the fixture's`() {
        val s = Session()
        s.hear("WHAT'S BLOCKED")
        assertEquals(1, s.sent.size)
        val request = s.sent.single() as Request
        assertEquals(Fixtures.json("v1", "request/blocked.json"), Json.parse(Protocol.encode(request.copy(id = "a3f1ca"))))
        s.requests.replied(Reply(request.id, "nothing is blocked."))
        assertEquals(listOf("nothing is blocked."), s.said)
        assertEquals("inbox", s.focus.bucket.name, "an anywhere command does not move Focus")
    }

    @Test
    fun `with the hub offline the loop says so at once, and nothing waits`() {
        val s = Session(online = false)
        s.hear("STSLOOP")
        s.hear("STATUS")
        assertEquals(listOf("stsloop agent. ${SessionCommands.HUB_OFFLINE}", SessionCommands.HUB_OFFLINE), s.said)
        assertEquals(0, s.requests.pending)
        assertEquals(Command.REQUEST, s.turns.last().classification!!.command!!.command, "the Turn is still a request command")
    }

    @Test
    fun `a reply that does not come in time is given up on, and a late one is not said`() {
        val s = Session()
        s.hear("WHAT IS BLOCKED")
        s.nowMs = HubRequests.REPLY_TIMEOUT_MS - 1
        s.requests.tick()
        assertEquals(emptyList<String>(), s.said)
        s.nowMs = HubRequests.REPLY_TIMEOUT_MS
        s.requests.tick()
        assertEquals(listOf(HubRequests.NO_ANSWER), s.said)
        s.requests.replied(Reply("r1", "nothing is blocked."))
        assertEquals(listOf(HubRequests.NO_ANSWER), s.said)
    }

    @Test
    fun `several requests given up on at once say so once`() {
        val s = Session()
        s.hear("WHAT IS BLOCKED")
        s.hear("WHAT IS BLOCKED")
        s.nowMs = HubRequests.REPLY_TIMEOUT_MS
        s.requests.tick()
        assertEquals(listOf(HubRequests.NO_ANSWER), s.said)
    }

    @Test
    fun `a command is read against the Focus its utterance ended in, even if Focus timed out since`() {
        val s = Session()
        s.hear("STSLOOP") // ends at 500 ms
        s.focus.tick(500 + 120_000, capturing = false)
        assertEquals("inbox", s.focus.bucket.name)
        s.at = 1000 // an utterance that ended at 1.5 s, published after the timeout
        s.hear("STATUS")
        assertEquals("herdr:w8:p1", (s.sent.single() as Request).bucket)
    }

    @Test
    fun `focusing a Bucket that needs a newer app says so once per Session`() {
        val s = Session(hub = agents)
        s.hear("STSLOOP")
        s.hear("ERRANDS")
        s.hear("THE LOOP AGENT")
        assertEquals(listOf("stsloop agent. ${SessionCommands.NEEDS_NEWER_APP}", "errands.", "stsloop agent."), s.said)
        s.hear("HOUSE SITE")
        assertEquals("house site. ${SessionCommands.NEEDS_NEWER_APP}", s.said.last(), "once per Bucket: speak_item is not known yet either")
    }

    @Test
    fun `a capture Bucket's alias that is an anywhere phrase is heard as the command`() {
        val s = Session(config = BucketConfig(listOf(Bucket("blocked", listOf("what's blocked")))))
        assertTrue(s.grammar.buckets.rejected.any { "no longer names" in it }, s.grammar.buckets.rejected.toString())
        s.hear("WHAT'S BLOCKED")
        assertEquals("blocked", (s.sent.single() as Request).command)
    }
}
