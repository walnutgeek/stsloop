package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.BucketSource
import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CommandInvocation
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.Declaration
import com.walnutgeek.stsloop.core.DeclarationPosition
import com.walnutgeek.stsloop.core.Transcript
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnAudio
import com.walnutgeek.stsloop.core.TurnInProgress
import com.walnutgeek.stsloop.core.TurnKind
import com.walnutgeek.stsloop.core.TurnVad
import com.walnutgeek.stsloop.core.grammar.Bucket
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.testmode.TestConfig
import com.walnutgeek.stsloop.core.testmode.TurnTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.IOException

class CorpusSinkTest {
    private class FakeWriter : CorpusWriter {
        var failAppendOn: Int? = null
        var failFinishOn: Int? = null
        var failAbandon = false
        val begun = mutableListOf<Triple<String, Long, Int>>()
        val written = mutableListOf<ShortArray>()
        val abandoned = mutableListOf<String>()

        override fun begin(id: String, sessionId: String, startedAtMs: Long, sampleRate: Int): TurnInProgress {
            begun += Triple(id, startedAtMs, sampleRate)
            val n = begun.size
            return object : TurnInProgress {
                var pcm = ShortArray(0)
                override fun append(samples: ShortArray, count: Int) {
                    if (failAppendOn == n) throw IOException("disk full")
                    pcm += samples.copyOf(count)
                }

                override fun finish(
                    appVersion: String,
                    vad: TurnVad?,
                    transcript: Transcript?,
                    classification: Classification?,
                    test: TurnTest?,
                ): Turn {
                    if (failFinishOn == n) throw IOException("fsync failed")
                    written += pcm
                    return Turn(
                        id, sessionId, startedAtMs, TurnAudio("audio.wav", "x", sampleRate, pcm.size * 1000L / sampleRate),
                        appVersion, vad, transcript, classification, test = test,
                    )
                }

                override fun abandon() {
                    abandoned += id
                    if (failAbandon) throw IOException("cannot delete")
                }
            }
        }
    }

    private val writer = FakeWriter()
    private val published = mutableListOf<Pair<Turn, Utterance>>()
    private val timings = mutableListOf<SttTiming?>()
    private val failures = mutableListOf<Pair<Utterance, Exception>>()
    private val said = mutableListOf<String?>()
    private var ids = 0
    private val sink = CorpusSink(
        writer, sessionId = "5e5510", sessionStartedAtMs = 1_000_000, sampleRate = 1000, appVersion = "t",
        grammar = PhraseGrammar(BucketConfig.DEFAULT),
        newId = { "id${++ids}" },
        listener = object : CorpusSink.Listener {
            override fun published(turn: Turn, utterance: Utterance, timing: SttTiming?, say: String?) {
                published += turn to utterance
                timings += timing
                said += say
            }
            override fun failed(utterance: Utterance, error: Exception) { failures += utterance to error }
        },
    )

    private fun u(start: Long, end: Long) = Utterance(start, end, end - start - 100, 100, CloseReason.SILENCE)
    private fun pcm(n: Int) = ShortArray(n) { it.toShort() }

    @Test
    fun `publishes a Turn per utterance with its offset-derived start and vad block`() {
        sink.closed(u(2500, 3700), pcm(1200))
        val (turn, _) = published.single()
        assertEquals(1_002_500, turn.startedAtMs)
        assertEquals(TurnVad(speechMs = 1100, trailingSilenceMs = 100), turn.vad)
        assertEquals(1200, turn.audio.durationMs)
        assertArrayEquals(pcm(1200), writer.written.single())
        assertEquals(1, sink.published)
    }

    @Test
    fun `the transcript goes into the same publish, and an undeclared transcript is unclassified`() {
        val transcript = Transcript("HELLO THERE", "sherpa-onnx", "m", 1_003_900, latencyMs = 200)
        val timing = SttTiming(computeMs = 120, queuedMs = 10, finalizeMs = 40)
        sink.closed(u(2500, 3700), pcm(1200), Transcription(transcript, timing))
        val (turn, _) = published.single()
        assertEquals(transcript, turn.transcript)
        assertEquals(Classification.UNCLASSIFIED, turn.classification)
        assertEquals(timing, timings.single())
    }

    private fun transcribed(text: String) =
        Transcription(Transcript(text, "sherpa-onnx", "m", 1_003_900, latencyMs = 200), SttTiming(1, 1, 1))

    @Test
    fun `a declared transcript is a Note in its Bucket, with the raw transcript untouched`() {
        sink.closed(u(0, 500), pcm(500), transcribed("ERRANDS ORDER ROOFING SCREWS"))
        val turn = published.single().first
        assertEquals("ERRANDS ORDER ROOFING SCREWS", turn.transcript!!.text)
        assertEquals(TurnKind.NOTE, turn.kind)
        val c = turn.classification!!
        assertEquals(Declaration("errands", DeclarationPosition.LEADING, "errands"), c.declaration)
        assertEquals("errands", c.bucket)
        assertEquals(BucketSource.DECLARATION, c.bucketSource)
        assertEquals("order roofing screws", c.content)
    }

    @Test
    fun `I need to work on the roof is published unclassified`() {
        sink.closed(u(0, 500), pcm(500), transcribed("I NEED TO WORK ON THE ROOF"))
        assertEquals(Classification.UNCLASSIFIED, published.single().first.classification)
    }

    @Test
    fun `an empty transcript is unclassified`() {
        sink.closed(u(0, 500), pcm(500), transcribed(""))
        assertEquals(Classification.UNCLASSIFIED, published.single().first.classification)
    }

    @Test
    fun `the sink classifies with the Buckets it was given`() {
        val s = CorpusSink(
            writer, "s", 0, 1000, "t", PhraseGrammar(BucketConfig(listOf(Bucket("garden", listOf("yard"))))),
            listener = object : CorpusSink.Listener {
                override fun published(turn: Turn, utterance: Utterance, timing: SttTiming?, say: String?) { published += turn to utterance }
            },
        )
        s.closed(u(0, 500), pcm(500), transcribed("YARD WATER THE BEDS"))
        assertEquals("garden", published.single().first.classification!!.bucket)
    }

    @Test
    fun `a Turn the recognizer could not transcribe is still published, unclassified`() {
        sink.closed(u(0, 500), pcm(500))
        val (turn, _) = published.single()
        assertEquals(null, turn.transcript)
        assertEquals(TurnKind.UNCLASSIFIED, turn.kind)
        assertEquals(null, timings.single())
    }

    @Test
    fun `a failed append abandons that Turn, reports it, and the next Turn still publishes`() {
        writer.failAppendOn = 1
        sink.closed(u(0, 500), pcm(500))
        sink.closed(u(1000, 1500), pcm(500))
        assertEquals(listOf("id1"), writer.abandoned)
        assertEquals("disk full", failures.single().second.message)
        assertEquals(0L, failures.single().first.startSample)
        assertEquals(listOf("id2"), published.map { it.first.id })
        assertEquals(1, sink.published)
        assertEquals(1, sink.failed)
    }

    @Test
    fun `a failed finish abandons that Turn too`() {
        writer.failFinishOn = 1
        sink.closed(u(0, 500), pcm(500))
        assertEquals(listOf("id1"), writer.abandoned)
        assertEquals(1, sink.failed)
    }

    @Test
    fun `a failing abandon does not escape`() {
        writer.failAppendOn = 1
        writer.failAbandon = true
        sink.closed(u(0, 500), pcm(500))
        assertEquals(1, sink.failed)
        assertEquals("disk full", failures.single().second.message)
    }

    @Test
    fun `a failing begin is reported, not thrown`() {
        val broken = CorpusSink(
            object : CorpusWriter {
                override fun begin(id: String, sessionId: String, startedAtMs: Long, sampleRate: Int): TurnInProgress =
                    throw IOException("no staging dir")
            },
            "s", 0, 1000, "t", PhraseGrammar(BucketConfig.DEFAULT), listener = object : CorpusSink.Listener {},
        )
        broken.closed(u(0, 500), pcm(500))
        assertEquals(1, broken.failed)
    }

    @Test
    fun `discards are passed to the listener`() {
        val seen = mutableListOf<TurnEvent.Discarded>()
        val s = CorpusSink(writer, "s", 0, 1000, "t", PhraseGrammar(BucketConfig.DEFAULT), listener = object : CorpusSink.Listener {
            override fun discarded(event: TurnEvent.Discarded) { seen += event }
        })
        s.discarded(TurnEvent.Discarded(0, 200, 20))
        assertEquals(20L, seen.single().speechSamples)
    }

    @Test
    fun `outside test mode a Turn has no test block`() {
        sink.closed(u(0, 500), pcm(500))
        assertEquals(null, published.single().first.test)
    }

    @Test
    fun `in test mode each Turn gets the test block computed for its own utterance`() {
        val asked = mutableListOf<Utterance>()
        val s = CorpusSink(
            writer, "s", 0, 1000, "t", PhraseGrammar(BucketConfig.DEFAULT),
            testOf = { utt -> asked += utt; TurnTest(TestConfig(enabled = true), listOf("builtin_mic"), utt.startSample, listOf("x")) },
            listener = object : CorpusSink.Listener {
                override fun published(turn: Turn, utterance: Utterance, timing: SttTiming?, say: String?) { published += turn to utterance }
            },
        )
        s.closed(u(0, 500), pcm(500))
        s.closed(u(700, 1500), pcm(800))
        assertEquals(listOf(0L, 700L), asked.map { it.startSample })
        assertEquals(listOf(0L, 700L), published.map { it.first.test!!.ttsOverlapMs })
    }

    @Test
    fun `a failing test block never loses the Turn's audio`() {
        val s = CorpusSink(
            writer, "s", 0, 1000, "t", PhraseGrammar(BucketConfig.DEFAULT),
            testOf = { error("route query failed") },
            listener = object : CorpusSink.Listener {
                override fun published(turn: Turn, utterance: Utterance, timing: SttTiming?, say: String?) { published += turn to utterance }
            },
        )
        s.closed(u(0, 500), pcm(500))
        assertEquals(null, published.single().first.test)
        assertEquals(1, s.published)
    }

    // --- "scratch that" (#13) ---

    private var at = 0L

    /** Closes the next utterance with [text] as its transcript (null: none) and returns the Turn published. */
    private fun say(text: String?): Turn {
        val start = at
        at += 1000
        sink.closed(u(start, start + 500), pcm(500), text?.let(::transcribed))
        return published.last().first
    }

    private val Turn.tombstones: String? get() = classification?.command?.tombstones

    @Test
    fun `scratch that tombstones the previous Turn by directory name and says dropped`() {
        val note = say("ERRANDS BUY MILK")
        val scratch = say("SCRATCH THAT")
        assertEquals(TurnKind.COMMAND, scratch.kind)
        assertEquals(CommandInvocation(Command.SCRATCH_THAT, "scratch that", note.directoryName), scratch.classification!!.command)
        assertEquals(listOf("ERRANDS BUY MILK", "dropped."), said)
    }

    @Test
    fun `an undeclared Turn is tombstoned just the same`() {
        val plain = say("I NEED TO WORK ON THE ROOF")
        assertEquals(plain.directoryName, say("DISCARD THAT").tombstones)
    }

    @Test
    fun `with no previous Turn in the Session there is nothing to drop`() {
        val scratch = say("SCRATCH THAT")
        assertEquals(TurnKind.COMMAND, scratch.kind)
        assertNull(scratch.tombstones)
        assertEquals(listOf("nothing to drop."), said)
    }

    @Test
    fun `a second scratch that does not reach further back`() {
        say("ERRANDS BUY MILK")
        val first = say("ERRANDS BUY BREAD")
        assertEquals(first.directoryName, say("SCRATCH THAT").tombstones)
        assertNull(say("SCRATCH THAT").tombstones)
        assertEquals("nothing to drop.", said.last())
    }

    @Test
    fun `after a scratch the next Turn can be scratched`() {
        say("ERRANDS BUY MILK")
        say("SCRATCH THAT")
        val again = say("ERRANDS BUY OAT MILK")
        assertEquals(again.directoryName, say("SCRATCH THAT").tombstones)
    }

    @Test
    fun `a Turn with nothing to echo is skipped, so scratch that drops what was last read back`() {
        val note = say("ERRANDS BUY MILK")
        say("") // road noise the recognizer heard nothing in
        say(null) // no transcript at all
        say("  ")
        assertEquals(note.directoryName, say("SCRATCH THAT").tombstones)
    }

    @Test
    fun `a Note containing the phrase is stored, echoed, and can itself be scratched`() {
        val note = say("SCRATCH THAT IDEA ABOUT THE ROOF")
        assertEquals(TurnKind.UNCLASSIFIED, note.kind)
        assertEquals("SCRATCH THAT IDEA ABOUT THE ROOF", said.single())
        assertEquals(note.directoryName, say("SCRATCH THAT").tombstones)
    }

    @Test
    fun `a scratch that which fails to write tombstones nothing and says nothing`() {
        val note = say("ERRANDS BUY MILK")
        writer.failFinishOn = 2
        sink.closed(u(5000, 5500), pcm(500), transcribed("SCRATCH THAT"))
        assertEquals(1, sink.failed)
        assertEquals(listOf("ERRANDS BUY MILK"), said)
        // The retry still finds the Turn: nothing was tombstoned.
        assertEquals(note.directoryName, say("SCRATCH THAT").tombstones)
    }

    @Test
    fun `a Turn that failed to write is never a target`() {
        val note = say("ERRANDS BUY MILK")
        writer.failFinishOn = 2
        sink.closed(u(5000, 5500), pcm(500), transcribed("ERRANDS BUY BREAD"))
        assertEquals(note.directoryName, say("SCRATCH THAT").tombstones)
    }

    @Test
    fun `each Session starts with nothing to drop`() {
        say("ERRANDS BUY MILK")
        val next = CorpusSink(
            writer, "5e5511", 2_000_000, 1000, "t", PhraseGrammar(BucketConfig.DEFAULT), newId = { "id${++ids}" },
            listener = object : CorpusSink.Listener {
                override fun published(turn: Turn, utterance: Utterance, timing: SttTiming?, say: String?) {
                    published += turn to utterance
                }
            },
        )
        next.closed(u(0, 500), pcm(500), transcribed("SCRATCH THAT"))
        assertNull(published.last().first.tombstones)
    }
}
