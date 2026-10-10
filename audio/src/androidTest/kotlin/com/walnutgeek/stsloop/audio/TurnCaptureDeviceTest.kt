package com.walnutgeek.stsloop.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.WaveReader
import com.walnutgeek.stsloop.audio.speech.SpeechModels
import com.walnutgeek.stsloop.audio.speech.SttRecognizer
import com.walnutgeek.stsloop.core.AUDIO_FILE
import com.walnutgeek.stsloop.core.TURN_FILE
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnKind
import com.walnutgeek.stsloop.core.Wav
import com.walnutgeek.stsloop.core.speech.StreamingRecognizer
import com.walnutgeek.stsloop.core.turn.CloseReason
import com.walnutgeek.stsloop.core.turn.SttTiming
import com.walnutgeek.stsloop.core.turn.Timings
import com.walnutgeek.stsloop.core.turn.Utterance
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * Feeds a synthetic Session through the same [TurnCapture] the service uses,
 * with the real Silero VAD, the real recognizer and the real
 * [FileCorpusWriter]: upstream test wavs spliced between stretches of
 * low-level noise, in the service's 100 ms chunks.
 * Needs `scripts/build-sherpa-onnx.sh` and `scripts/fetch-models.sh` first.
 * Latency and RTF are logged under the tag "TurnCaptureDeviceTest".
 */
@RunWith(AndroidJUnit4::class)
class TurnCaptureDeviceTest {
    private companion object {
        const val TAG = "TurnCaptureDeviceTest"
        const val RATE = SAMPLE_RATE_HZ
        const val CHUNK = RATE / 10
        const val SESSION_START_MS = 1_791_296_527_000L

        /** What the shipped model hears in the upstream wavs (`test_wavs/trans.txt`, lower-cased). */
        const val TEXT_0 =
            "after early nightfall the yellow lamps would light up here and there the squalid quarter of the brothels"
        const val TEXT_1 =
            "god as a direct consequence of the sin which man thus punished had given her a lovely child " +
                "whose place was on that same dishonoured bosom to connect her parent for ever with the race " +
                "and descent of mortals and to be finally a blessed soul in heaven"
    }

    private class Run(val turns: List<Triple<Turn, Utterance, SttTiming?>>, val maxAcceptMs: Double)

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var vad: Vad

    @Before
    fun setUp() {
        root = File(target.cacheDir, "turn-capture-test").apply { deleteRecursively(); mkdirs() }
        vad = SpeechModels.newVad(context.assets)
    }

    @After
    fun tearDown() {
        vad.release()
        root.deleteRecursively()
    }

    private fun wav(name: String): ShortArray {
        val w = WaveReader.readWave(context.assets, "test_wavs/$name")
        assertEquals(RATE, w.sampleRate)
        // WaveReader divides int16 by 32768, so this recovers the file's samples exactly.
        return ShortArray(w.samples.size) { Math.round(w.samples[it] * 32768f).toShort() }
    }

    /** Room-tone stand-in: |x| <= 30 of 32767, never digital zero. */
    private fun noise(ms: Int, rnd: Random) = ShortArray(RATE / 1000 * ms) { (rnd.nextInt(61) - 30).toShort() }

    private class Piece(val start: Int, val end: Int, val speech: Boolean)

    private fun splice(vararg parts: ShortArray, speech: (Int) -> Boolean): Pair<ShortArray, List<Piece>> {
        val out = ShortArray(parts.sumOf { it.size })
        val pieces = mutableListOf<Piece>()
        var at = 0
        parts.forEachIndexed { i, p ->
            p.copyInto(out, at)
            pieces += Piece(at, at + p.size, speech(i))
            at += p.size
        }
        return out to pieces
    }

    private fun runSession(stream: ShortArray, stopEarlyAt: Int = stream.size): List<Pair<Turn, Utterance>> =
        run(stream, stopEarlyAt).turns.map { (t, u, _) -> t to u }

    /**
     * One Session through [TurnCapture]. [realTime] paces the chunks like the
     * mic does and starts the Session clock now, so `finished_at - ended_at` is
     * the latency a speaker would see (less AudioRecord's own buffering).
     */
    private fun run(
        stream: ShortArray,
        stopEarlyAt: Int = stream.size,
        realTime: Boolean = false,
        recognizer: () -> StreamingRecognizer = { SttRecognizer.load(context.assets) },
    ): Run {
        val writer = FileCorpusWriter(File(root, "corpus"), File(root, "staging"))
        val out = java.util.Collections.synchronizedList(mutableListOf<Triple<Turn, Utterance, SttTiming?>>())
        val startMs = if (realTime) System.currentTimeMillis() else SESSION_START_MS
        val capture = TurnCapture(
            writer, "5e5510", startMs, "test", Timings(),
            TurnCapture.silero(vad), SpeechModels.vadConfig().sileroVadModelConfig.windowSize,
            recognizer,
        ) { t, u, timing ->
            Log.i(TAG, "Turn ${t.directoryName} ${u.closedBy}: ${SttTiming.summary(t, timing)}: ${t.transcript?.text}")
            out += Triple(t, u, timing)
        }
        val buf = ShortArray(CHUNK)
        var i = 0
        var maxAcceptNs = 0L
        val t0 = System.nanoTime()
        while (i < stopEarlyAt) {
            val n = minOf(CHUNK, stopEarlyAt - i)
            if (realTime) {
                // The mic hands over a chunk once its last sample is captured.
                val due = t0 + (i + n) * 1_000_000_000L / RATE
                while (System.nanoTime() < due) Thread.sleep(1)
            }
            stream.copyInto(buf, 0, i, i + n)
            val a = System.nanoTime()
            capture.accept(buf, n)
            maxAcceptNs = maxOf(maxAcceptNs, System.nanoTime() - a)
            i += n
        }
        val f = System.nanoTime()
        capture.finish()
        Log.i(
            TAG,
            "processed ${stopEarlyAt * 1000L / RATE} ms of audio in ${(f - t0) / 1_000_000} ms " +
                "(realTime=$realTime), finish ${(System.nanoTime() - f) / 1_000_000} ms, " +
                "slowest accept ${"%.2f".format(maxAcceptNs / 1e6)} ms",
        )
        return Run(out.sortedBy { it.second.startSample }, maxAcceptNs / 1e6)
    }

    private fun turnJson(turn: Turn) = File(root, "corpus/${turn.directoryName}/$TURN_FILE").readText()

    /** The published `audio.wav`'s PCM, as little-endian bytes after the 44-byte header. */
    private fun publishedPcm(turn: Turn): ByteArray {
        val bytes = File(File(root, "corpus/${turn.directoryName}"), AUDIO_FILE).readBytes()
        return bytes.copyOfRange(Wav.HEADER_BYTES, bytes.size)
    }

    private fun le(stream: ShortArray, from: Long, to: Long): ByteArray {
        val out = ByteArray(((to - from) * 2).toInt())
        Wav.pcm16ToLittleEndian(stream.copyOfRange(from.toInt(), to.toInt()), (to - from).toInt(), out)
        return out
    }

    @Test
    fun threeUtterancesBetweenSilencesBecomeThreeByteExactTurns() {
        val rnd = Random(9)
        val (stream, pieces) = splice(
            noise(2000, rnd), wav("0.wav"), noise(3000, rnd), wav("1.wav"),
            noise(3000, rnd), wav("0.wav"), noise(3000, rnd),
        ) { it % 2 == 1 }
        val turns = runSession(stream)
        for ((t, u) in turns) Log.i(TAG, "Turn ${t.directoryName}: samples ${u.startSample}..${u.endSample} ${u.closedBy} vad=${t.vad}")

        assertEquals("one Turn per spliced utterance", 3, turns.size)
        val speech = pieces.filter { it.speech }
        turns.forEachIndexed { k, (turn, u) ->
            // Byte-identical to its range of the captured stream.
            assertArrayEquals("Turn $k bytes", le(stream, u.startSample, u.endSample), publishedPcm(turn))
            // The range covers its utterance and stays inside the silences around it.
            val s = speech[k]
            val before = pieces[pieces.indexOf(s) - 1]
            val after = pieces[pieces.indexOf(s) + 1]
            assertTrue("Turn $k starts inside the silence before it", u.startSample >= before.start && u.startSample <= s.start + RATE / 2)
            assertTrue("Turn $k ends inside the silence after it", u.endSample >= s.end && u.endSample <= after.end)
            assertEquals(CloseReason.SILENCE, u.closedBy)
            assertEquals(1500L, turn.vad!!.trailingSilenceMs)
            assertTrue("Turn $k speech_ms ${turn.vad!!.speechMs}", turn.vad!!.speechMs > (s.end - s.start) * 1000L / RATE / 2)
            assertEquals(Wav.durationMs(u.lengthSamples, RATE), turn.audio.durationMs)
            assertEquals(SESSION_START_MS + u.startSample * 1000 / RATE, turn.startedAtMs)
            val json = File(root, "corpus/${turn.directoryName}/$TURN_FILE").readText()
            assertTrue(json, json.contains("\"vad\": { \"speech_ms\": ${turn.vad!!.speechMs}, \"trailing_silence_ms\": 1500 }"))
        }
        // Turns are in stream order and never overlap.
        turns.zipWithNext { (_, a), (_, b) -> assertTrue(a.endSample <= b.startSample) }
        assertEquals(3, File(root, "corpus").list()!!.size)
        assertEquals(0, File(root, "staging").list()?.size ?: 0)
    }

    @Test
    fun eachTurnIsTranscribedFromItsOwnStream() {
        val rnd = Random(9)
        val (stream, _) = splice(
            noise(2000, rnd), wav("0.wav"), noise(3000, rnd), wav("1.wav"),
            noise(3000, rnd), wav("0.wav"), noise(3000, rnd),
        ) { it % 2 == 1 }
        val turns = run(stream).turns
        assertEquals(listOf(TEXT_0, TEXT_1, TEXT_0), turns.map { it.first.transcript?.text })
        for ((turn, _, timing) in turns) {
            val t = turn.transcript!!
            assertEquals("sherpa-onnx", t.engine)
            assertEquals(
                "sherpa-onnx-streaming-zipformer-en-2023-06-26/epoch-99-avg-1-chunk-16-left-128.int8 modified_beam_search",
                t.model,
            )
            assertEquals(TurnKind.UNCLASSIFIED, turn.kind)
            assertTrue("timing for ${turn.directoryName}", timing != null)
            val json = turnJson(turn)
            assertTrue(json, json.contains("\"transcript\": {\n    \"text\": \"${t.text}\",\n    \"engine\": \"sherpa-onnx\",\n"))
            assertTrue(json, json.contains("\"model\": \"${t.model}\",\n"))
            assertTrue(json, json.contains("\"finished_at\": \""))
            assertTrue(json, json.contains("\"kind\": \"unclassified\",\n"))
        }
    }

    @Test
    fun realTimeSessionLatencyAndCaptureNeverWaitsOnDecoding() {
        val rnd = Random(10)
        val (stream, _) = splice(
            noise(1500, rnd), wav("0.wav"), noise(2500, rnd), wav("1.wav"), noise(2500, rnd),
        ) { it % 2 == 1 }
        val r = run(stream, realTime = true)
        assertEquals(listOf(TEXT_0, TEXT_1), r.turns.map { it.first.transcript?.text })
        for ((turn, _, timing) in r.turns) {
            val latency = turn.transcriptLatencyMs!!
            Log.i(TAG, "real-time Turn ${turn.directoryName}: latency $latency ms, ${SttTiming.summary(turn, timing)}")
            // Close is seen within a chunk of ended_at; the transcript follows the final flush.
            assertTrue("latency $latency ms", latency in 0..2_000)
        }
        // Capture keeps up with the mic: no accept() comes near a 100 ms chunk.
        assertTrue("slowest accept ${r.maxAcceptMs} ms", r.maxAcceptMs < 50.0)
    }

    @Test
    fun withoutARecognizerTurnsAreStillWrittenUntranscribed() {
        val rnd = Random(11)
        val (stream, _) = splice(noise(1000, rnd), wav("0.wav"), noise(2500, rnd)) { it == 1 }
        val turns = run(stream, recognizer = { throw IllegalStateException("no model") }).turns
        val (turn, _, timing) = turns.single()
        assertNull(turn.transcript)
        assertNull(timing)
        assertEquals(TurnKind.UNCLASSIFIED, turn.kind)
        assertTrue(!turnJson(turn).contains("\"transcript\""))
    }

    @Test
    fun stoppingMidUtteranceClosesItAsATurn() {
        val rnd = Random(4)
        val (stream, pieces) = splice(noise(1000, rnd), wav("0.wav")) { it == 1 }
        val stopAt = (pieces[1].start + pieces[1].end) / 2
        val (turn, u) = runSession(stream, stopEarlyAt = stopAt).single()
        assertEquals(CloseReason.SESSION_END, u.closedBy)
        assertEquals(stopAt.toLong(), u.endSample) // up to the last captured sample, unjudged partial window included
        assertArrayEquals(le(stream, u.startSample, u.endSample), publishedPcm(turn))
        // The cut-off Turn is still transcribed, up to where it was cut.
        val text = turn.transcript!!.text
        Log.i(TAG, "cut-off transcript: $text")
        assertTrue(text, text.startsWith("after early nightfall"))
        assertTrue(text, !text.contains("brothels"))
    }

    @Test
    fun noiseAloneYieldsNoTurns() {
        assertEquals(0, runSession(noise(10_000, Random(1))).size)
    }
}
