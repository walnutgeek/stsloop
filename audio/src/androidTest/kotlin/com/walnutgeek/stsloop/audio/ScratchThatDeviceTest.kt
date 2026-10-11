package com.walnutgeek.stsloop.audio

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.WaveReader
import com.walnutgeek.stsloop.audio.speech.SpeechModels
import com.walnutgeek.stsloop.core.TURN_FILE
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnKind
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.TranscriptList
import com.walnutgeek.stsloop.core.speech.RecognitionStream
import com.walnutgeek.stsloop.core.speech.StreamingRecognizer
import com.walnutgeek.stsloop.core.turn.Speaker
import com.walnutgeek.stsloop.core.turn.Timings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * "Scratch that" (#13) through the service's pipeline on the phone: the real
 * VAD, the real [FileCorpusWriter] and the real [EchoSpeaker] (audible), with
 * a recognizer that returns scripted transcripts, so the test does not depend
 * on how the model hears the phrase. Writes only under the test's own cache
 * directory, never the app's Corpus.
 */
@RunWith(AndroidJUnit4::class)
class ScratchThatDeviceTest {
    private companion object {
        const val TAG = "ScratchThatDeviceTest"
        const val RATE = SAMPLE_RATE_HZ
        const val CHUNK = RATE / 10
    }

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var vad: Vad

    @Before
    fun setUp() {
        root = File(target.cacheDir, "scratch-that-test").apply { deleteRecursively(); mkdirs() }
        vad = SpeechModels.newVad(context.assets)
    }

    @After
    fun tearDown() {
        vad.release()
        root.deleteRecursively()
    }

    /** Hands out [texts] in order, one per Turn. */
    private class Scripted(texts: List<String>) : StreamingRecognizer {
        private val queue = ConcurrentLinkedQueue(texts)
        override val engine = "scripted"
        override val model = "scripted"
        override fun open(): RecognitionStream = object : RecognitionStream {
            override fun accept(samples: FloatArray) {}
            override fun finish(): String = queue.poll() ?: ""
            override fun release() {}
        }
        override fun close() {}
    }

    @Test
    fun scratchThatTombstonesThePreviousTurnWithoutTouchingIt() {
        val rnd = Random(13)
        fun noise(ms: Int) = ShortArray(RATE / 1000 * ms) { (rnd.nextInt(61) - 30).toShort() }
        val w = WaveReader.readWave(context.assets, "test_wavs/0.wav")
        val speech = ShortArray(w.samples.size) { Math.round(w.samples[it] * 32768f).toShort() }

        val corpus = File(root, "corpus")
        val turns = Collections.synchronizedList(mutableListOf<Turn>())
        val firstJsonAtPublish = arrayOfNulls<ByteArray>(1)
        val said = Collections.synchronizedList(mutableListOf<String>())
        var capture: TurnCapture? = null
        val echo = EchoSpeaker(target, "5c4a7c") { id, atNs -> capture?.spoken(id, atNs) }
        val speaker = object : Speaker {
            override fun speak(id: Long, text: String) {
                said += text
                echo.speak(id, text)
            }
            override fun abandon(id: Long) = echo.abandon(id)
        }
        echo.start()
        val c = TurnCapture(
            FileCorpusWriter(corpus, File(root, "staging")), "5c4a7c", System.currentTimeMillis(), "test",
            Timings(), TurnCapture.silero(vad), SpeechModels.vadConfig().sileroVadModelConfig.windowSize,
            recognizer = { Scripted(listOf("ERRANDS BUY MILK", "SCRATCH THAT")) },
            speaker = speaker,
        ) { t, _, _ ->
            if (turns.isEmpty()) firstJsonAtPublish[0] = File(corpus, "${t.directoryName}/$TURN_FILE").readBytes()
            turns += t
        }
        capture = c

        val t0 = System.nanoTime()
        var fed = 0L
        val buf = ShortArray(CHUNK)
        fun feed(pcm: ShortArray, until: () -> Boolean) {
            var i = 0
            while (!until()) {
                for (k in 0 until CHUNK) buf[k] = pcm[(i + k) % pcm.size]
                i = (i + CHUNK) % pcm.size
                fed += CHUNK
                val due = t0 + fed * 1_000_000_000L / RATE
                while (System.nanoTime() < due) Thread.sleep(1)
                c.accept(buf, CHUNK, SystemClock.elapsedRealtimeNanos())
            }
        }
        fun forMs(ms: Int): () -> Boolean { val end = fed + RATE / 1000L * ms; return { fed >= end } }
        fun untilSaid(n: Int): () -> Boolean { val deadline = fed + RATE * 20L; return { said.size >= n || fed >= deadline } }

        try {
            feed(noise(1000), forMs(1000))
            feed(speech, forMs(speech.size * 1000 / RATE)) // "errands buy milk"
            feed(noise(1000), untilSaid(1))
            feed(noise(1000), forMs(4000)) // the echo plays, then the guard
            feed(speech, forMs(speech.size * 1000 / RATE)) // "scratch that"
            feed(noise(1000), untilSaid(2))
            feed(noise(1000), forMs(3000))
        } finally {
            c.finish()
            echo.close()
        }
        Log.i(TAG, "turns: ${turns.map { it.directoryName to it.transcript?.text }}; said: $said")

        assertEquals(2, turns.size)
        val (note, scratch) = turns
        assertEquals(TurnKind.NOTE, note.kind)
        assertEquals(TurnKind.COMMAND, scratch.kind)
        assertEquals(listOf("errands buy milk", "dropped."), said.toList())

        // The command Turn names its target by directory name, in its own turn.json.
        val written = Json.parseObject(File(corpus, "${scratch.directoryName}/$TURN_FILE").readText())
        assertEquals("command", written["kind"])
        assertEquals(mapOf("name" to "scratch_that", "matched" to "scratch that"), written["command"])
        assertEquals(note.directoryName, written["tombstones"])
        assertFalse(written.containsKey("tombstoned_by"))

        // The target is untouched, audio and all.
        val noteDir = File(corpus, note.directoryName)
        assertTrue(File(noteDir, "audio.wav").isFile)
        assertTrue(firstJsonAtPublish[0]!!.contentEquals(File(noteDir, TURN_FILE).readBytes()))

        // The transcript list marks it from the command Turn.
        val listed = TranscriptList.of(
            corpus.listFiles()!!.filter { TranscriptList.isTurnEntry(it.name) }.map {
                TranscriptList.read(it.name, File(it, TURN_FILE).readText())
            },
        ).associateBy { it.directoryName }
        assertEquals(scratch.directoryName, listed.getValue(note.directoryName).tombstonedBy)
        assertFalse(listed.getValue(scratch.directoryName).tombstoned)
    }
}
