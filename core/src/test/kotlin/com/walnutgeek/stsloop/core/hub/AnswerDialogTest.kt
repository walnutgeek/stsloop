package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.Transcript
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnAudio
import com.walnutgeek.stsloop.core.TurnInProgress
import com.walnutgeek.stsloop.core.TurnJson
import com.walnutgeek.stsloop.core.TurnVad
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.Dialog
import com.walnutgeek.stsloop.core.grammar.DialogQuestion
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
 * Answering a blocked agent's question dialog by voice (#80), at the `:core`
 * seam: the hub's `descriptors/dialogs.json` shows woodglue agent asking two
 * questions, stsloop agent at a permission prompt and direction wiki agent
 * blocked on a dialog the hub cannot tell. "option one" … and "my answer"
 * pick each question's answer, in order, read back; only "send it" sends the
 * whole answer, with an idempotency key a reconnect resends unchanged.
 */
class AnswerDialogTest {
    private val dialogs = Protocol.decode(Fixtures.text("v1", "descriptors/dialogs.json")) as Descriptors

    private class Writer : CorpusWriter {
        override fun begin(id: String, sessionId: String, startedAtMs: Long, sampleRate: Int) = object : TurnInProgress {
            override fun append(samples: ShortArray, count: Int) {}
            override fun finish(appVersion: String, vad: TurnVad?, transcript: Transcript?, classification: Classification?, test: TurnTest?) =
                Turn(id, sessionId, startedAtMs, TurnAudio("audio.wav", "x", sampleRate, 100), appVersion, vad, transcript, classification)
            override fun abandon() {}
        }
    }

    /** The phone's side of one Session: what the loop says, the Turns it writes, what reaches the hub. */
    private inner class Session(online: Boolean = true, snapshot: Descriptors = dialogs) {
        val said = mutableListOf<String>()
        val turns = mutableListOf<Turn>()
        val sent = mutableListOf<Message>()
        var online = online
        private var at = 0L
        private var ids = 0
        private var keys = 0
        val drafts = Drafts(
            send = { m -> this.online && sent.add(m) },
            say = { said += it },
            clockMs = { 0 },
            newKey = { "key-%028d".format(++keys) },
        )
        val grammar = PhraseGrammar(HubProvider.registry(BucketConfig.DEFAULT, snapshot))
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
                    send()
                }
            },
        )

        fun hear(vararg texts: String) {
            for (text in texts) {
                val u = Utterance(at, at + 500, 400, 100, CloseReason.SILENCE)
                at += 1000
                sink.closed(u, ShortArray(500), Transcription(Transcript(text, "e", "m", 0, 0), SttTiming(1, 1, 1)))
            }
        }

        /** The hub's next snapshot. */
        fun snapshot(next: Descriptors) = grammar.replace(HubProvider.registry(BucketConfig.DEFAULT, next))

        fun hubAnswers(reason: String? = null, say: String = "answered woodglue agent.") =
            drafts.sent(Sent((sent.last() as Send).key, say, reason))
    }

    private val two = "question two: Which test suite should run after? option one: Unit; option two: Device; option three: Both."

    @Test
    fun `the dialog comes from the Descriptor`() {
        val woodglue = HubProvider(dialogs).descriptors().single { it.id == "herdr:w4:p1" }
        assertEquals(
            Dialog(
                Dialog.QUESTION, "toolu_1792049416875",
                listOf(
                    DialogQuestion("Should I rebase onto main or merge it?", listOf("Rebase", "Merge"), other = true),
                    DialogQuestion("Which test suite should run after?", listOf("Unit", "Device", "Both"), other = true),
                ),
            ),
            woodglue.dialog,
        )
        assertEquals(Dialog.PERMISSION, HubProvider(dialogs).descriptors().single { it.id == "herdr:w8:p1" }.dialog!!.kind)
    }

    @Test
    fun `each question is answered in order, read back, and sent only on send it`() {
        val s = Session()
        s.hear("WOODGLUE", "OPTION ONE")
        assertEquals("option one: Rebase. $two", s.said.last(), "the choice, then the next question")
        s.hear("ONLY THE FAST ONES", "MY ANSWER")
        assertEquals("your answer: only the fast ones. say send it to answer.", s.said.last())
        assertNull(s.drafts.text("herdr:w4:p1"), "the Draft became the answer")
        assertTrue(s.sent.isEmpty(), "nothing goes before send it")
        s.hear("SEND IT")
        assertEquals("answering woodglue agent: option one: Rebase; your answer: only the fast ones.", s.said.last())
        val send = s.sent.single() as Send
        assertEquals(
            Send("key-0000000000000000000000000001", "herdr:w4:p1", "option one: Rebase; your answer: only the fast ones",
                Answer("toolu_1792049416875", listOf(Choice(option = 1), Choice(text = "only the fast ones")))),
            send,
        )
        // As the protocol fixture has it (the engine there wrote capitals).
        val asFixture = send.copy(key = "5d2c8e1f0a3b4c6d7e8f9a0b1c2d3e4f", text = "option one: Rebase; your answer: Only the fast ones",
            answer = send.answer!!.copy(choices = listOf(Choice(option = 1), Choice(text = "Only the fast ones"))))
        assertEquals(Fixtures.json("v1", "send/answer.json"), Json.parse(Protocol.encode(asFixture)))
        s.drafts.sent(Protocol.decode(Fixtures.text("v1", "sent/answered.json")) as Sent) // another key: not ours
        assertTrue(s.drafts.sending("herdr:w4:p1"))
        s.hubAnswers()
        assertEquals("answered woodglue agent.", s.said.last())
        assertFalse(s.drafts.sending("herdr:w4:p1"))
        assertEquals(emptyList<Choice>(), s.drafts.choices("herdr:w4:p1", "toolu_1792049416875"), "answered: nothing left")
    }

    @Test
    fun `an unanswered answer goes again on every welcome with the same key, so the hub types it once`() {
        val s = Session()
        s.hear("WOODGLUE", "OPTION TWO", "OPTION THREE", "SEND IT")
        val send = s.sent.single() as Send
        s.drafts.welcomed()
        s.drafts.welcomed()
        assertEquals(listOf(send, send, send), s.sent)
        s.hubAnswers()
        s.drafts.welcomed()
        assertEquals(3, s.sent.size, "answered: never again")
    }

    @Test
    fun `send it before every question is answered asks the next one`() {
        val s = Session()
        s.hear("WOODGLUE", "OPTION TWO", "SEND IT")
        assertEquals("question two is not answered yet. $two", s.said.last())
        assertTrue(s.sent.isEmpty())
        s.hear("READ IT BACK")
        assertEquals("answer for woodglue agent: option two: Merge. $two", s.said.last())
    }

    @Test
    fun `an option the question does not have, or my answer without a Draft, picks nothing`() {
        val s = Session()
        s.hear("WOODGLUE", "OPTION THREE")
        assertEquals(
            "there is no option three. question one: Should I rebase onto main or merge it? option one: Rebase; option two: Merge.",
            s.said.last(),
        )
        s.hear("MY ANSWER")
        assertEquals("dictate your answer first, then say my answer.", s.said.last())
        assertEquals(emptyList<Choice>(), s.drafts.choices("herdr:w4:p1", "toolu_1792049416875"))
    }

    @Test
    fun `a permission prompt and a dialog the hub cannot tell are never answered`() {
        val s = Session()
        s.hear("STSLOOP", "OPTION ONE")
        assertEquals(SessionCommands.PERMISSION_PROMPT, s.said.last())
        assertEquals("that's a permission prompt. I don't approve those.", SessionCommands.PERMISSION_PROMPT)
        s.hear("SEND IT")
        assertTrue(s.sent.isEmpty())
        s.hear("DIRECTION WIKI", "OPTION ONE")
        assertEquals("I can't tell what direction wiki agent is asking, so I can't answer it.", s.said.last())
        s.hear("MY ANSWER")
        assertEquals("I can't tell what direction wiki agent is asking, so I can't answer it.", s.said.last())
        assertTrue(s.sent.isEmpty())
    }

    @Test
    fun `an agent not asking anything has nothing to answer`() {
        val s = Session(snapshot = Protocol.decode(Fixtures.text("v1", "descriptors/herdr.json")) as Descriptors)
        s.hear("STSLOOP", "OPTION ONE")
        assertEquals("stsloop agent is not asking anything.", s.said.last())
    }

    @Test
    fun `a Draft sent while a question is open is not sent, and the loop says how to answer`() {
        val s = Session()
        s.hear("WOODGLUE", "SQUASH IT", "SEND IT")
        assertEquals("woodglue agent is asking a question. say an option, or my answer to answer with the draft.", s.said.last())
        assertTrue(s.sent.isEmpty())
        assertEquals("squash it", s.drafts.text("herdr:w4:p1"))
    }

    @Test
    fun `cancel drops the answer and gives the Draft its words back`() {
        val s = Session()
        s.hear("WOODGLUE", "SQUASH IT", "MY ANSWER", "CANCEL")
        assertEquals(SessionCommands.ANSWER_DROPPED, s.said.last())
        assertEquals("squash it", s.drafts.text("herdr:w4:p1"))
        s.hear("OPTION TWO")
        assertEquals("option two: Merge. $two", s.said.last(), "a new answer starts at question one")
    }

    @Test
    fun `an answer the hub did not type is kept, and an uncertain one is not`() {
        val s = Session()
        s.hear("WOODGLUE", "OPTION TWO", "FAST", "MY ANSWER", "SEND IT")
        s.hubAnswers("refused", "woodglue agent's screen does not show that question, so it was not sent.")
        assertEquals("woodglue agent's screen does not show that question, so it was not sent. ${Drafts.ANSWER_KEPT}", s.said.last())
        s.hear("SEND IT")
        assertEquals("key-0000000000000000000000000002", (s.sent.last() as Send).key, "sent again: a new key")
        s.hubAnswers(Drafts.UNCERTAIN, "herdr did not confirm that woodglue agent got the answer.")
        assertEquals("herdr did not confirm that woodglue agent got the answer. ${Drafts.ANSWER_MAYBE_ARRIVED}", s.said.last())
        assertEquals(emptyList<Choice>(), s.drafts.choices("herdr:w4:p1", "toolu_1792049416875"), "not kept: it cannot be typed twice")
        assertEquals("fast", s.drafts.text("herdr:w4:p1"), "its words go back to the Draft")
    }

    @Test
    fun `with the hub offline the answer is kept`() {
        val s = Session(online = false)
        s.hear("WOODGLUE", "OPTION TWO", "OPTION ONE", "SEND IT")
        assertEquals(Drafts.ANSWER_OFFLINE, s.said.last())
        assertTrue(s.sent.isEmpty())
        s.online = true
        s.hear("SEND IT")
        assertEquals("option two: Merge; option one: Unit", (s.sent.single() as Send).text)
    }

    @Test
    fun `an answer to a dialog that has gone is dropped when a new one opens`() {
        val s = Session()
        s.hear("WOODGLUE", "OPTION TWO")
        val woodglue = dialogs.buckets.single { it.id == "herdr:w4:p1" }
        val another = woodglue.copy(dialog = Dialog(Dialog.QUESTION, "toolu_another", listOf(DialogQuestion("Push now?", listOf("Yes", "No"), other = true))))
        s.snapshot(dialogs.copy(buckets = dialogs.buckets.map { if (it.id == woodglue.id) another else it }))
        s.hear("OPTION ONE")
        assertEquals("option one: Yes. say send it to answer.", s.said.last())
        s.hear("SEND IT")
        assertEquals(Answer("toolu_another", listOf(Choice(option = 1))), (s.sent.single() as Send).answer)
    }

    @Test
    fun `a dialog answered elsewhere gives back the Draft words my answer took`() {
        val s = Session()
        s.hear("WOODGLUE", "SQUASH IT", "MY ANSWER")
        assertNull(s.drafts.text("herdr:w4:p1"))
        // Answered at the keyboard: the hub's next snapshot shows no dialog.
        s.snapshot(dialogs.copy(buckets = dialogs.buckets.map { if (it.id == "herdr:w4:p1") it.copy(dialog = null) else it }))
        s.hear("READ IT BACK")
        assertEquals("draft for woodglue agent: squash it.", s.said.last())
        s.hear("OPTION ONE")
        assertEquals("woodglue agent is not asking anything.", s.said.last())
    }

    @Test
    fun `the guidance names the Descriptor's first send phrase`() {
        val woodglue = dialogs.buckets.single { it.id == "herdr:w4:p1" }
        val renamed = woodglue.copy(commands = woodglue.commands.map { if (it.id == "send") it.copy(phrases = listOf("answer it", "send it")) else it })
        val s = Session(snapshot = dialogs.copy(buckets = dialogs.buckets.map { if (it.id == woodglue.id) renamed else it }))
        s.hear("WOODGLUE", "OPTION ONE", "OPTION ONE")
        assertEquals("option one: Unit. say answer it to answer.", s.said.last())
    }

    @Test
    fun `choices and the answer sent are recorded in turn json`() {
        val s = Session()
        s.hear("WOODGLUE", "OPTION TWO", "OPTION THREE", "SEND IT")
        val option = Json.parse(TurnJson.encode(s.turns[1])) as Map<*, *>
        assertEquals(mapOf("name" to "option", "matched" to "option two"), option["command"])
        assertEquals(
            mapOf("bucket" to "woodglue agent", "bucket_id" to "herdr:w4:p1", "text" to "Merge", "option" to 2L, "dialog" to "toolu_1792049416875"),
            option["draft"],
        )
        val send = Json.parse(TurnJson.encode(s.turns[3])) as Map<*, *>
        assertEquals(Command.SEND.json, (send["command"] as Map<*, *>)["name"])
        assertEquals(
            mapOf("bucket" to "woodglue agent", "bucket_id" to "herdr:w4:p1", "text" to "option two: Merge; option three: Both",
                "key" to "key-0000000000000000000000000001", "dialog" to "toolu_1792049416875"),
            send["draft"],
        )
    }
}
