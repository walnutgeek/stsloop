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
 * Timings are logged under the tag "SttDeviceTest".
 */
@RunWith(AndroidJUnit4::class)
class SttDeviceTest {
    companion object {
        private const val TAG = "SttDeviceTest"
        private const val WAV = "test_wavs/0.wav"
        private const val EXPECTED =
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

    private fun transcribe(hotwords: String = ""): String {
        val stt = loaded
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
    fun shipsOnlyTheInt8Chunk16Left128Subset() {
        val files = assets.list(SpeechModels.STT_DIR)!!.sorted()
        assertEquals(
            listOf(
                "bpe.vocab",
                "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "tokens.txt",
            ),
            files,
        )
        val spec = loaded.spec
        assertEquals("modified_beam_search", spec.decodingMethod)
        assertEquals("bpe", spec.modelingUnit)
        assertEquals("${SpeechModels.STT_DIR}/bpe.vocab", spec.bpeVocab)
        assertEquals("", spec.hotwordsFile)
    }

    @Test
    fun transcribesTheBundledWav() {
        assertEquals(EXPECTED, transcribe())
    }

    @Test
    fun hotwordsReachATransducerUnderModifiedBeamSearch() {
        assertEquals(EXPECTED, transcribe("YELLOW LAMPS/SQUALID QUARTER"))
    }

    @Test
    fun chokepointRefusesHotwordsInsteadOfCrashing() {
        // Same model under greedy_search: no BPE encoder is built, so letting
        // hotwords through would crash natively and take the test run with it.
        val config = SpeechModels.sttConfig().apply { decodingMethod = "greedy_search" }
        Stt(assets, config).use { greedy ->
            val e = assertThrows(IllegalStateException::class.java) { greedy.createStream("HELLO") }
            assertTrue(e.message!!, e.message!!.contains("modified_beam_search"))
            greedy.createStream("").release() // no hotwords is always fine
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
