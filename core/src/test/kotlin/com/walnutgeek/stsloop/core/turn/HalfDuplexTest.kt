package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.turn.LoopState.CAPTURING
import com.walnutgeek.stsloop.core.turn.LoopState.GUARD
import com.walnutgeek.stsloop.core.turn.LoopState.LISTENING
import com.walnutgeek.stsloop.core.turn.LoopState.SPEAKING
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The loop's Half-duplex floor: echoes are spoken only into Silence, and the
 * mic is closed from the moment SPEAKING begins until the guard interval after
 * it ends. At 1000 Hz a sample is a millisecond; chunks are 10 ms, and a
 * chunk's last sample is captured at (its end sample) ms, so the sample clock
 * and the nanosecond clock agree. The fake VAD calls any loud window speech,
 * and the TTS audio fed while speaking is as loud as a voice.
 */
class HalfDuplexTest {
    private companion object {
        const val MS = 1_000_000L
        const val LOUD: Short = 20_000
        const val QUIET: Short = 50
    }

    private val timings = Timings(
        trailingSilenceMs = 100, guardMs = 300, maxUtteranceMs = 1000, minUtteranceMs = 30, preRollMs = 20,
    )

    private class Sink : UtteranceSink {
        val opened = mutableListOf<Long>()
        val closed = mutableListOf<Pair<Utterance, ShortArray>>()
        override fun opened(startSample: Long) { opened += startSample }
        override fun closed(utterance: Utterance, pcm: ShortArray, endedAtNs: Long) { closed += utterance to pcm }
    }

    private class Spoken : Speaker {
        val said = mutableListOf<Pair<Long, String>>()
        val abandoned = mutableListOf<Long>()
        override fun speak(id: Long, text: String) { said += id to text }
        override fun abandon(id: Long) { abandoned += id }
    }

    private class Events : HalfDuplex.Listener {
        val log = mutableListOf<String>()
        override fun speaking(id: Long, text: String, atSample: Long) { log += "speaking $id at $atSample" }
        override fun reopened(atSample: Long, closedSamples: Long) { log += "reopened at $atSample after $closedSamples" }
        override fun lost(id: Long) { log += "lost $id" }
        override fun unspoken(count: Int) { log += "unspoken $count" }
    }

    private val vadSeen = mutableListOf<Float>()
    private val vad = SpeechProbability { w -> vadSeen += w.toList(); if (w.any { kotlin.math.abs(it) >= 0.25f }) 1f else 0f }
    private val sink = Sink()
    private val speaker = Spoken()
    private val events = Events()
    private val segmenter = Segmenter(timings, 1000, 10, vad, sink)
    private val loop = HalfDuplex(segmenter, timings, 1000, speaker, events)

    /** Everything fed so far, as the mic produced it. */
    private val stream = mutableListOf<Short>()
    private val now: Long get() = stream.size.toLong()

    private fun feed(ms: Int, value: Short) {
        require(ms % 10 == 0)
        repeat(ms / 10) {
            val chunk = ShortArray(10) { i -> if (i % 2 == 0) value else (-value).toShort() }
            stream += chunk.toList()
            loop.accept(chunk, chunk.size, capturedAtNs = now * MS)
        }
    }

    private fun speech(ms: Int) = feed(ms, LOUD)
    private fun quiet(ms: Int) = feed(ms, QUIET)

    /** The machine's own voice reaching the mic: as loud as a human. */
    private fun tts(ms: Int) = feed(ms, LOUD)

    private fun done(id: Long = speaker.said.last().first, atMs: Long = now) = loop.spoken(id, atMs * MS)

    // --- SPEAKING → guard → LISTENING ---

    @Test
    fun `starts listening`() {
        assertEquals(LISTENING, loop.state)
    }

    @Test
    fun `a transcript arriving in Silence is spoken at the next chunk`() {
        quiet(100)
        loop.echo("HELLO THERE")
        assertEquals(LISTENING, loop.state)
        quiet(10)
        assertEquals(SPEAKING, loop.state)
        assertEquals(listOf(1L to "hello there"), speaker.said)
        assertEquals(listOf("speaking 1 at 110"), events.log)
    }

    @Test
    fun `nothing the mic hears while speaking reaches the vad or becomes a Turn`() {
        quiet(100)
        loop.echo("HELLO")
        quiet(10)
        val judged = vadSeen.size
        tts(2000) // longer than max_utterance_ms, and loud throughout
        assertEquals(judged, vadSeen.size)
        assertEquals(emptyList<Long>(), sink.opened)
        assertEquals(SPEAKING, loop.state)
    }

    @Test
    fun `when speaking ends the mic stays closed for the guard interval`() {
        quiet(100)
        loop.echo("HELLO")
        quiet(10)
        tts(500)
        done()
        tts(200) // speaker bleed and output latency after the engine says it is done
        assertEquals(GUARD, loop.state)
        tts(100) // up to the guard's last instant, inclusive
        assertEquals(GUARD, loop.state)
        quiet(10)
        assertEquals(emptyList<Long>(), sink.opened)
        assertEquals(LISTENING, loop.state)
    }

    @Test
    fun `after the guard the loop listens again and a human Turn is captured`() {
        quiet(100)
        loop.echo("HELLO")
        quiet(10)
        tts(500)
        done()
        tts(300)
        quiet(50)
        speech(200)
        quiet(100)
        val (u, pcm) = sink.closed.single()
        assertEquals(940L, u.startSample) // speech at 960, with its 20 ms of pre-roll, all heard after the reopening
        assertArrayEquals(stream.subList(940, u.endSample.toInt()).toShortArray(), pcm)
        assertEquals("reopened at 910 after 800", events.log.last())
    }

    @Test
    fun `pre-roll never reaches back into the guard interval`() {
        quiet(100)
        loop.echo("HELLO")
        quiet(10)
        tts(100)
        done()
        tts(300)
        speech(200) // starts on the very first sample after the guard
        quiet(100)
        assertEquals(510L, sink.closed.single().first.startSample)
    }

    @Test
    fun `the guard ends at the exact sample, even inside a chunk`() {
        quiet(100)
        loop.echo("HELLO")
        quiet(10)
        tts(100)
        done(atMs = 205) // mid-chunk: the guard runs to 505 ms, inside the chunk 500..510
        tts(300)
        assertEquals(LISTENING, loop.state)
        // Sample n is captured at n + 1 ms, so 505 is the first captured after the guard.
        assertEquals("reopened at 505 after 395", events.log.last())
        // The 5 open samples (505..510) are less than a window: the VAD has judged nothing since the speak.
        val judgedBeforeSpeaking = 110
        assertEquals(judgedBeforeSpeaking, vadSeen.size)
        quiet(10)
        assertEquals(judgedBeforeSpeaking + 10, vadSeen.size)
        assertEquals(stream.subList(505, 515).map { it / 32768f }, vadSeen.takeLast(10))
    }

    @Test
    fun `a guard of zero reopens as soon as speaking ends`() {
        val t = timings.copy(guardMs = 0)
        val seg = Segmenter(t, 1000, 10, vad, sink)
        val l = HalfDuplex(seg, t, 1000, speaker, events)
        l.accept(ShortArray(100), 100, 100 * MS)
        l.echo("HI")
        l.accept(ShortArray(10), 10, 110 * MS)
        l.spoken(1, 300 * MS)
        l.accept(ShortArray(200), 200, 310 * MS)
        assertEquals(LISTENING, l.state)
        assertEquals("reopened at 300 after 190", events.log.last())
    }

    @Test
    fun `the speaker hears about each machine Turn once`() {
        quiet(100)
        loop.echo("ONE")
        repeat(5) { quiet(10) }
        assertEquals(1, speaker.said.size)
    }

    // --- the floor: a transcript arriving while a human Turn is being captured ---

    @Test
    fun `an announcement is said as written, into Silence, with the mic closed`() {
        quiet(100)
        loop.announce("The microphone is silenced by another app.")
        quiet(10)
        assertEquals(SPEAKING, loop.state)
        assertEquals(listOf(1L to "The microphone is silenced by another app."), speaker.said)
        val judged = vadSeen.size
        tts(500)
        assertEquals(judged, vadSeen.size)
    }

    @Test
    fun `an announcement waits for the human Turn being captured, like an echo`() {
        speech(100)
        loop.announce("That recording was silent.")
        speech(100)
        assertEquals(emptyList<Pair<Long, String>>(), speaker.said)
        quiet(100)
        assertEquals(listOf(1L to "That recording was silent."), speaker.said)
    }

    @Test
    fun `nothing is announced without a speaker`() {
        val off = HalfDuplex(Segmenter(timings, 1000, 10, vad, Sink()), timings, 1000, speaker = null)
        off.announce("anything")
        off.accept(ShortArray(10), 10, 10 * MS)
        assertEquals(emptyList<Pair<Long, String>>(), speaker.said)
    }

    @Test
    fun `a transcript arriving while a new Turn is captured waits for its Silence`() {
        speech(200)
        quiet(100) // Turn 1 closes at 300
        speech(100) // Turn 2 is being captured when Turn 1's transcript arrives
        loop.echo("TURN ONE")
        speech(200)
        assertEquals(CAPTURING, loop.state)
        assertEquals(emptyList<Pair<Long, String>>(), speaker.said)
        quiet(100) // Turn 2 closes by Silence: now the machine may take its Turn
        assertEquals(2, sink.closed.size)
        assertEquals(SPEAKING, loop.state)
        assertEquals(listOf(1L to "turn one"), speaker.said)
        // Turn 2 was not cut short by the echo.
        assertEquals(CloseReason.SILENCE, sink.closed[1].first.closedBy)
    }

    @Test
    fun `transcripts queued behind a human Turn are spoken in order in one closed span`() {
        speech(200)
        quiet(100)
        speech(100)
        loop.echo("ONE")
        loop.echo("TWO")
        speech(100)
        quiet(100)
        assertEquals(listOf(1L to "one"), speaker.said)
        tts(300)
        done(1)
        tts(10)
        assertEquals(SPEAKING, loop.state) // straight on to the next echo: no reopening in between
        assertEquals(listOf(1L to "one", 2L to "two"), speaker.said)
        tts(300)
        done(2)
        tts(300)
        quiet(10)
        assertEquals(LISTENING, loop.state)
        assertEquals(1, events.log.count { it.startsWith("reopened") })
        assertEquals(2, sink.closed.size)
    }

    @Test
    fun `a max-duration Turn's transcript waits for its continuation to end`() {
        speech(1500) // cut at 1000; the continuation keeps capturing
        assertEquals(1, sink.closed.size)
        assertEquals(CloseReason.MAX_DURATION, sink.closed[0].first.closedBy)
        loop.echo("FIRST PART")
        speech(100)
        assertEquals(CAPTURING, loop.state)
        assertEquals(emptyList<Pair<Long, String>>(), speaker.said)
        quiet(100)
        assertEquals(2, sink.closed.size)
        assertEquals(1000L, sink.closed[1].first.startSample)
        loop.echo("SECOND PART")
        assertEquals(listOf(1L to "first part"), speaker.said)
        tts(200)
        done()
        tts(10)
        assertEquals(listOf(1L to "first part", 2L to "second part"), speaker.said)
    }

    @Test
    fun `speech that starts in the chunk where an echo was due wins the floor`() {
        quiet(100)
        loop.echo("HELLO")
        speech(10)
        assertEquals(CAPTURING, loop.state)
        assertEquals(emptyList<Pair<Long, String>>(), speaker.said)
    }

    // --- nothing to say ---

    @Test
    fun `an empty transcript is not echoed`() {
        quiet(100)
        loop.echo("")
        loop.echo("   ")
        loop.echo(null)
        quiet(100)
        assertEquals(emptyList<Pair<Long, String>>(), speaker.said)
        assertEquals(LISTENING, loop.state)
    }

    @Test
    fun `with no speaker the mic never closes`() {
        val l = HalfDuplex(segmenter, timings, 1000, speaker = null, listener = events)
        l.accept(ShortArray(100), 100, 100 * MS)
        l.echo("HELLO")
        l.accept(ShortArray(100), 100, 200 * MS)
        assertEquals(LISTENING, l.state)
        assertEquals(emptyList<String>(), events.log)
    }

    // --- the engine misbehaving ---

    @Test
    fun `an end for another machine Turn is ignored`() {
        quiet(100)
        loop.echo("HELLO")
        quiet(10)
        loop.spoken(99, now * MS)
        tts(500)
        assertEquals(SPEAKING, loop.state)
    }

    @Test
    fun `an end reported before the next chunk still opens the guard from its own time`() {
        quiet(100)
        loop.echo("HELLO")
        quiet(10)
        done(atMs = 112)
        tts(200) // 112 + 300 = 412: still guarding at 310
        assertEquals(GUARD, loop.state)
        tts(110)
        assertEquals(LISTENING, loop.state)
    }

    @Test
    fun `a machine Turn that never reports an end reopens the mic after its limit`() {
        quiet(100)
        loop.echo("HI")
        quiet(10)
        val limit = HalfDuplex.speakingLimitMs("hi")
        tts(limit.toInt() - 10)
        assertEquals(SPEAKING, loop.state)
        tts(20)
        assertEquals(GUARD, loop.state)
        assertTrue("lost 1" in events.log)
        assertEquals(listOf(1L), speaker.abandoned) // it must never play into the reopened mic
        tts(300)
        quiet(10)
        assertEquals(LISTENING, loop.state)
    }

    @Test
    fun `the limit grows with the text`() {
        assertTrue(HalfDuplex.speakingLimitMs("a".repeat(1000)) > HalfDuplex.speakingLimitMs("a"))
        assertTrue(HalfDuplex.speakingLimitMs("a") >= 10_000)
    }

    // --- the end of the Session ---

    @Test
    fun `finishing while speaking reports what was never spoken`() {
        speech(200)
        quiet(100)
        speech(100)
        loop.echo("ONE")
        loop.echo("TWO")
        speech(100)
        quiet(100)
        loop.finish()
        assertEquals("unspoken 1", events.log.last())
    }

    @Test
    fun `a transcript that arrives after the end is reported, not queued`() {
        quiet(100)
        loop.finish()
        loop.echo("LATE")
        assertEquals("unspoken 1", events.log.last())
        assertEquals(emptyList<Pair<Long, String>>(), speaker.said)
    }

    @Test
    fun `finishing closes a Turn still being captured`() {
        speech(200)
        loop.finish()
        assertEquals(CloseReason.SESSION_END, sink.closed.single().first.closedBy)
    }
}
