package com.walnutgeek.stsloop.audio.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.k2fsa.sherpa.onnx.WaveReader
import com.walnutgeek.stsloop.audio.SAMPLE_RATE_HZ
import com.walnutgeek.stsloop.core.recognisability.PhraseSynth
import com.walnutgeek.stsloop.core.recognisability.Variant
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The system TTS engine as the name-recognisability check's voice: each
 * phrase is synthesised to a file, never played, so the check is silent,
 * and read back at the recogniser's rate. Only en-US voices installed on the
 * phone are used ([voices], the default first), so the check needs no
 * network. Call from one background thread; [open] blocks while the engine starts.
 */
class TtsSynth private constructor(
    private val tts: TextToSpeech,
    private val dir: File,
    /** The voices the check speaks in, by name, the engine's default first. */
    val voices: List<String>,
) : PhraseSynth, AutoCloseable {
    private val done = ConcurrentHashMap<String, Pair<CountDownLatch, BooleanArray>>()
    private var next = 0

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId, true)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId, false)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)
        })
    }

    private fun finish(id: String?, ok: Boolean) {
        val (latch, result) = done[id] ?: return
        result[0] = ok
        latch.countDown()
    }

    override fun synthesize(phrase: String, variant: Variant): FloatArray? {
        val voice = tts.voices?.firstOrNull { it.name == variant.voice } ?: return null
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) return null
        tts.setSpeechRate(variant.rate)
        val id = "probe-${next++}"
        val file = File(dir, "$id.wav")
        val latch = CountDownLatch(1)
        val ok = BooleanArray(1)
        done[id] = latch to ok
        try {
            if (tts.synthesizeToFile(phrase, null, file, id) != TextToSpeech.SUCCESS) return null
            if (!latch.await(SYNTH_TIMEOUT_S, TimeUnit.SECONDS) || !ok[0]) {
                Log.w(TAG, "could not synthesise \"$phrase\" in ${variant.id}")
                return null
            }
            val wave = WaveReader.readWave(file.absolutePath)
            return resample(wave.samples, wave.sampleRate, SAMPLE_RATE_HZ)
        } finally {
            done.remove(id)
            file.delete()
        }
    }

    override fun close() {
        tts.shutdown()
        dir.deleteRecursively()
    }

    companion object {
        private const val TAG = "stsloop.NameCheck"
        private const val INIT_TIMEOUT_S = 20L
        private const val SYNTH_TIMEOUT_S = 20L

        /** Starts the engine; null when there is none, or it has no installed en-US voice. Blocks up to 20 s. */
        fun open(context: Context, dir: File): TtsSynth? {
            val ready = CountDownLatch(1)
            var status = TextToSpeech.ERROR
            val tts = TextToSpeech(context) { s -> status = s; ready.countDown() }
            if (!ready.await(INIT_TIMEOUT_S, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "no TTS engine to speak names with (status $status)")
                tts.shutdown()
                return null
            }
            tts.language = Locale.US
            val installed = tts.voices.orEmpty().filter(::usable).sortedBy { it.name }
            val default = tts.voice?.takeIf(::usable)
            val voices = (listOfNotNull(default) + installed).map { it.name }.distinct()
            if (voices.isEmpty()) {
                Log.w(TAG, "the TTS engine has no installed en-US voice")
                tts.shutdown()
                return null
            }
            dir.deleteRecursively()
            if (!dir.mkdirs()) Log.w(TAG, "cannot create $dir")
            return TtsSynth(tts, dir, voices)
        }

        private fun usable(v: Voice) =
            v.locale.language == "en" && v.locale.country == "US" && !v.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()

        /** Linear resampling: enough for a recogniser to hear TTS audio. */
        fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
            if (from == to || x.isEmpty()) return x
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
}
