package com.walnutgeek.stsloop.core.summary

import com.walnutgeek.stsloop.core.BucketRequest
import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.SummaryCommand
import com.walnutgeek.stsloop.core.Transcript
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnAudio
import com.walnutgeek.stsloop.core.TurnInProgress
import com.walnutgeek.stsloop.core.TurnJson
import com.walnutgeek.stsloop.core.TurnKind
import com.walnutgeek.stsloop.core.TurnVad
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.TranscriptList
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.hub.Fixtures
import com.walnutgeek.stsloop.core.hub.HubProvider
import com.walnutgeek.stsloop.core.hub.HubRequests
import com.walnutgeek.stsloop.core.hub.Message
import com.walnutgeek.stsloop.core.hub.Protocol
import com.walnutgeek.stsloop.core.hub.Reply
import com.walnutgeek.stsloop.core.hub.Request
import com.walnutgeek.stsloop.core.hub.Summarize
import com.walnutgeek.stsloop.core.testmode.TurnTest
import com.walnutgeek.stsloop.core.turn.CloseReason
import com.walnutgeek.stsloop.core.turn.CorpusSink
import com.walnutgeek.stsloop.core.turn.Focus
import com.walnutgeek.stsloop.core.turn.SttTiming
import com.walnutgeek.stsloop.core.turn.Transcription
import com.walnutgeek.stsloop.core.turn.Utterance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "Summarize bucket" (#76) end to end at the `:core` seam: the phrases, the
 * Turn the command writes, the Notes of this very Session going to the hub,
 * the summary said, and the phone answering alone without the hub.
 */
class SummarizeBucketTest {
    private val herdr = Protocol.decode(Fixtures.text("v1", "descriptors/herdr.json")) as Descriptors
    private val sessionStart = 1_760_421_600_000L // 2025-10-14T06:00:00Z

    /** Writes Turns to an in-memory Corpus that the summary reads back, as `turn.json` text. */
    private inner class Writer : CorpusWriter {
        val corpus = sortedMapOf<String, String>()
        override fun begin(id: String, sessionId: String, startedAtMs: Long, sampleRate: Int) = object : TurnInProgress {
            override fun append(samples: ShortArray, count: Int) {}
            override fun finish(appVersion: String, vad: TurnVad?, transcript: Transcript?, classification: Classification?, test: TurnTest?): Turn {
                val turn = Turn(id, sessionId, startedAtMs, TurnAudio("audio.wav", "x", sampleRate, 500), appVersion, vad, transcript, classification)
                corpus[turn.directoryName] = TurnJson.encode(turn)
                return turn
            }
            override fun abandon() {}
        }
    }

    private inner class Session(var online: Boolean = true, var summarising: Boolean = true) {
        val said = mutableListOf<String>()
        val heard = mutableListOf<(Boolean) -> Unit>()
        val turns = mutableListOf<Turn>()
        val sent = mutableListOf<Message>()
        val writer = Writer()
        val marks = SummaryMarks(null)
        var nowMs = 0L
        var at = 0L
        private var ids = 0
        val requests = HubRequests(send = { m -> online && sent.add(m) }, say = { said += it }, clockMs = { nowMs }, newId = { "r${++ids}" })
        val summaries = BucketSummaries(
            notes = { writer.corpus.entries.reversed().asSequence().map { (name, json) -> TranscriptList.read(name, json) } },
            marks = marks,
            hubSummarises = { online && summarising },
            send = { m -> online && sent.add(m) },
            say = { text, then -> said += text; heard += then },
            clockMs = { nowMs },
            wallClockMs = { sessionStart + at },
            newId = { "s${++ids}" },
        )
        val grammar = PhraseGrammar(HubProvider.registry(BucketConfig.DEFAULT, herdr))
        val focus = Focus({ grammar.buckets }, timeoutMs = 120_000)
        val sink = CorpusSink(
            writer, "5e5510", sessionStart, 1000, "t", grammar, focus,
            hubOnline = { online },
            request = requests::request,
            summaries = summaries,
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

        fun command() = turns.last().classification!!.command!!
    }

    @Test
    fun `summarize bucket and its aliases are commands, whole utterances only`() {
        val g = PhraseGrammar(BucketConfig.DEFAULT)
        for (said in listOf("summarize bucket", "SUMMARIZE", "um, sum it up.", "Summarize bucket?")) {
            assertEquals(Command.SUMMARIZE, g.classify(said, focus = "errands", focused = "errands").command?.command, said)
        }
        assertEquals(SummaryCommand("errands"), g.classify("summarize", focus = "errands", focused = "errands").command!!.summary)
        assertEquals(SummaryCommand("inbox"), g.classify("sum it up").command!!.summary, "in the Inbox, the Inbox")
        assertEquals("summarize the meeting notes", g.classify("summarize the meeting notes", focus = "work").content)
    }

    @Test
    fun `focused on an agent, the phrases do what what does it say does`() {
        val g = PhraseGrammar(HubProvider.registry(BucketConfig.DEFAULT, herdr))
        for (said in listOf("summarize bucket", "summarize", "sum it up")) {
            val c = g.classify(said, focused = "stsloop agent").command!!
            assertEquals(Command.REQUEST, c.command, said)
            assertEquals(BucketRequest("stsloop agent", "herdr:w8:p1", "read", replyWithinMs = 30_000), c.request)
        }
        assertEquals(SummaryCommand("hub"), g.classify("summarize", focused = "hub").command!!.summary, "the hub Bucket has no read")
    }

    @Test
    fun `on an agent Bucket, sum it up sends the read request and says its reply`() {
        val s = Session()
        s.hear("STSLOOP")
        s.hear("SUM IT UP")
        assertEquals(Fixtures.json("v1", "request/read.json"), Json.parse(Protocol.encode((s.sent.single() as Request).copy(id = "a3f1cc"))))
        assertEquals("request", s.command().command.json)
        s.requests.replied(Reply("r1", "stsloop agent is blocked: it asks whether to rebase or merge."))
        assertEquals("stsloop agent is blocked: it asks whether to rebase or merge.", s.said.last())
    }

    @Test
    fun `on a capture Bucket, this Session's Notes go to the hub, and the summary is said`() {
        val s = Session()
        s.hear("ERRANDS")
        s.hear("ORDER ROOFING SCREWS")
        s.hear("SCRATCH THAT")
        s.hear("ORDER WOOD SCREWS")
        s.hear("ERRANDS RETURN THE LIBRARY BOOKS")
        s.hear("WORK PLAN THE WEEK")
        s.hear("SUMMARIZE BUCKET")
        val summarize = s.sent.single() as Summarize
        assertEquals("errands", summarize.bucket)
        assertEquals(listOf("order wood screws", "return the library books"), summarize.notes.map { it.text }, "the scratched Note left out")
        assertNull(summarize.since)
        val c = s.command()
        assertEquals(Command.SUMMARIZE, c.command)
        assertEquals(SummaryCommand("errands", null, summarize.notes.map { it.turn }), c.summary, "the Turn records what was summarised")
        val json = TurnJson.encode(s.turns.last())
        assertTrue("\"summary\": { \"bucket\": \"errands\", \"since\": null, \"notes\": [\"${summarize.notes[0].turn}\", \"${summarize.notes[1].turn}\"], \"more\": false }" in json, json)
        assertEquals("dropped.", s.said[2])
        assertEquals(6, s.said.size, "nothing is said for the summary until the hub answers")
        assertTrue(s.summaries.replied(Reply(summarize.id, "Errands: wood screws and the library books.")))
        assertEquals("Errands: wood screws and the library books.", s.said.last())
        s.heard.single()(true)
        assertEquals(summarize.notes.last().turn, s.marks.since("errands"))

        s.hear("SUMMARIZE")
        assertEquals("nothing new in errands since last time.", s.said.last())
        assertEquals(1, s.sent.size)
        s.hear("BUY STAMPS")
        s.hear("SUMMARIZE")
        val next = s.sent.last() as Summarize
        assertEquals(listOf("buy stamps"), next.notes.map { it.text })
        assertEquals(summarize.notes.last().turn, next.since)
    }

    @Test
    fun `with the hub offline the phone reads the count and the latest three Notes`() {
        val s = Session(online = false)
        s.hear("ERRANDS")
        for (n in listOf("ONE", "TWO", "THREE", "FOUR", "FIVE")) s.hear("NOTE $n")
        s.hear("SUMMARIZE")
        assertEquals("errands: 5 notes in the last day; the latest three: note three. note four. note five.", s.said.last())
        assertTrue(s.sent.isEmpty())
        assertEquals(5, s.command().summary!!.notes.size, "the Turn records the range all the same")
        assertNull(s.marks.since("errands"), "a raw reading is not a summary")
    }

    @Test
    fun `in the Inbox the unlabelled Turns are summarised`() {
        val s = Session(summarising = false)
        s.hear("BUY MILK")
        s.hear("UM")
        s.hear("SUM IT UP")
        assertEquals("the inbox: one note in the last day: buy milk.", s.said.last())
    }

    @Test
    fun `a hub Bucket that is not an agent's has nothing to summarise`() {
        val s = Session()
        s.hear("THE HUB")
        s.hear("SUMMARIZE")
        assertEquals("hub has no notes to summarize.", s.said.last())
        assertTrue(s.sent.isEmpty())
        assertEquals(TurnKind.COMMAND, s.turns.last().kind)
    }

    @Test
    fun `a summary the hub does not answer in time is read raw after saying so`() {
        val s = Session()
        s.hear("WORK PLAN THE WEEK")
        s.hear("WORK")
        s.hear("SUMMARIZE")
        assertEquals(1, s.summaries.pending)
        s.nowMs = BucketSummaries.REPLY_TIMEOUT_MS
        s.summaries.tick()
        assertEquals("${HubRequests.NO_ANSWER} work: one note in the last day: plan the week.", s.said.last())
    }
}
