package com.walnutgeek.stsloop.audio.speech

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.VersionInfo
import com.k2fsa.sherpa.onnx.WaveReader
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads the shipped model on the device and transcribes an upstream test wav.
 * Needs `scripts/build-sherpa-onnx.sh` and `scripts/fetch-models.sh` first.
 * Timings are logged under the tag "SttDeviceTest"; [SttBenchmarkDeviceTest]
 * compares the shipped model with the previous one.
 */
@RunWith(AndroidJUnit4::class)
class SttDeviceTest {
    companion object {
        private const val TAG = "SttDeviceTest"
        private const val WAV = "test_wavs/0.wav"

        /** The shipped model's text: mixed case, with punctuation. */
        private const val EXPECTED =
            "After early nightfall, the yellow lamps would light up here and there the squalid quarter of the brothels"

        /** The test-only zipformer's text, as `test_wavs/trans.txt` has it. */
        private const val ZIPFORMER_EXPECTED =
            "AFTER EARLY NIGHTFALL THE YELLOW LAMPS WOULD LIGHT UP HERE AND THERE THE SQUALID QUARTER OF THE BROTHELS"

        private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets
        private var stt: Stt? = null

        @BeforeClass
        @JvmStatic
        fun load() {
            Log.i(TAG, "sherpa-onnx ${VersionInfo.version} ${VersionInfo.gitSha1}, onnxruntime ${VersionInfo.onnxruntimeVersion}")
            val t0 = SystemClock.elapsedRealtime()
            stt = Stt(assets)
            val heapMb = Debug.getNativeHeapAllocatedSize() / (1 shl 20)
            Log.i(TAG, "model load: ${SystemClock.elapsedRealtime() - t0} ms, native heap $heapMb MB")
        }

        @AfterClass
        @JvmStatic
        fun release() {
            stt?.close()
        }
    }

    private val loaded get() = checkNotNull(stt) { "model failed to load" }

    private fun transcribe(stt: Stt = loaded, hotwords: String = ""): String {
        val wave = WaveReader.readWave(assets, WAV)
        val t0 = SystemClock.elapsedRealtime()
        val stream = stt.createStream(hotwords)
        val text = try {
            stream.acceptWaveform(wave.samples, wave.sampleRate)
            stream.acceptWaveform(FloatArray(wave.sampleRate * 3 / 10), wave.sampleRate) // tail padding
            stream.inputFinished()
            while (stt.isReady(stream)) stt.decode(stream)
            stt.text(stream).trim()
        } finally {
            stream.release()
        }
        val ms = SystemClock.elapsedRealtime() - t0
        val audioMs = wave.samples.size * 1000L / wave.sampleRate
        Log.i(TAG, "decode hotwords='$hotwords': $ms ms for $audioMs ms audio, RTF ${"%.3f".format(ms.toDouble() / audioMs)}: $text")
        return text
    }

    @Test
    fun shipsOnlyTheInt8Nemotron() {
        val files = assets.list(SpeechModels.STT_DIR)!!.sorted()
        assertEquals(listOf("decoder.int8.onnx", "encoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), files)
        val spec = loaded.spec
        assertEquals("greedy_search", spec.decodingMethod)
        assertEquals("", spec.modelType)
        assertEquals("", spec.hotwordsFile)
        assertEquals("${SpeechModels.STT_DIR}/int8 greedy_search", SttRecognizer(loaded).model)
    }

    @Test
    fun transcribesTheBundledWav() {
        assertEquals(EXPECTED, transcribe())
    }

    @Test
    fun chokepointRefusesHotwordsOnTheShippedModelInsteadOfCrashing() {
        // sherpa-onnx v1.13.8's NeMo transducer has no hotwords support: letting
        // them through would _Exit and take the test run with it.
        val e = assertThrows(IllegalStateException::class.java) { loaded.createStream("YELLOW LAMPS") }
        assertTrue(e.message!!, e.message!!.contains("modelType"))
        loaded.createStream("").release() // no hotwords is always fine
    }

    /** The test-only zipformer is the one hotwords-capable model: the gate's Pass path, end to end. */
    @Test
    fun hotwordsReachAZipformerTransducerUnderModifiedBeamSearch() {
        Stt(assets, Zipformer.config()).use { zipformer ->
            assertEquals(ZIPFORMER_EXPECTED, transcribe(zipformer, "YELLOW LAMPS/SQUALID QUARTER"))
        }
        // The same model under greedy_search builds no BPE encoder, so the gate refuses.
        Stt(assets, Zipformer.config().apply { decodingMethod = "greedy_search" }).use { greedy ->
            val e = assertThrows(IllegalStateException::class.java) { greedy.createStream("HELLO") }
            assertTrue(e.message!!, e.message!!.contains("modified_beam_search"))
        }
    }

    @Test
    fun configTimeHotwordsAreRefused() {
        val config = SpeechModels.sttConfig().apply { hotwordsFile = "hotwords.txt" }
        assertThrows(IllegalArgumentException::class.java) { Stt(assets, config) }
    }

    @Test
    fun missingAssetThrowsInsteadOfExiting() {
        val config = SpeechModels.sttConfig().apply { modelConfig.tokens = "nope/tokens.txt" }
        assertThrows(IllegalStateException::class.java) { Stt(assets, config) }
    }

    @Test
    fun sileroVadFindsSpeechInTheWav() {
        val wave = WaveReader.readWave(assets, WAV)
        val vad = SpeechModels.newVad(assets)
        val window = SpeechModels.vadConfig().sileroVadModelConfig.windowSize
        var segments = 0
        try {
            var i = 0
            while (i + window <= wave.samples.size) {
                vad.acceptWaveform(wave.samples.copyOfRange(i, i + window))
                while (!vad.empty()) { segments++; vad.pop() }
                i += window
            }
            vad.flush()
            while (!vad.empty()) { segments++; vad.pop() }
        } finally {
            vad.release()
        }
        Log.i(TAG, "vad segments: $segments")
        assertTrue("expected at least one speech segment", segments > 0)
    }
}
