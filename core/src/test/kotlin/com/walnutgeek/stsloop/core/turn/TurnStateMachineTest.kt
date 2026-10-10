package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.TurnVad
import com.walnutgeek.stsloop.core.turn.CloseReason.MAX_DURATION
import com.walnutgeek.stsloop.core.turn.CloseReason.SESSION_END
import com.walnutgeek.stsloop.core.turn.CloseReason.SILENCE
import com.walnutgeek.stsloop.core.turn.TurnState.CAPTURING
import com.walnutgeek.stsloop.core.turn.TurnState.IDLE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The clock is the sample position. At 1000 Hz one sample is one millisecond,
 * and the fake VAD hands out one probability per 10 ms window.
 */
class TurnStateMachineTest {
    private val timings = Timings(
        trailingSilenceMs = 100,
        maxUtteranceMs = 1000,
        minUtteranceMs = 30,
        preRollMs = 20,
        speechThreshold = 0.5f,
        releaseThreshold = 0.3f,
    )
    private val sm = TurnStateMachine(timings, sampleRate = 1000)
    private val events = mutableListOf<TurnEvent>()

    private fun feed(ms: Int, p: Float) {
        require(ms % 10 == 0)
        repeat(ms / 10) { sm.window(10, p)?.let(events::add) }
    }

    private fun speech(ms: Int) = feed(ms, 0.9f)
    private fun silence(ms: Int) = feed(ms, 0.0f)
    private fun closed() = events.filterIsInstance<TurnEvent.Closed>().map { it.utterance }
    private fun discarded() = events.filterIsInstance<TurnEvent.Discarded>()

    // --- IDLE ---

    @Test
    fun `starts idle at position zero`() {
        assertEquals(IDLE, sm.state)
        assertEquals(0, sm.position)
    }

    @Test
    fun `silence keeps it idle and emits nothing`() {
        silence(5000)
        assertEquals(IDLE, sm.state)
        assertEquals(emptyList<TurnEvent>(), events)
        assertEquals(5000, sm.position)
    }

    @Test
    fun `a speech window starts capturing`() {
        silence(100)
        speech(10)
        assertEquals(CAPTURING, sm.state)
    }

    @Test
    fun `a window below the speech threshold does not start capturing`() {
        feed(10, 0.49f)
        assertEquals(IDLE, sm.state)
    }

    @Test
    fun `a window exactly at the speech threshold starts capturing`() {
        feed(10, 0.5f)
        assertEquals(CAPTURING, sm.state)
    }

    @Test
    fun `the release threshold alone does not start capturing`() {
        feed(500, 0.4f)
        assertEquals(IDLE, sm.state)
    }

    // --- closing by Silence ---

    @Test
    fun `trailing silence closes the utterance`() {
        silence(100)
        speech(200)
        silence(100)
        assertEquals(IDLE, sm.state)
        assertEquals(1, closed().size)
        assertEquals(SILENCE, closed()[0].closedBy)
    }

    @Test
    fun `one window short of the trailing silence it is still capturing`() {
        speech(200)
        silence(90)
        assertEquals(CAPTURING, sm.state)
        assertEquals(emptyList<TurnEvent>(), events)
    }

    @Test
    fun `the close is reported on the window that completes the silence`() {
        speech(200)
        silence(90)
        val e = sm.window(10, 0f)
        assertEquals(SILENCE, (e as TurnEvent.Closed).utterance.closedBy)
        assertEquals(IDLE, sm.state)
    }

    @Test
    fun `utterance spans pre-roll, speech and exactly the trailing silence`() {
        silence(100)
        speech(200)
        silence(300)
        val u = closed().single()
        assertEquals(80, u.startSample) // 100 - 20 pre-roll
        assertEquals(400, u.endSample) // 300 + 100 trailing
        assertEquals(200, u.speechSamples)
        assertEquals(100, u.trailingSilenceSamples)
    }

    @Test
    fun `speech resuming inside the trailing silence keeps one utterance`() {
        silence(100)
        speech(100)
        silence(90)
        speech(100)
        silence(100)
        val u = closed().single()
        assertEquals(80, u.startSample)
        assertEquals(390 + 100, u.endSample)
        assertEquals(290, u.speechSamples) // the in-utterance pause counts as speech time
    }

    @Test
    fun `a gap of exactly the trailing silence splits two utterances`() {
        silence(100)
        speech(100)
        silence(100)
        speech(100)
        silence(100)
        assertEquals(2, closed().size)
        assertEquals(listOf(80L to 300L, 300L to 500L), closed().map { it.startSample to it.endSample })
    }

    @Test
    fun `once capturing, the release threshold counts as speech`() {
        speech(50)
        feed(200, 0.35f)
        assertEquals(CAPTURING, sm.state)
        silence(100)
        assertEquals(250, closed().single().speechSamples)
    }

    @Test
    fun `once capturing, below the release threshold is silence`() {
        speech(50)
        feed(100, 0.29f)
        assertEquals(IDLE, sm.state)
        assertEquals(50, closed().single().speechSamples)
    }

    // --- minimum duration ---

    @Test
    fun `speech shorter than the minimum is discarded, not closed`() {
        silence(100)
        speech(20)
        silence(100)
        assertEquals(IDLE, sm.state)
        assertEquals(emptyList<Utterance>(), closed())
        val d = discarded().single()
        assertEquals(20, d.speechSamples)
        assertEquals(80, d.startSample)
        assertEquals(220, d.endSample)
    }

    @Test
    fun `speech exactly at the minimum is kept`() {
        speech(30)
        silence(100)
        assertEquals(30, closed().single().speechSamples)
    }

    @Test
    fun `a single-window blip is discarded`() {
        silence(50)
        speech(10)
        silence(200)
        assertEquals(1, discarded().size)
        assertEquals(0, closed().size)
    }

    @Test
    fun `a minimum of zero keeps even a single window`() {
        val keepAll = TurnStateMachine(timings.copy(minUtteranceMs = 0), 1000)
        keepAll.window(10, 0.9f)
        val e = (1..10).firstNotNullOf { keepAll.window(10, 0f) }
        assertEquals(10, (e as TurnEvent.Closed).utterance.speechSamples)
    }

    @Test
    fun `discarded audio does not block the next utterance's pre-roll`() {
        silence(100)
        speech(10) // blip at 100..110, discarded at 210
        silence(100)
        speech(100) // onset at 210
        silence(100)
        assertEquals(190, closed().single().startSample)
    }

    // --- maximum duration ---

    @Test
    fun `max duration closes the Recording at exactly max length`() {
        silence(100)
        speech(2000)
        val u = closed().first()
        assertEquals(MAX_DURATION, u.closedBy)
        assertEquals(80, u.startSample)
        assertEquals(1080, u.endSample)
        assertEquals(1000, u.endSample - u.startSample)
    }

    @Test
    fun `max duration close reports speech up to the cut and no trailing silence`() {
        silence(100)
        speech(2000)
        val u = closed().first()
        assertEquals(1080 - 100, u.speechSamples)
        assertEquals(0, u.trailingSilenceSamples)
    }

    @Test
    fun `runaway speech is cut into contiguous utterances with no lost samples`() {
        silence(100)
        speech(2500)
        silence(100)
        val spans = closed().map { it.startSample to it.endSample }
        assertEquals(listOf(80L to 1080L, 1080L to 2080L, 2080L to 2700L), spans)
        assertEquals(listOf(MAX_DURATION, MAX_DURATION, SILENCE), closed().map { it.closedBy })
    }

    @Test
    fun `max may cut inside a window`() {
        val sm = TurnStateMachine(timings.copy(preRollMs = 15), 1000)
        repeat(10) { sm.window(10, 0f) }
        val e = (1..200).firstNotNullOf { sm.window(10, 0.9f) } as TurnEvent.Closed
        assertEquals(85, e.utterance.startSample)
        assertEquals(1085, e.utterance.endSample)
    }

    @Test
    fun `when silence and max fall due on the same window the earlier boundary wins`() {
        // start 0 (pre-roll clamped), speech to 950, silence close due at 1050, max at 1000
        speech(950)
        silence(100)
        val u = closed().single()
        assertEquals(MAX_DURATION, u.closedBy)
        assertEquals(1000, u.endSample)
        assertEquals(950, u.speechSamples)
        assertEquals(50, u.trailingSilenceSamples)
    }

    @Test
    fun `silence that completes before max closes by silence`() {
        speech(890)
        silence(100)
        val u = closed().single()
        assertEquals(SILENCE, u.closedBy)
        assertEquals(990, u.endSample)
    }

    // --- pre-roll ---

    @Test
    fun `pre-roll is clamped at the start of the stream`() {
        silence(10)
        speech(100)
        silence(100)
        assertEquals(0, closed().single().startSample)
    }

    @Test
    fun `pre-roll never reaches back into the previous Turn`() {
        speech(100)
        silence(100) // closes at 200
        speech(100) // onset 200, pre-roll would be 180
        silence(100)
        assertEquals(listOf(0L to 200L, 200L to 400L), closed().map { it.startSample to it.endSample })
    }

    @Test
    fun `zero pre-roll starts the Recording at the onset window`() {
        val sm = TurnStateMachine(timings.copy(preRollMs = 0), 1000)
        repeat(10) { sm.window(10, 0f) }
        repeat(10) { sm.window(10, 0.9f) }
        val u = (1..10).firstNotNullOf { sm.window(10, 0f) } as TurnEvent.Closed
        assertEquals(100, u.utterance.startSample)
    }

    // --- end of Session ---

    @Test
    fun `ending the Session while capturing closes the utterance at the current position`() {
        silence(100)
        speech(200)
        silence(40)
        val u = (sm.end() as TurnEvent.Closed).utterance
        assertEquals(SESSION_END, u.closedBy)
        assertEquals(80, u.startSample)
        assertEquals(340, u.endSample)
        assertEquals(200, u.speechSamples)
        assertEquals(40, u.trailingSilenceSamples)
        assertEquals(IDLE, sm.state)
    }

    @Test
    fun `ending the Session on too little speech discards`() {
        speech(20)
        assertEquals(20, (sm.end() as TurnEvent.Discarded).speechSamples)
    }

    @Test
    fun `ending the Session while idle emits nothing`() {
        speech(100)
        silence(200)
        events.clear()
        assertNull(sm.end())
    }

    // --- retention ---

    @Test
    fun `while idle only the pre-roll must be retained`() {
        silence(500)
        assertEquals(480, sm.retainFrom)
    }

    @Test
    fun `while idle near the start the whole stream is retained`() {
        silence(10)
        assertEquals(0, sm.retainFrom)
    }

    @Test
    fun `while capturing everything from the utterance start is retained`() {
        silence(500)
        speech(300)
        assertEquals(480, sm.retainFrom)
    }

    @Test
    fun `after a close nothing before its end is retained`() {
        speech(100)
        silence(100)
        assertEquals(200, sm.retainFrom)
        silence(10)
        assertEquals(200, sm.retainFrom)
        silence(100)
        assertEquals(290, sm.retainFrom)
    }

    // --- realistic rates ---

    @Test
    fun `defaults at 16 kHz with 512-sample Silero windows`() {
        val sm = TurnStateMachine(Timings(), 16_000)
        val window = 512
        val out = mutableListOf<TurnEvent>()
        fun run(windows: Int, p: Float) = repeat(windows) { sm.window(window, p)?.let(out::add) }
        run(100, 0f) // 3.2 s of silence
        run(63, 0.9f) // ~2 s of speech
        run(47, 0f) // 1504 ms of silence: enough to close at exactly 1500 ms
        val u = (out.single() as TurnEvent.Closed).utterance
        assertEquals(100L * 512 - 300 * 16, u.startSample)
        assertEquals(63L * 512, u.speechSamples)
        assertEquals(1500L * 16, u.trailingSilenceSamples)
        assertEquals(u.endSample - u.startSample, 300L * 16 + u.speechSamples + u.trailingSilenceSamples)
    }

    @Test
    fun `46 Silero windows of silence are not yet 1500 ms`() {
        val sm = TurnStateMachine(Timings(), 16_000)
        repeat(20) { sm.window(512, 0.9f) }
        repeat(46) { assertNull(sm.window(512, 0f)) }
        assertEquals(CAPTURING, sm.state)
    }

    // --- conversion to turn.json ---

    @Test
    fun `an utterance's wall time is the session start plus its sample offset`() {
        val u = Utterance(48_000, 100_000, 40_000, 24_000, SILENCE)
        assertEquals(1_000_003_000, u.startedAtMs(1_000_000_000, 16_000))
    }

    @Test
    fun `the vad block is speech and trailing silence in ms`() {
        val u = Utterance(0, 100_000, 50_880, 24_000, SILENCE)
        assertEquals(TurnVad(speechMs = 3180, trailingSilenceMs = 1500), u.vad(16_000))
    }

    @Test
    fun `length is end minus start`() {
        assertEquals(52_000, Utterance(48_000, 100_000, 0, 0, SILENCE).lengthSamples)
    }

    // --- guards ---

    @Test
    fun `a window must have samples`() {
        assertThrows<IllegalArgumentException> { sm.window(0, 0f) }
    }

    @Test
    fun `sample rate must be positive`() {
        assertThrows<IllegalArgumentException> { TurnStateMachine(timings, 0) }
    }

    @Test
    fun `a NaN probability is treated as silence`() {
        feed(100, Float.NaN)
        assertEquals(IDLE, sm.state)
    }
}
