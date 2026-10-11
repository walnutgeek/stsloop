package com.walnutgeek.stsloop.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.WaveReader
import com.walnutgeek.stsloop.audio.speech.SpeechModels
import com.walnutgeek.stsloop.audio.speech.SttRecognizer
import com.walnutgeek.stsloop.core.AUDIO_FILE
import com.walnutgeek.stsloop.core.TURN_FILE
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.Wav
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.failure.SilentFailure
import com.walnutgeek.stsloop.core.turn.Speaker
import com.walnutgeek.stsloop.core.turn.Timings
import com.walnutgeek.stsloop.core.turnDirectoryName
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Silent failures (#15) on the phone: the real VAD, recognizer and Corpus
 * writer, fed a synthetic mic. A dead mic (zeros) is said once through the
 * echo path; a Recording's silence lands in `turn.json`; Turns a crash left
 * staged are published. Needs `scripts/build-sherpa-onnx.sh` and
 * `scripts/fetch-models.sh` first.
 */
@RunWith(AndroidJUnit4::class)
class SilentFailureDeviceTest {
    private companion object {
        const val RATE = SAMPLE_RATE_HZ
        const val CHUNK = RATE / 10
    }

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private val corpus get() = File(root, "corpus")
    private val staging get() = File(root, "staging")

    @Before
    fun setUp() {
        root = File(target.cacheDir, "silent-failure-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun json(dir: File) = Json.parseObject(File(dir, TURN_FILE).readText())

    @Suppress("UNCHECKED_CAST")
    private fun audioBlock(dir: File) = json(dir)["audio"] as Map<String, Any?>

    @Test
    fun aDeadMicIsSaidOnceThroughTheEchoAndAWorkingOneIsNot() {
        val vad: Vad = SpeechModels.newVad(context.assets)
        val said = Collections.synchronizedList(mutableListOf<String>())
        val turns = Collections.synchronizedList(mutableListOf<Turn>())
        var capture: TurnCapture? = null
        var nowNs = 0L
        // Speaks instantly: the end is reported on the capture clock as soon as it is asked for.
        val speaker = Speaker { id, text -> said += text; capture!!.spoken(id, nowNs) }
        val c = TurnCapture(
            FileCorpusWriter(corpus, staging), "dead01", System.currentTimeMillis(), "test",
            Timings(), TurnCapture.silero(vad), SpeechModels.vadConfig().sileroVadModelConfig.windowSize,
            recognizer = { SttRecognizer.load(context.assets) },
            speaker = speaker,
        ) { t, _, _ -> turns += t }
        capture = c
        val buf = ShortArray(CHUNK)
        fun feed(pcm: ShortArray) {
            var i = 0
            while (i < pcm.size) {
                val n = minOf(CHUNK, pcm.size - i)
                System.arraycopy(pcm, i, buf, 0, n)
                i += n
                nowNs += n * 1_000_000_000L / RATE
                c.accept(buf, n, nowNs)
            }
        }
        val rnd = Random(7)
        fun quietRoom(ms: Int) = ShortArray(RATE / 1000 * ms) { (rnd.nextInt(7) - 3).toShort() } // RMS about 2
        fun zeros(ms: Int) = ShortArray(RATE / 1000 * ms)
        val w = WaveReader.readWave(context.assets, "test_wavs/0.wav")
        val speech = ShortArray(w.samples.size) { Math.round(w.samples[it] * 32768f).toShort() }

        try {
            feed(quietRoom(10_000)) // a quiet but working mic: nothing to say
            assertEquals(emptyList<String>(), said.toList())
            feed(zeros(5_000)) // the mic dies
            assertEquals(listOf(SilentFailure.MIC_SILENT.spoken), said.toList())
            feed(zeros(5_000))
            feed(quietRoom(1_000)) // it comes back: not said, the next echo says it
            feed(speech)
            feed(quietRoom(3_000))
            // The feed outruns real time; give the recognizer time to publish the Turn, and the loop time to echo it.
            val deadline = System.currentTimeMillis() + 30_000
            while (said.size < 2 && System.currentTimeMillis() < deadline) {
                feed(quietRoom(100))
                Thread.sleep(20)
            }
            feed(zeros(5_000)) // dies again within a minute: not said again
        } finally {
            c.finish()
            vad.release()
        }
        assertEquals(1, turns.size)
        assertEquals(false, turns.single().audio.silent)
        assertEquals(false, audioBlock(File(corpus, turns.single().directoryName))["silent"])
        assertEquals(SilentFailure.MIC_SILENT.spoken, said.first())
        assertEquals(1, said.count { it == SilentFailure.MIC_SILENT.spoken })
        assertTrue("the Turn was echoed: $said", said.size == 2)
        assertEquals(1, c.announced)
    }

    @Test
    fun anAllZeroRecordingIsFlaggedSilentInTurnJson() {
        val writer = FileCorpusWriter(corpus, staging)
        val zeros = writer.begin("000001", "s00001", 1_791_296_527_431, RATE)
        zeros.append(ShortArray(RATE * 2), RATE * 2)
        val silent = zeros.finish("test")
        val quiet = writer.begin("000002", "s00001", 1_791_296_537_431, RATE)
        val floor = ShortArray(RATE * 2) { i -> if (i % 2 == 0) 3 else -3 } // RMS 3: a parked car's SCO mic
        quiet.append(floor, floor.size)
        val heard = quiet.finish("test")

        assertEquals(true, silent.audio.silent)
        assertEquals(true, audioBlock(File(corpus, silent.directoryName))["silent"])
        assertEquals(false, heard.audio.silent)
        assertEquals(false, audioBlock(File(corpus, heard.directoryName))["silent"])
        assertFalse("staged under its Session, then moved", File(staging, "s00001/${silent.directoryName}").exists())
    }

    @Test
    fun turnsACrashLeftStagedArePublishedOnRecovery() {
        val writer = FileCorpusWriter(corpus, staging)
        // A Turn mid-capture when the process died: header placeholder, samples, a torn last sample.
        val pcm = ShortArray(RATE) { i -> (if (i % 2 == 0) 400 else -400).toShort() }
        writer.begin("a0a0a0", "c0ffee", 1_791_296_527_431, RATE).append(pcm, pcm.size)
        val torn = File(staging, "c0ffee/${turnDirectoryName(1_791_296_527_431, "a0a0a0")}/$AUDIO_FILE")
        torn.appendBytes(byteArrayOf(7))
        // A Turn that never got a sample.
        writer.begin("b0b0b0", "c0ffee", 1_791_296_528_431, RATE)
        // A Turn staged by an older version, directly under staging/.
        val legacy = File(staging, turnDirectoryName(1_791_296_529_431, "c0c0c0")).apply { mkdirs() }
        File(legacy, AUDIO_FILE).writeBytes(ByteArray(Wav.HEADER_BYTES) + ByteArray(3200))
        // Not a Turn at all.
        File(staging, "c0ffee/notes").mkdirs()

        val results = FileCorpusWriter(corpus, staging).recover("9.9.9", RATE).associateBy { it.name }

        val recovered = results.getValue(turnDirectoryName(1_791_296_527_431, "a0a0a0"))
        assertEquals(FileCorpusWriter.Outcome.RECOVERED, recovered.outcome)
        val dir = File(corpus, recovered.name)
        val wav = File(dir, AUDIO_FILE).readBytes()
        assertEquals(Wav.HEADER_BYTES + pcm.size * 2, wav.size)
        assertTrue(Wav.header(RATE, pcm.size * 2).contentEquals(wav.copyOf(Wav.HEADER_BYTES)))
        val turn = json(dir)
        assertEquals(true, turn["recovered"])
        assertEquals("c0ffee", turn["session_id"])
        assertEquals("9.9.9", turn["app_version"])
        assertEquals(null, turn["transcript"])
        val audio = audioBlock(dir)
        assertEquals(1000L, (audio["duration_ms"] as Number).toLong())
        assertEquals(false, audio["silent"])
        val sha = MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) }
        assertEquals(sha, audio["sha256"])

        assertEquals(FileCorpusWriter.Outcome.DISCARDED, results.getValue(turnDirectoryName(1_791_296_528_431, "b0b0b0")).outcome)
        assertFalse(File(corpus, turnDirectoryName(1_791_296_528_431, "b0b0b0")).exists())

        val old = results.getValue(legacy.name)
        assertEquals(FileCorpusWriter.Outcome.RECOVERED, old.outcome)
        assertEquals(FileCorpusWriter.LEGACY_SESSION, json(File(corpus, legacy.name))["session_id"])
        assertEquals(true, audioBlock(File(corpus, legacy.name))["silent"])

        assertEquals(FileCorpusWriter.Outcome.LEFT, results.getValue("notes").outcome)
        assertTrue(File(staging, "c0ffee/notes").isDirectory)
        assertEquals(emptyList<String>(), FileCorpusWriter(corpus, staging).recover("9.9.9", RATE).filter { it.outcome != FileCorpusWriter.Outcome.LEFT }.map { it.name })
    }

    @Test
    fun aCompleteTurnThatWasNeverRenamedIsPublishedAsItIs() {
        val writer = FileCorpusWriter(corpus, staging)
        val name = turnDirectoryName(1_791_296_527_431, "d0d0d0")
        // Write a whole Turn, then move it back into staging as if the rename never happened.
        writer.begin("d0d0d0", "c0ffee", 1_791_296_527_431, RATE).apply { append(ShortArray(1600) { 50 }, 1600) }.finish("1.0")
        val before = File(corpus, name).let { File(it, TURN_FILE).readText() }
        assertTrue(File(corpus, name).renameTo(File(staging, "c0ffee/$name").apply { parentFile!!.mkdirs() }))

        val r = writer.recover("9.9.9", RATE).single()
        assertEquals(FileCorpusWriter.Outcome.PUBLISHED, r.outcome)
        assertEquals(before, File(File(corpus, name), TURN_FILE).readText())
        assertFalse(File(staging, "c0ffee").exists())
    }

    @Test
    fun aTurnWithATornTurnJsonIsSealedAgainRatherThanPublishedTorn() {
        val writer = FileCorpusWriter(corpus, staging)
        val name = turnDirectoryName(1_791_296_527_431, "e0e0e0")
        writer.begin("e0e0e0", "c0ffee", 1_791_296_527_431, RATE).apply { append(ShortArray(3200) { 50 }, 3200) }.finish("1.0")
        val staged = File(staging, "c0ffee/$name")
        assertTrue(File(corpus, name).renameTo(staged))
        File(staged, TURN_FILE).writeText("{\n  \"schema\": 1,\n  \"id\": \"e0") // the crash hit mid-write

        val r = writer.recover("9.9.9", RATE).single()
        assertEquals(FileCorpusWriter.Outcome.RECOVERED, r.outcome)
        val turn = json(File(corpus, name))
        assertEquals(true, turn["recovered"])
        assertEquals(200L, (audioBlock(File(corpus, name))["duration_ms"] as Number).toLong())
    }

    @Test
    fun theEchoReportsWhetherEachDoneEchoPlausiblyPlayed() {
        val played = Collections.synchronizedList(mutableListOf<Boolean>())
        val done = CountDownLatch(1)
        val speaker = EchoSpeaker(target, "heard1", played = { played += it }) { _, _ -> done.countDown() }
        speaker.start()
        try {
            speaker.speak(1, "silent failure test")
            assertTrue("no end reported within 20 s", done.await(20, TimeUnit.SECONDS))
        } finally {
            speaker.close()
        }
        assertEquals(1, played.size)
        assertEquals("played is true exactly when the echo was not unobserved", speaker.unobserved == 0, played.single())
    }
}
