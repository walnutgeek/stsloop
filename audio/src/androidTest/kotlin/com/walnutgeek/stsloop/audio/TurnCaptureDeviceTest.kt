package com.walnutgeek.stsloop.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.WaveReader
import com.walnutgeek.stsloop.audio.speech.SpeechModels
import com.walnutgeek.stsloop.core.AUDIO_FILE
import com.walnutgeek.stsloop.core.TURN_FILE
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.Wav
import com.walnutgeek.stsloop.core.turn.CloseReason
import com.walnutgeek.stsloop.core.turn.Timings
import com.walnutgeek.stsloop.core.turn.Utterance
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * Feeds a synthetic Session through the same [TurnCapture] the service uses,
 * with the real Silero VAD and the real [FileCorpusWriter]: upstream test wavs
 * spliced between stretches of low-level noise, in the service's 100 ms chunks.
 * Needs `scripts/build-sherpa-onnx.sh` and `scripts/fetch-models.sh` first.
 */
@RunWith(AndroidJUnit4::class)
class TurnCaptureDeviceTest {
    private companion object {
        const val TAG = "TurnCaptureDeviceTest"
        const val RATE = SAMPLE_RATE_HZ
        const val CHUNK = RATE / 10
        const val SESSION_START_MS = 1_791_296_527_000L
    }

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

    private fun runSession(stream: ShortArray, stopEarlyAt: Int = stream.size): List<Pair<Turn, Utterance>> {
        val writer = FileCorpusWriter(File(root, "corpus"), File(root, "staging"))
        val out = mutableListOf<Pair<Turn, Utterance>>()
        val capture = TurnCapture(
            writer, "5e5510", SESSION_START_MS, "test", Timings(),
            TurnCapture.silero(vad), SpeechModels.vadConfig().sileroVadModelConfig.windowSize,
        ) { t, u -> out += t to u }
        val buf = ShortArray(CHUNK)
        var i = 0
        val t0 = System.nanoTime()
        while (i < stopEarlyAt) {
            val n = minOf(CHUNK, stopEarlyAt - i)
            stream.copyInto(buf, 0, i, i + n)
            capture.accept(buf, n)
            i += n
        }
        capture.finish()
        Log.i(TAG, "segmented ${stopEarlyAt * 1000L / RATE} ms of audio in ${(System.nanoTime() - t0) / 1_000_000} ms")
        return out
    }

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
    fun stoppingMidUtteranceClosesItAsATurn() {
        val rnd = Random(4)
        val (stream, pieces) = splice(noise(1000, rnd), wav("0.wav")) { it == 1 }
        val stopAt = (pieces[1].start + pieces[1].end) / 2
        val (turn, u) = runSession(stream, stopEarlyAt = stopAt).single()
        assertEquals(CloseReason.SESSION_END, u.closedBy)
        val window = SpeechModels.vadConfig().sileroVadModelConfig.windowSize
        assertEquals(stopAt.toLong() / window * window, u.endSample) // the partial last window is not judged
        assertArrayEquals(le(stream, u.startSample, u.endSample), publishedPcm(turn))
    }

    @Test
    fun noiseAloneYieldsNoTurns() {
        assertEquals(0, runSession(noise(10_000, Random(1))).size)
    }
}
