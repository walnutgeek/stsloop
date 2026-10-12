package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.hub.Item
import com.walnutgeek.stsloop.core.turn.LoopState.CAPTURING
import com.walnutgeek.stsloop.core.turn.LoopState.GUARD
import com.walnutgeek.stsloop.core.turn.LoopState.LISTENING
import com.walnutgeek.stsloop.core.turn.LoopState.SPEAKING
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Inbound Items drained into Silence (`speak_item`, #46): the same floor as
 * [HalfDuplexTest], with the same clock (1000 Hz, a sample is a millisecond,
 * 10 ms chunks). Silence closes a Turn after 100 ms here, and an Item waits
 * for that much quiet on the open mic.
 */
class HalfDuplexItemsTest {
    private companion object {
        const val MS = 1_000_000L
        const val LOUD: Short = 20_000
        const val QUIET: Short = 50
    }

    private val timings = Timings(
        trailingSilenceMs = 100, guardMs = 300, maxUtteranceMs = 1000, minUtteranceMs = 30, preRollMs = 20,
    )

    private class Sink : UtteranceSink {
        val closed = mutableListOf<Utterance>()
        override fun opened(startSample: Long) {}
        override fun closed(utterance: Utterance, pcm: ShortArray, endedAtNs: Long) { closed += utterance }
    }

    /** What the speaker was asked to play: "alert" for an earcon and the text, "speak" for the text alone. */
    private class Spoken : Speaker {
        val said = mutableListOf<Triple<Long, String, String>>()
        override fun speak(id: Long, text: String) { said += Triple(id, "speak", text) }
        override fun alert(id: Long, text: String) { said += Triple(id, "alert", text) }
    }

    private class Events : HalfDuplex.Listener {
        val log = mutableListOf<String>()
        override fun speaking(id: Long, text: String, atSample: Long) { log += "speaking $id at $atSample" }
        override fun lost(id: Long) { log += "lost $id" }
        override fun itemsSpoken(items: List<Item>) { log += "spoken ${items.map { it.id }}" }
    }

    private val vad = SpeechProbability { w -> if (w.any { kotlin.math.abs(it) >= 0.25f }) 1f else 0f }
    private val sink = Sink()
    private val speaker = Spoken()
    private val events = Events()
    private val attention = AttentionQueue()
    private val segmenter = Segmenter(timings, 1000, 10, vad, sink)
    private val loop = HalfDuplex(segmenter, timings, 1000, speaker, events, attention)

    private var now = 0L

    private fun feed(ms: Int, value: Short) {
        require(ms % 10 == 0)
        repeat(ms / 10) {
            val chunk = ShortArray(10) { i -> if (i % 2 == 0) value else (-value).toShort() }
            now += chunk.size
            loop.accept(chunk, chunk.size, capturedAtNs = now * MS)
        }
    }

    private fun speech(ms: Int) = feed(ms, LOUD)
    private fun quiet(ms: Int) = feed(ms, QUIET)
    private fun done() = loop.spoken(speaker.said.last().first, now * MS)

    private val stsloop = Item("a1", listOf("herdr:w8:p1"), "stsloop agent is blocked.")
    private val woodglue = Item("a2", listOf("herdr:w4:p1"), "woodglue agent is blocked.")

    @Test
    fun `an item is said after an earcon once the mic has been quiet for the Silence`() {
        quiet(50)
        attention.offer(stsloop)
        quiet(40)
        assertEquals(LISTENING, loop.state, "only 90 ms of quiet so far")
        quiet(10)
        assertEquals(SPEAKING, loop.state)
        assertEquals(listOf(Triple(1L, "alert", "stsloop agent is blocked.")), speaker.said)
    }

    @Test
    fun `an item never starts while the person is speaking, nor right after`() {
        quiet(200)
        speech(100)
        attention.offer(stsloop)
        speech(300)
        assertEquals(CAPTURING, loop.state)
        quiet(110) // the Turn closes on its Silence
        assertEquals(1, sink.closed.size)
        assertEquals(emptyList<Triple<Long, String, String>>(), speaker.said, "a reply could go now; an Item waits")
        quiet(100) // and one more Silence
        assertEquals(listOf(Triple(1L, "alert", "stsloop agent is blocked.")), speaker.said)
        val at = events.log.single().removePrefix("speaking 1 at ").toLong()
        assertTrue(at >= 800, "not before twice the Silence after the last speech (at 600), at $at")
    }

    @Test
    fun `the echo of the Turn just closed goes before a waiting item`() {
        speech(100)
        attention.offer(stsloop)
        quiet(110) // the Turn closes
        quiet(50) // transcribing it
        loop.echo("HELLO")
        quiet(10)
        assertEquals(listOf(Triple(1L, "speak", "hello")), speaker.said)
        quiet(300)
        done()
        quiet(10)
        assertEquals(Triple(2L, "alert", "stsloop agent is blocked."), speaker.said.last())
    }

    @Test
    fun `a pause shorter than the Silence lets nothing in`() {
        quiet(200)
        speech(100)
        attention.offer(stsloop)
        quiet(60)
        speech(100)
        quiet(60)
        assertEquals(emptyList<Triple<Long, String, String>>(), speaker.said)
        assertEquals(CAPTURING, loop.state)
    }

    @Test
    fun `items waiting together are one machine Turn with one earcon`() {
        speech(100)
        attention.offer(stsloop)
        attention.offer(woodglue)
        quiet(220)
        assertEquals(
            listOf(Triple(1L, "alert", "stsloop agent is blocked. woodglue agent is blocked.")),
            speaker.said,
        )
    }

    @Test
    fun `replies go first and the item follows them in the same closed span`() {
        quiet(200)
        speech(100)
        quiet(100) // the Turn closes
        attention.offer(stsloop)
        loop.echo("HELLO")
        quiet(10)
        assertEquals(listOf(Triple(1L, "speak", "hello")), speaker.said)
        quiet(200)
        done()
        quiet(10)
        assertEquals(SPEAKING, loop.state, "no guard in between")
        assertEquals(Triple(2L, "alert", "stsloop agent is blocked."), speaker.said.last())
    }

    @Test
    fun `an item arriving during the guard waits for the Silence after the mic reopens`() {
        quiet(200)
        loop.echo("HELLO")
        quiet(10)
        quiet(200)
        done()
        quiet(20)
        assertEquals(GUARD, loop.state)
        attention.offer(stsloop)
        quiet(280) // the rest of the guard
        assertEquals(GUARD, loop.state)
        quiet(90)
        assertEquals(LISTENING, loop.state)
        assertEquals(1, speaker.said.size)
        quiet(20)
        assertEquals(Triple(2L, "alert", "stsloop agent is blocked."), speaker.said.last())
    }

    @Test
    fun `an item said is reported once its end is, and never said again`() {
        quiet(100)
        attention.offer(stsloop)
        quiet(10)
        assertEquals(listOf<String>(), events.log.filter { it.startsWith("spoken") })
        quiet(500)
        done()
        quiet(10)
        assertEquals(listOf("spoken [a1]"), events.log.filter { it.startsWith("spoken") })
        assertEquals(AttentionQueue.Offered.SPOKEN, attention.offer(stsloop))
        quiet(1000)
        assertEquals(1, speaker.said.size)
    }

    @Test
    fun `an item whose machine Turn is lost is not reported said, and is heard when the hub sends it again`() {
        quiet(100)
        attention.offer(stsloop)
        quiet(10)
        quiet((HalfDuplex.speakingLimitMs(stsloop.say) + 20).toInt() / 10 * 10)
        assertEquals(listOf("speaking 1 at 110", "lost 1"), events.log)
        assertEquals(AttentionQueue.Offered.QUEUED, attention.offer(stsloop))
    }

    @Test
    fun `items left waiting when the Session ends are not reported said`() {
        speech(100)
        attention.offer(stsloop)
        loop.finish()
        assertEquals(emptyList<String>(), events.log)
    }

    @Test
    fun `with no speaker items wait and nothing is said`() {
        val quietLoop = HalfDuplex(Segmenter(timings, 1000, 10, vad, Sink()), timings, 1000, null, events, attention)
        attention.offer(stsloop)
        repeat(50) { i -> quietLoop.accept(ShortArray(10), 10, (i + 1) * 10 * MS) }
        assertEquals(1, attention.size)
    }
}
