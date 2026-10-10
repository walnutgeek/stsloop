package com.walnutgeek.stsloop.audio.speech

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.VersionInfo
import com.k2fsa.sherpa.onnx.WaveReader
import com.walnutgeek.stsloop.core.speech.ModelArchitecture
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
        private lateinit var stt: Stt

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
        fun release() = stt.close()
    }

    private fun transcribe(hotwords: String = ""): String {
        val wave = WaveReader.readWave(assets, WAV)
        val t0 = SystemClock.elapsedRealtime()
        val stream = stt.createStream(hotwords)
        stream.acceptWaveform(wave.samples, wave.sampleRate)
        stream.acceptWaveform(FloatArray(wave.sampleRate * 3 / 10), wave.sampleRate) // tail padding
        stream.inputFinished()
        while (stt.isReady(stream)) stt.decode(stream)
        val text = stt.text(stream).trim()
        stream.release()
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
        assertEquals("modified_beam_search", stt.config.decodingMethod)
        assertEquals("bpe", stt.config.modelConfig.modelingUnit)
        assertEquals("${SpeechModels.STT_DIR}/bpe.vocab", stt.config.modelConfig.bpeVocab)
        assertEquals("", stt.config.hotwordsFile)
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
    fun chokepointRefusesHotwordsInsteadOfExiting() {
        // Same model, declared as CTC: the gate must stop the call before JNI,
        // or this process would die with _Exit(-1) and the test run with it.
        Stt(assets, architecture = ModelArchitecture.CTC).use { ctc ->
            val e = assertThrows(IllegalStateException::class.java) { ctc.createStream("HELLO") }
            assertTrue(e.message!!, e.message!!.contains("CTC"))
            ctc.createStream("").release() // no hotwords is always fine
        }
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
        var i = 0
        while (i + window <= wave.samples.size) {
            vad.acceptWaveform(wave.samples.copyOfRange(i, i + window))
            while (!vad.empty()) { segments++; vad.pop() }
            i += window
        }
        vad.flush()
        while (!vad.empty()) { segments++; vad.pop() }
        vad.release()
        Log.i(TAG, "vad segments: $segments")
        assertTrue("expected at least one speech segment", segments > 0)
    }
}
