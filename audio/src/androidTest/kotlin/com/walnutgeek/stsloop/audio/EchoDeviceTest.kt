package com.walnutgeek.stsloop.audio

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.WaveReader
import com.walnutgeek.stsloop.audio.speech.SpeechModels
import com.walnutgeek.stsloop.audio.speech.SttRecognizer
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.hub.Item
import com.walnutgeek.stsloop.core.hub.ItemSpoken
import com.walnutgeek.stsloop.core.hub.Message
import com.walnutgeek.stsloop.core.turn.Speaker
import com.walnutgeek.stsloop.core.turn.Timings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The spoken echo with Half-duplex mic gating, on the phone: the real
 * [EchoSpeaker] (system TextToSpeech, audible), the real VAD and recognizer,
 * and a synthetic mic fed at real time on the capture clock. While the echo
 * plays, the "mic" carries loud speech (an upstream test wav, standing in for
 * the machine's own voice coming back); none of it may become a Turn.
 * Needs `scripts/build-sherpa-onnx.sh` and `scripts/fetch-models.sh` first.
 */
@RunWith(AndroidJUnit4::class)
class EchoDeviceTest {
    private companion object {
        const val TAG = "EchoDeviceTest"
        const val RATE = SAMPLE_RATE_HZ
        const val CHUNK = RATE / 10
    }

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var vad: Vad

    @Before
    fun setUp() {
        root = File(target.cacheDir, "echo-test").apply { deleteRecursively(); mkdirs() }
        vad = SpeechModels.newVad(context.assets)
    }

    @After
    fun tearDown() {
        vad.release()
        root.deleteRecursively()
    }

    private fun wav(name: String): ShortArray {
        val w = WaveReader.readWave(context.assets, "test_wavs/$name")
        return ShortArray(w.samples.size) { Math.round(w.samples[it] * 32768f).toShort() }
    }

    @Test
    fun theEngineReportsTheEndOfEveryEcho() {
        val ends = Collections.synchronizedList(mutableListOf<Long>())
        val done = CountDownLatch(2)
        val speaker = EchoSpeaker(target, "echo01") { id, _ -> ends += id; done.countDown() }
        speaker.start()
        try {
            speaker.speak(1, "echo test one")
            speaker.speak(2, "two")
            assertTrue("no end reported within 20 s", done.await(20, TimeUnit.SECONDS))
        } finally {
            speaker.close()
        }
        assertEquals(listOf(1L, 2L), ends.toList())
        assertEquals(2, speaker.spoken)
        assertEquals(0, speaker.failed)
        Log.i(TAG, "unobserved: ${speaker.unobserved}")
    }

    @Test
    fun anItemPlaysItsEarconThenItsSentenceAndReportsOneEnd() {
        // The engine reports no progress for an earcon, so it is seen in the timing: the same sentence
        // takes about the earcon's 300 ms longer as an Item. Each is said twice, interleaved, and the
        // faster of each pair is compared, so warming up the engine cannot pass for an earcon.
        val ends = Collections.synchronizedList(mutableListOf<Long>())
        val heard = Collections.synchronizedList(mutableListOf<Boolean>())
        var done = CountDownLatch(1)
        val speaker = EchoSpeaker(target, "echo03", played = { heard += it }) { id, _ -> ends += id; done.countDown() }
        speaker.start()
        val text = "stsloop agent is blocked."
        val took = mutableMapOf<Boolean, MutableList<Long>>(true to mutableListOf(), false to mutableListOf())
        try {
            for ((id, item) in listOf(1L to false, 2L to true, 3L to false, 4L to true)) {
                done = CountDownLatch(1)
                val t0 = SystemClock.elapsedRealtime()
                if (item) speaker.alert(id, text) else speaker.speak(id, text)
                assertTrue("no end reported within 20 s", done.await(20, TimeUnit.SECONDS))
                took.getValue(item) += SystemClock.elapsedRealtime() - t0
            }
        } finally {
            speaker.close()
        }
        Log.i(TAG, "said as an Item: ${took[true]} ms, as a reply: ${took[false]} ms; heard $heard")
        assertEquals(listOf(1L, 2L, 3L, 4L), ends.toList())
        assertEquals(4, speaker.spoken)
        assertEquals(0, speaker.failed)
        val longer = took.getValue(true).min() - took.getValue(false).min()
        assertTrue("an Item takes only $longer ms longer than its sentence: no earcon?", longer >= 200)
    }

    @Test
    fun anItemFromTheHubIsSaidIntoSilenceAndTheHubIsTold() {
        val rnd = Random(7)
        fun noise(ms: Int) = ShortArray(RATE / 1000 * ms) { (rnd.nextInt(61) - 30).toShort() }
        val alerts = Collections.synchronizedList(mutableListOf<String>())
        val sent = Collections.synchronizedList(mutableListOf<Message>())
        val ended = CountDownLatch(1)
        var capture: TurnCapture? = null
        val echo = EchoSpeaker(target, "echo04") { id, atNs ->
            capture?.spoken(id, atNs)
            ended.countDown()
        }
        val speaker = object : Speaker {
            override fun speak(id: Long, text: String) = echo.speak(id, text)
            override fun alert(id: Long, text: String) {
                alerts += text
                echo.alert(id, text)
            }
        }
        echo.start()
        val c = TurnCapture(
            FileCorpusWriter(File(root, "corpus"), File(root, "staging")), "ec4012", System.currentTimeMillis(), "test",
            Timings(), TurnCapture.silero(vad), SpeechModels.vadConfig().sileroVadModelConfig.windowSize,
            recognizer = { SttRecognizer.load(context.assets) },
            hubSend = { m -> sent += m; true },
            speaker = speaker,
        ) { _, _, _ -> }
        capture = c
        val item = Item("a1", listOf("herdr:w8:p1"), "stsloop agent is blocked.")
        val pcm = noise(1000)
        val t0 = System.nanoTime()
        var fed = 0L
        val buf = ShortArray(CHUNK)
        try {
            c.item(item)
            c.item(item) // sent again: still said once
            val deadline = RATE * 30L
            var i = 0
            while (ended.count > 0 && fed < deadline) {
                for (k in 0 until CHUNK) buf[k] = pcm[(i + k) % pcm.size]
                i = (i + CHUNK) % pcm.size
                fed += CHUNK
                val due = t0 + fed * 1_000_000_000L / RATE
                while (System.nanoTime() < due) Thread.sleep(1)
                c.accept(buf, CHUNK, SystemClock.elapsedRealtimeNanos())
            }
            assertEquals("the item was never said", 0L, ended.count)
            repeat(5) { c.accept(buf, CHUNK, SystemClock.elapsedRealtimeNanos()) } // the end is taken in on the next chunk
        } finally {
            c.finish()
            echo.close()
        }
        assertEquals(listOf("stsloop agent is blocked."), alerts.toList())
        assertTrue("not before a full Silence: fed ${fed * 1000 / RATE} ms", fed >= RATE * Timings().trailingSilenceMs / 1000L)
        assertEquals(listOf<Message>(ItemSpoken("a1")), sent.toList())
    }

    @Test
    fun aTurnIsEchoedAndNothingHeardWhileItPlaysBecomesATurn() {
        val rnd = Random(5)
        fun noise(ms: Int) = ShortArray(RATE / 1000 * ms) { (rnd.nextInt(61) - 30).toShort() }
        val human = wav("0.wav")
        val machine = wav("1.wav") // loud, continuous speech: what the mic would hear of the echo

        val turns = Collections.synchronizedList(mutableListOf<Turn>())
        val said = Collections.synchronizedList(mutableListOf<String>())
        val started = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var capture: TurnCapture? = null
        val echo = EchoSpeaker(target, "echo02") { id, atNs ->
            capture?.spoken(id, atNs)
            ended.countDown()
        }
        val speaker = Speaker { id, text ->
            said += text
            started.countDown()
            echo.speak(id, text)
        }
        echo.start()
        val c = TurnCapture(
            FileCorpusWriter(File(root, "corpus"), File(root, "staging")), "ec4011", System.currentTimeMillis(), "test",
            Timings(), TurnCapture.silero(vad), SpeechModels.vadConfig().sileroVadModelConfig.windowSize,
            recognizer = { SttRecognizer.load(context.assets) },
            speaker = speaker,
        ) { t, _, _ -> turns += t }
        capture = c

        val t0 = System.nanoTime()
        var fed = 0L
        val buf = ShortArray(CHUNK)
        /** Feeds [pcm] (looping) at real time, one 100 ms chunk at a time, until [until] says stop. */
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

        try {
            feed(noise(1000), forMs(1000))
            feed(human, forMs(human.size * 1000 / RATE))
            val deadline = fed + RATE * 10L
            feed(noise(1000)) { started.count == 0L || fed >= deadline } // Silence, then the echo starts
            assertEquals("the echo never started", 0L, started.count)
            val speakDeadline = fed + RATE * 30L
            feed(machine) { ended.count == 0L || fed >= speakDeadline } // the machine's own voice, as loud as a human
            assertEquals("the echo never ended", 0L, ended.count)
            feed(noise(1000), forMs(3000))
        } finally {
            c.finish()
            echo.close()
        }
        Log.i(TAG, "turns: ${turns.map { it.transcript?.text }}; said: $said; unobserved ${echo.unobserved}")
        assertEquals(1, turns.size)
        val heard = turns.single().transcript?.text?.trim()
        assertTrue("transcript: $heard", heard!!.startsWith("After early nightfall"))
        assertEquals(listOf(heard), said.toList()) // mixed-case text is spoken as is
        assertEquals(1, echo.spoken)
    }
}
