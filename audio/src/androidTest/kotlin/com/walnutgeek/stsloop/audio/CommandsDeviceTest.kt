package com.walnutgeek.stsloop.audio

import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.WaveReader
import com.walnutgeek.stsloop.audio.speech.SpeechModels
import com.walnutgeek.stsloop.audio.speech.SttRecognizer
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.TURN_FILE
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnKind
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.speech.RecognitionStream
import com.walnutgeek.stsloop.core.speech.StreamingRecognizer
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
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * "Repeat", "what bucket", "stop listening" (#14) and Focus (#40) through the service's
 * pipeline on the phone: the real VAD, the real [FileCorpusWriter] and the
 * real [EchoSpeaker] (audible), with a recognizer that returns scripted
 * transcripts. Writes only under the test's own cache directory, never the
 * app's Corpus.
 */
@RunWith(AndroidJUnit4::class)
class CommandsDeviceTest {
    private companion object {
        const val TAG = "CommandsDeviceTest"
        const val RATE = SAMPLE_RATE_HZ
        const val CHUNK = RATE / 10
    }

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var vad: Vad

    @Before
    fun setUp() {
        root = File(target.cacheDir, "commands-test").apply { deleteRecursively(); mkdirs() }
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
    fun focusRepeatWhatBucketThenStopListeningEndsTheSessionOnlyAfterItsReplyIsSaid() {
        val rnd = Random(14)
        fun noise(ms: Int) = ShortArray(RATE / 1000 * ms) { (rnd.nextInt(61) - 30).toShort() }
        val w = WaveReader.readWave(context.assets, "test_wavs/0.wav")
        val speech = ShortArray(w.samples.size) { Math.round(w.samples[it] * 32768f).toShort() }

        val corpus = File(root, "corpus")
        val turns = Collections.synchronizedList(mutableListOf<Turn>())
        val said = Collections.synchronizedList(mutableListOf<String>())
        val ended = Collections.synchronizedList(mutableListOf<Long>()) // machine Turn ids the engine reported done
        val endedAt = Collections.synchronizedList(mutableListOf<List<Long>>()) // [ended] when the Session was ended
        val endedOn = Collections.synchronizedList(mutableListOf<Turn>())
        var capture: TurnCapture? = null
        val echo = EchoSpeaker(target, "5c4a7d") { id, atNs ->
            ended += id
            capture?.spoken(id, atNs)
        }
        val speaker = object : Speaker {
            override fun speak(id: Long, text: String) {
                said += text
                echo.speak(id, text)
            }
            override fun abandon(id: Long) = echo.abandon(id)
        }
        echo.start()
        val c = TurnCapture(
            FileCorpusWriter(corpus, File(root, "staging")), "5c4a7d", System.currentTimeMillis(), "test",
            Timings(), TurnCapture.silero(vad), SpeechModels.vadConfig().sileroVadModelConfig.windowSize,
            recognizer = { Scripted(listOf("HOUSE PROJECT", "CHECK THE JOISTS", "SAY THAT AGAIN", "WHICH BUCKET", "STOP LISTENING")) },
            speaker = speaker,
            endSession = { turn ->
                endedAt += ended.toList()
                endedOn += turn
            },
        ) { t, _, _ -> turns += t }
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
            for (n in 1..4) {
                feed(speech, forMs(speech.size * 1000 / RATE))
                feed(noise(1000), untilSaid(n))
                feed(noise(1000), forMs(4000)) // the echo plays, then the guard
            }
            feed(speech, forMs(speech.size * 1000 / RATE)) // "stop listening"
            // The Session keeps capturing until it is told to end, as SessionService's loop does.
            val deadline = fed + RATE * 20L
            feed(noise(1000)) { endedOn.isNotEmpty() || fed >= deadline }
        } finally {
            c.finish()
            echo.close()
        }
        Log.i(TAG, "turns: ${turns.map { it.directoryName to it.transcript?.text }}; said: $said; ended: $ended at $endedAt")

        assertEquals(5, turns.size)
        val (focus, note, repeat, which, stop) = turns
        assertEquals(TurnKind.NOTE, note.kind)
        assertEquals("house-project", note.classification?.bucket)
        assertEquals(listOf("house project.", "check the joists", "check the joists", "house project.", "stopped listening."), said.toList())
        val focused = Json.parseObject(File(corpus, "${focus.directoryName}/$TURN_FILE").readText())
        assertEquals("focus", (focused["command"] as Map<*, *>)["name"])
        assertEquals("house-project", focused["focus"])
        assertEquals("focus", Json.parseObject(File(corpus, "${note.directoryName}/$TURN_FILE").readText())["bucket_source"])

        // Ended once, on the persisted command Turn, after the engine reported the reply's end.
        assertEquals(listOf(stop), endedOn.toList())
        assertTrue("ended before the reply was said: $endedAt", endedAt.single().contains(4L))

        for ((turn, command) in listOf(repeat to Command.REPEAT, which to Command.WHERE_AM_I, stop to Command.STOP_LISTENING)) {
            val written = Json.parseObject(File(corpus, "${turn.directoryName}/$TURN_FILE").readText())
            assertEquals("command", written["kind"])
            assertEquals(command.json, (written["command"] as Map<*, *>)["name"])
            assertTrue(!written.containsKey("tombstones"))
        }
    }

    /**
     * A lone alias focuses its Bucket; then, with no speech for the focus
     * timeout, Focus returns to the Inbox and the loop says so (#40).
     */
    @Test
    fun focusTimesOutBackToTheInboxAndSaysSo() {
        val rnd = Random(40)
        fun noise(ms: Int) = ShortArray(RATE / 1000 * ms) { (rnd.nextInt(61) - 30).toShort() }
        val w = WaveReader.readWave(context.assets, "test_wavs/0.wav")
        val speech = ShortArray(w.samples.size) { Math.round(w.samples[it] * 32768f).toShort() }
        val said = Collections.synchronizedList(mutableListOf<String>())
        var capture: TurnCapture? = null
        val echo = EchoSpeaker(target, "5c4a7e") { id, atNs -> capture?.spoken(id, atNs) }
        val speaker = object : Speaker {
            override fun speak(id: Long, text: String) {
                said += text
                echo.speak(id, text)
            }
            override fun abandon(id: Long) = echo.abandon(id)
        }
        echo.start()
        val c = TurnCapture(
            FileCorpusWriter(File(root, "corpus"), File(root, "staging")), "5c4a7e", System.currentTimeMillis(), "test",
            Timings(focusTimeoutMs = Timings.FOCUS_TIMEOUT_FLOOR_MS), TurnCapture.silero(vad),
            SpeechModels.vadConfig().sileroVadModelConfig.windowSize,
            recognizer = { Scripted(listOf("ERRANDS")) },
            speaker = speaker,
        )
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
        try {
            feed(noise(1000)) { fed >= RATE }
            val end = fed + speech.size
            feed(speech) { fed >= end }
            val deadline = fed + RATE * 30L
            feed(noise(1000)) { said.size >= 2 || fed >= deadline }
        } finally {
            c.finish()
            echo.close()
        }
        assertEquals(listOf("errands.", "back to inbox."), said.toList())
    }

    /**
     * Not a pass/fail check of the model: what the bundled recognizer makes of
     * each command phrase spoken by the system TTS voice, logged so obvious
     * misrecognitions are visible (`adb logcat -s CommandsDeviceTest`). The
     * grammar is deliberately not fuzzy, so a garbled phrase is stored as a Note.
     */
    @Test
    fun probeWhatTheModelHearsForEachCommandPhrase() {
        val dir = File(root, "tts").apply { mkdirs() }
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts = TextToSpeech(target) { s -> status = s; ready.countDown() }
        val stt = SttRecognizer.load(context.assets)
        val grammar = PhraseGrammar(BucketConfig.DEFAULT)
        val report = mutableListOf<String>()
        try {
            assertTrue(ready.await(20, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, status)
            tts.language = Locale.US
            for (phrase in Command.entries.flatMap { it.phrases }) {
                val file = File(dir, phrase.replace(' ', '_') + ".wav")
                val done = CountDownLatch(1)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = done.countDown()
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = done.countDown()
                })
                tts.synthesizeToFile(phrase, null, file, phrase)
                assertTrue(done.await(20, TimeUnit.SECONDS))
                val wave = WaveReader.readWave(file.absolutePath)
                val samples = resample(wave.samples, wave.sampleRate, RATE)
                val stream = stt.open()
                val text = try {
                    stream.accept(FloatArray(RATE / 2))
                    stream.accept(samples)
                    stream.accept(FloatArray(RATE / 2))
                    stream.finish()
                } finally {
                    stream.release()
                }
                val c = grammar.classify(text)
                report += "'$phrase' -> '$text' -> ${c.command?.command?.json ?: c.kind.json}"
            }
        } finally {
            tts.shutdown()
            stt.close()
        }
        for (r in report) Log.i(TAG, "heard: $r")
        assertEquals(Command.entries.sumOf { it.phrases.size }, report.size)
    }

    /** Linear resampling: good enough to probe a recognizer with TTS audio. */
    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        val n = (x.size.toLong() * to / from).toInt()
        return FloatArray(n) { i ->
            val pos = i.toDouble() * from / to
            val j = pos.toInt()
            val f = (pos - j).toFloat()
            val a = x[minOf(j, x.size - 1)]
            val b = x[minOf(j + 1, x.size - 1)]
            a + (b - a) * f
        }
    }
}
