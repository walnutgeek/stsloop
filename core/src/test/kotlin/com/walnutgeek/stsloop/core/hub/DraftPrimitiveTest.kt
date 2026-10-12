package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.BucketSource
import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.DraftCommand
import com.walnutgeek.stsloop.core.Transcript
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnAudio
import com.walnutgeek.stsloop.core.TurnInProgress
import com.walnutgeek.stsloop.core.TurnJson
import com.walnutgeek.stsloop.core.TurnKind
import com.walnutgeek.stsloop.core.TurnVad
import com.walnutgeek.stsloop.core.corpus.Json
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `draft` Primitive at the `:core` seam (#45): under an agent Bucket's
 * Focus (the hub's `descriptors/herdr.json`), Turns that are no command make
 * a Draft; "read it back", "cancel" and "send" act on it, and a send goes to
 * the hub with an idempotency key that a reconnect resends unchanged.
 */
class DraftPrimitiveTest {
    private val herdr = Protocol.decode(Fixtures.text("v1", "descriptors/herdr.json")) as Descriptors

    private class Writer : CorpusWriter {
        override fun begin(id: String, sessionId: String, startedAtMs: Long, sampleRate: Int) = object : TurnInProgress {
            override fun append(samples: ShortArray, count: Int) {}
            override fun finish(appVersion: String, vad: TurnVad?, transcript: Transcript?, classification: Classification?, test: TurnTest?) =
                Turn(id, sessionId, startedAtMs, TurnAudio("audio.wav", "x", sampleRate, 100), appVersion, vad, transcript, classification)
            override fun abandon() {}
        }
    }

    /** The phone's side of one Session: what the loop says, the Turns it writes, what reaches the hub. */
    private inner class Session(online: Boolean = true, private val readBackHeard: Boolean = true) {
        val said = mutableListOf<String>()
        val turns = mutableListOf<Turn>()
        val sent = mutableListOf<Message>()
        var online = online
        var nowMs = 0L
        var at = 0L
        private var ids = 0
        private var keys = 0
        val drafts = Drafts(
            send = { m -> online && sent.add(m) },
            say = { said += it },
            clockMs = { nowMs },
            newKey = { "key-%028d".format(++keys) },
        )
        val grammar = PhraseGrammar(HubProvider.registry(BucketConfig.DEFAULT, herdr))
        val focus = Focus({ grammar.buckets }, timeoutMs = 120_000)
        val sink = CorpusSink(
            Writer(), "s", 0, 1000, "t", grammar, focus,
            newId = { "%06x".format(++ids) },
            hubOnline = { this.online },
            drafts = drafts,
            listener = object : CorpusSink.Listener {
                override fun published(turn: Turn, utterance: Utterance, timing: SttTiming?, say: String?) {
                    turns += turn
                    if (say != null) said += say
                }

                override fun readBack(turn: Turn, say: String, send: () -> Unit, withdraw: () -> Unit) {
                    said += say
                    if (readBackHeard) send() else withdraw()
                }
            },
        )

        fun hear(text: String) {
            val u = Utterance(at, at + 500, 400, 100, CloseReason.SILENCE)
            at += 1000
            sink.closed(u, ShortArray(500), Transcription(Transcript(text, "e", "m", 0, 0), SttTiming(1, 1, 1)))
        }

        /** The hub answers the last send with [reason] (null: delivered). */
        fun hubAnswers(reason: String? = null, say: String = "sent to stsloop agent.") =
            drafts.sent(Sent((sent.last() as Send).key, say, reason))

        fun draft() = drafts.text("herdr:w8:p1")
    }

    // --- the Draft ------------------------------------------------------------------

    @Test
    fun `under an agent's Focus, Turns that are no command make its Draft`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("UM REBASE ONTO MAIN")
        s.hear("AND RUN THE TESTS")
        assertEquals("rebase onto main and run the tests", s.draft())
        val turn = s.turns[1]
        assertEquals(TurnKind.DRAFT, turn.kind)
        assertEquals(Classification.drafted("stsloop agent", "herdr:w8:p1", "rebase onto main"), turn.classification)
        assertEquals(BucketSource.FOCUS, turn.classification!!.bucketSource)
        assertEquals(listOf("stsloop agent.", "UM REBASE ONTO MAIN", "AND RUN THE TESTS"), s.said, "each Turn is echoed as usual")
        assertTrue(s.sent.isEmpty(), "nothing reaches the hub before send")
    }

    @Test
    fun `a Declared Turn, a command, fillers and the Inbox do not touch the Draft`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("ERRANDS ORDER SCREWS")
        s.hear("UM")
        s.hear("STATUS")
        assertNull(s.draft())
        assertEquals(TurnKind.NOTE, s.turns[1].kind)
        assertEquals(TurnKind.UNCLASSIFIED, s.turns[2].kind)
        s.hear("INBOX")
        s.hear("REBASE")
        assertNull(s.draft())
    }

    @Test
    fun `read it back says the Draft, and cancel drops it`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("READ IT BACK")
        assertEquals(SessionCommands.NO_DRAFT, s.said.last())
        s.hear("REBASE ONTO MAIN")
        s.hear("READ IT BACK")
        assertEquals("draft for stsloop agent: rebase onto main.", s.said.last())
        val readBack = s.turns.last().classification!!.command!!
        assertEquals(Command.READ_BACK, readBack.command)
        assertEquals(DraftCommand("stsloop agent", "herdr:w8:p1", "rebase onto main"), readBack.draft)
        s.hear("CANCEL")
        assertEquals(SessionCommands.DRAFT_DROPPED, s.said.last())
        assertEquals("rebase onto main", s.turns.last().classification!!.command!!.draft!!.text, "cancel records what it dropped")
        assertNull(s.draft())
        s.hear("CANCEL")
        assertEquals(SessionCommands.NO_DRAFT, s.said.last())
    }

    @Test
    fun `scratch that takes the last Turn back out of the Draft`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("REBASE ONTO MAIN")
        s.hear("AND DELETE EVERYTHING")
        s.hear("SCRATCH THAT")
        assertEquals(SessionCommands.DROPPED, s.said.last())
        assertEquals("rebase onto main", s.draft())
    }

    @Test
    fun `the draft commands are heard only under their agent's Focus`() {
        val s = Session()
        s.hear("SEND")
        assertEquals(TurnKind.UNCLASSIFIED, s.turns.last().kind)
        s.hear("ERRANDS")
        s.hear("CANCEL")
        assertEquals(TurnKind.NOTE, s.turns.last().kind, "in a capture Bucket it is a Note")
    }

    // --- send -------------------------------------------------------------------------

    @Test
    fun `send reads the Draft back, then sends it with a key, and a delivered answer drops it`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("REBASE ONTO MAIN AND RUN THE TESTS")
        s.hear("SEND")
        assertEquals("sending to stsloop agent: rebase onto main and run the tests.", s.said.last())
        val send = s.sent.single() as Send
        assertEquals(Send("key-0000000000000000000000000001", "herdr:w8:p1", "rebase onto main and run the tests"), send)
        assertEquals(Fixtures.json("v1", "send/basic.json"), Json.parse(Protocol.encode(send.copy(key = "7c1e0a4b9d2f4e6a8b3c5d7e9f1a2b4c"))))
        val command = s.turns.last().classification!!.command!!
        assertEquals(Command.SEND, command.command)
        assertEquals(DraftCommand("stsloop agent", "herdr:w8:p1", "rebase onto main and run the tests", send.key), command.draft)
        assertNull(s.draft(), "taken into the send")
        assertTrue(s.drafts.sending("herdr:w8:p1"))
        s.drafts.sent(Protocol.decode(Fixtures.text("v1", "sent/delivered.json")) as Sent) // another key: not ours
        assertTrue(s.drafts.sending("herdr:w8:p1"))
        s.hubAnswers()
        assertEquals("sent to stsloop agent.", s.said.last())
        assertFalse(s.drafts.sending("herdr:w8:p1"))
        assertNull(s.draft())
    }

    @Test
    fun `nothing is sent until the readback is over, and not at all if it is never heard`() {
        val s = Session(readBackHeard = false)
        s.hear("STSLOOP")
        s.hear("REBASE")
        s.hear("SEND")
        assertTrue(s.sent.isEmpty())
        assertEquals("rebase", s.draft(), "the Draft is kept")
    }

    @Test
    fun `a Draft the hub did not deliver is kept, in front of anything said since`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("REBASE")
        s.hear("SEND")
        s.hear("THEN MERGE")
        assertEquals("then merge", s.draft(), "Turns said meanwhile start a new Draft")
        s.hubAnswers("refused", "stsloop agent is waiting on a question, so it was not sent.")
        assertEquals("stsloop agent is waiting on a question, so it was not sent. ${Drafts.DRAFT_KEPT}", s.said.last())
        assertEquals("rebase then merge", s.draft())
        s.hear("SEND")
        assertEquals("key-0000000000000000000000000002", (s.sent.last() as Send).key, "a new send is a new key")
    }

    @Test
    fun `an uncertain answer keeps the Draft and warns that sending it again may send it twice`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("REBASE")
        s.hear("SEND")
        s.hubAnswers(Drafts.UNCERTAIN, "herdr did not confirm that stsloop agent got it.")
        assertEquals("herdr did not confirm that stsloop agent got it. ${Drafts.MAYBE_ARRIVED}", s.said.last())
        assertEquals("rebase", s.draft())
    }

    @Test
    fun `an answer the phone does not know the reason of keeps the Draft`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("REBASE")
        s.hear("SEND")
        s.hubAnswers("a_reason_from_the_future", "hm.")
        assertEquals("rebase", s.draft())
    }

    @Test
    fun `with the hub offline, send says so and keeps the Draft`() {
        val s = Session(online = false)
        s.hear("STSLOOP")
        s.hear("REBASE")
        s.hear("SEND")
        assertEquals(Drafts.OFFLINE, s.said.last())
        assertEquals("hub offline, draft kept.", Drafts.OFFLINE)
        assertEquals("rebase", s.draft())
        assertTrue(s.sent.isEmpty())
        assertNull(s.turns.last().classification!!.command!!.draft!!.key, "no key: nothing was sent")
    }

    @Test
    fun `a hub that goes away during the readback keeps the Draft too`() {
        // The link cannot send (not welcomed), though the loop does not yet count the hub as offline.
        val said = mutableListOf<String>()
        val d = Drafts(send = { false }, say = { said += it }, clockMs = { 0 })
        d.append("herdr:w8:p1", "stsloop agent", "t1", "rebase")
        val key = d.newKey()
        assertEquals("rebase", d.take("herdr:w8:p1", key))
        d.dispatch(key)
        assertEquals(listOf(Drafts.OFFLINE), said)
        assertEquals("rebase", d.text("herdr:w8:p1"))
        assertFalse(d.sending("herdr:w8:p1"))
        assertTrue(Regex("[0-9a-f]{32}").matches(key), "a real key is 128 random bits")
    }

    @Test
    fun `send with no Draft, or while the last send waits, sends nothing new`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("SEND")
        assertEquals(SessionCommands.NOTHING_TO_SEND, s.said.last())
        s.hear("REBASE")
        s.hear("SEND")
        s.hear("MERGE")
        s.hear("SEND")
        assertEquals(SessionCommands.STILL_SENDING, s.said.last())
        assertEquals(1, s.sent.size)
        assertEquals("merge", s.draft())
    }

    @Test
    fun `an unanswered send goes again on every welcome, with the same key, and is said once`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("REBASE")
        s.hear("SEND")
        val send = s.sent.single() as Send
        s.nowMs = Drafts.UNCONFIRMED_MS - 1
        s.drafts.tick()
        assertFalse(Drafts.NOT_CONFIRMED in s.said)
        s.nowMs = Drafts.UNCONFIRMED_MS
        s.drafts.tick()
        s.drafts.tick()
        assertEquals(1, s.said.count { it == Drafts.NOT_CONFIRMED })
        s.drafts.welcomed() // the connection dropped and came back
        s.drafts.welcomed()
        assertEquals(listOf(send, send, send), s.sent, "the same key each time: the hub delivers it at most once")
        s.hubAnswers()
        s.drafts.hubAnswersAgain(send.key)
        s.drafts.welcomed()
        assertEquals(3, s.sent.size, "answered: never sent again")
        assertEquals(1, s.said.count { it == "sent to stsloop agent." }, "a second answer to the same key is ignored")
    }

    private fun Drafts.hubAnswersAgain(key: String) = sent(Sent(key, "sent to stsloop agent."))

    // --- Focus ---------------------------------------------------------------------------

    @Test
    fun `a Draft left at the focus timeout is kept and announced, never sent`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("REBASE")
        assertTrue(s.focus.tick(500 + 1000 + 120_000, capturing = false))
        assertEquals("draft kept for stsloop agent. back to inbox.", s.drafts.timedOut(s.focus.left))
        assertTrue(s.sent.isEmpty())
        s.at = 200_000 // said after the timeout
        s.hear("SEND")
        assertEquals(TurnKind.UNCLASSIFIED, s.turns.last().kind, "in the Inbox, send is not a command")
        assertTrue(s.sent.isEmpty())
        s.hear("STSLOOP")
        assertEquals("stsloop agent. ${SessionCommands.DRAFT_WAITING}", s.said.last())
        assertEquals("rebase", s.draft())
    }

    @Test
    fun `a timeout without a Draft says only back to inbox`() {
        val s = Session()
        s.hear("STSLOOP")
        s.focus.tick(500 + 120_000, capturing = false)
        assertEquals(Focus.TIMED_OUT, s.drafts.timedOut(s.focus.left))
        assertEquals(Focus.TIMED_OUT, s.drafts.timedOut(null))
    }

    @Test
    fun `each agent has its own Draft`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("REBASE")
        s.hear("WOODGLUE")
        s.hear("SAND IT")
        assertEquals("rebase", s.draft())
        assertEquals("sand it", s.drafts.text("herdr:w4:p1"))
    }

    // --- the Corpus ------------------------------------------------------------------------

    @Test
    fun `Draft Turns and sends are recorded in turn json`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("REBASE")
        s.hear("SEND")
        val draft = Json.parse(TurnJson.encode(s.turns[1])) as Map<*, *>
        assertEquals("draft", draft["kind"])
        assertEquals("stsloop agent", draft["bucket"])
        assertEquals("focus", draft["bucket_source"])
        assertEquals("rebase", draft["content"])
        assertEquals("herdr:w8:p1", draft["bucket_id"])
        val send = Json.parse(TurnJson.encode(s.turns[2])) as Map<*, *>
        assertEquals(mapOf("name" to "send", "matched" to "send"), send["command"])
        assertEquals(
            mapOf("bucket" to "stsloop agent", "bucket_id" to "herdr:w8:p1", "text" to "rebase", "key" to "key-0000000000000000000000000001"),
            send["draft"],
        )
        assertNull(send["bucket"])
        s.hear("READ IT BACK")
        val readBack = Json.parse(TurnJson.encode(s.turns[3])) as Map<*, *>
        assertEquals(mapOf("bucket" to "stsloop agent", "bucket_id" to "herdr:w8:p1", "text" to null), readBack["draft"], "no key but on send")
    }
}
