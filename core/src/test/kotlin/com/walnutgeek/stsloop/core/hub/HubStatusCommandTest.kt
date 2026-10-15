package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CommandInvocation
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.Transcript
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnAudio
import com.walnutgeek.stsloop.core.TurnInProgress
import com.walnutgeek.stsloop.core.TurnJson
import com.walnutgeek.stsloop.core.TurnVad
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.recognisability.ProbeKind
import com.walnutgeek.stsloop.core.recognisability.Probes
import com.walnutgeek.stsloop.core.testmode.TurnTest
import com.walnutgeek.stsloop.core.turn.CloseReason
import com.walnutgeek.stsloop.core.turn.CorpusSink
import com.walnutgeek.stsloop.core.turn.Focus
import com.walnutgeek.stsloop.core.turn.SessionCommands
import com.walnutgeek.stsloop.core.turn.SttTiming
import com.walnutgeek.stsloop.core.turn.Transcription
import com.walnutgeek.stsloop.core.turn.Utterance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "Hub status" and "hub details" (#75) at the `:core` seam: heard whatever has
 * Focus, asked of the hub while it is online and said when its report comes,
 * and answered by the phone alone, at once, while it is not.
 */
class HubStatusCommandTest {
    private val herdr = Protocol.decode(Fixtures.text("v1", "descriptors/herdr.json")) as Descriptors
    private val welcome = Protocol.decode(Fixtures.text("v1", "welcome/basic.json")) as Welcome
    private val unreachable = HubFailure.Unreachable("100.101.102.103:7717")
    private val minute = 60_000L
    private val now = 1_760_000_000_000L

    private class Writer : CorpusWriter {
        override fun begin(id: String, sessionId: String, startedAtMs: Long, sampleRate: Int) = object : TurnInProgress {
            override fun append(samples: ShortArray, count: Int) {}
            override fun finish(appVersion: String, vad: TurnVad?, transcript: Transcript?, classification: Classification?, test: TurnTest?) =
                Turn(id, sessionId, startedAtMs, TurnAudio("audio.wav", "x", sampleRate, 100), appVersion, vad, transcript, classification)
            override fun abandon() {}
        }
    }

    /** One Session's phone side: what the loop says, the Turns it writes, what reaches the hub. */
    private inner class Session(var status: HubStatus, var reach: HubReach, var offline: Boolean = status !is HubStatus.Online) {
        val said = mutableListOf<String>()
        val turns = mutableListOf<Turn>()
        val sent = mutableListOf<Message>()
        val asked = mutableListOf<StatusQueries.Detail>()
        var nowMs = 0L
        private var at = 0L
        val reports = StatusQueries(
            send = { m -> status is HubStatus.Online && sent.add(m) },
            say = { said += it },
            facts = { detail ->
                asked += detail
                HubFacts(status, offline, reach, HubReport.agents(herdr), toUpload = if (detail == StatusQueries.Detail.LONG) 2 else null, nowMs = now)
            },
            clockMs = { nowMs },
            newId = { "b7e2d4" },
        )
        val grammar = PhraseGrammar(HubProvider.registry(BucketConfig.DEFAULT, herdr))
        val focus = Focus({ grammar.buckets }, timeoutMs = 120_000)
        val sink = CorpusSink(
            Writer(), "s", 0, 1000, "t", grammar, focus,
            hubOnline = { !offline },
            hubReport = reports::ask,
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

        fun hubSays(rel: String) = reports.answered(Protocol.decode(Fixtures.text("v1", rel)) as StatusReport)
    }

    private fun online() = Session(HubStatus.Online(welcome), HubReach.EMPTY.connected(now - 40 * minute))

    @Test
    fun `the two phrases are loop commands, heard whatever has Focus`() {
        val g = PhraseGrammar(HubProvider.registry(BucketConfig.DEFAULT, herdr))
        for (focused in listOf(null, "inbox", "errands", "stsloop agent", "the hub")) {
            assertEquals(Classification.command(CommandInvocation(Command.HUB_STATUS, "hub status")), g.classify("Hub status.", focused = focused))
            assertEquals(Classification.command(CommandInvocation(Command.HUB_DETAILS, "hub details")), g.classify("um, hub details", focused = focused))
        }
        // The recogniser hears these every time; "hub" alone it often hears as "hob" (#49).
        for (said in listOf("hub health", "How is the hub?")) assertEquals(Command.HUB_STATUS, g.classify(said)?.command?.command, said)
        for (said in listOf("hub info", "details of the hub")) assertEquals(Command.HUB_DETAILS, g.classify(said)?.command?.command, said)
        assertEquals("hub status of the roof", g.classify("hub status of the roof", focus = "inbox").content, "only as the whole utterance")
    }

    @Test
    fun `the longer phrases come first, and the two-word ones stay aliases`() {
        // #82: "hub status" is heard 3 times in 6 on the dev phone, "hub details" 2; the primaries every time.
        assertEquals(listOf("how is the hub", "hub status", "hub health"), Command.HUB_STATUS.phrases)
        assertEquals(listOf("details of the hub", "hub details", "hub info"), Command.HUB_DETAILS.phrases)
    }

    @Test
    fun `the recognisability check speaks both`() {
        val probes = Probes.of(HubProvider.registry(BucketConfig.DEFAULT, herdr)).associateBy { it.key }
        assertEquals(ProbeKind.COMMAND, probes["hub status"]?.kind)
        assertEquals("hub_details", probes["hub details"]?.owner)
    }

    @Test
    fun `online, hub status asks the hub and says its report when it comes`() {
        val s = online()
        s.hear("HUB STATUS")
        assertEquals(emptyList<String>(), s.said, "nothing is said until the hub answers")
        assertEquals(Fixtures.json("v1", "status_request/basic.json"), Json.parse(Protocol.encode(s.sent.single())))
        val turn = s.turns.single()
        assertEquals(Command.HUB_STATUS, turn.classification!!.command!!.command)
        assertTrue("\"name\": \"hub_status\"" in TurnJson.encode(turn), TurnJson.encode(turn))
        s.hubSays("status_report/herdr.json")
        assertEquals(listOf("hub connected, 3 agents, 1 blocked."), s.said)
        assertEquals(listOf(StatusQueries.Detail.SHORT), s.asked, "the Turns to upload are counted only for hub details")
    }

    @Test
    fun `online, hub details adds what only the phone knows`() {
        val s = online()
        s.hear("HUB DETAILS")
        s.hubSays("status_report/herdr.json")
        assertEquals(
            listOf("hub connected for 40 minutes. 3 agents, 1 blocked. herdr 0.9.1 ok. Claude Code hooks installed. 2 Turns wait to upload."),
            s.said,
        )
    }

    @Test
    fun `a hub that never answers is given up on, and a late report is not said`() {
        val s = online()
        s.hear("HUB STATUS")
        s.nowMs = HubRequests.REPLY_TIMEOUT_MS - 1
        s.reports.tick()
        assertEquals(1, s.reports.pending)
        s.nowMs = HubRequests.REPLY_TIMEOUT_MS
        s.reports.tick()
        assertEquals(listOf(HubRequests.NO_ANSWER), s.said)
        s.hubSays("status_report/herdr.json")
        assertEquals(listOf(HubRequests.NO_ANSWER), s.said)
    }

    @Test
    fun `offline, the phone answers alone at once, from reach and the cached Descriptors`() {
        val reach = HubReach.EMPTY.connected(now - 60 * minute).left(now - 12 * minute).failed(unreachable, now - minute)
        val s = Session(HubStatus.Offline("x", unreachable), reach)
        s.hear("HUB STATUS")
        s.hear("HUB DETAILS")
        assertEquals(
            listOf(
                "hub offline for 12 minutes, last saw 3 agents.",
                "hub offline for 12 minutes. ${unreachable.text} last saw 3 agents. 2 Turns wait to upload.",
            ),
            s.said,
        )
        assertEquals(emptyList<Message>(), s.sent)
        assertEquals(0, s.reports.pending)
    }

    @Test
    fun `a link that drops as it is asked says hub offline`() {
        // The facts still say online, as they may in the moment the link drops, but the send fails.
        val reports = StatusQueries(
            send = { false }, say = {}, clockMs = { 0 },
            facts = { HubFacts(HubStatus.Online(welcome), false, HubReach.EMPTY, 0, null, now) },
        )
        assertEquals(SessionCommands.HUB_OFFLINE, reports.ask(StatusQueries.Detail.SHORT))
        assertEquals(0, reports.pending)
    }

    @Test
    fun `no hub paired`() {
        val s = Session(HubStatus.Unpaired, HubReach.EMPTY)
        s.hear("HUB STATUS")
        assertEquals(listOf(HubReport.NO_HUB), s.said)
    }
}
