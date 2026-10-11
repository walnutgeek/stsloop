package com.walnutgeek.stsloop.audio.speech

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.WaveReader
import com.walnutgeek.stsloop.audio.SAMPLE_RATE_HZ
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-device STT A/B behind #30: real-time factor, end-of-Turn latency,
 * load time and memory for each model, on the same wavs, through the app's
 * own STT path ([SttRecognizer]: one stream per Turn, fed in the service's
 * 100 ms chunks, then the final flush).
 *
 * Chunks are paced like the mic, so a model slower than real time falls
 * behind exactly as it would in a Session. Latency is what
 * `transcript.latency_ms` records: from the moment the Turn's last sample is
 * captured to the final text. The wavs are Corpus Recordings, which already
 * end in the trailing Silence that closed them.
 *
 * Off by default (it takes a few minutes per model). Run with
 * ```
 * ./gradlew :audio:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.walnutgeek.stsloop.audio.speech.SttBenchmarkDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.bench=nemotron,zipformer
 * ```
 * Optional arguments: `threads` (default 2, as the app) and `tailMs`
 * (default [SttRecognizer.TAIL_PADDING_MS]). Each model first runs every wav
 * unpaced (the "batch" RTF, which also warms it up), then paced. Wavs: `test_wavs/` plus any `bench_wavs/` in the test
 * assets (git-ignored; copy Corpus `audio.wav`s there). Results are logged
 * under the tag "SttBenchmark".
 */
@RunWith(AndroidJUnit4::class)
class SttBenchmarkDeviceTest {
    private companion object {
        const val TAG = "SttBenchmark"
        const val CHUNK = SAMPLE_RATE_HZ / 10
    }

    private val args = InstrumentationRegistry.getArguments()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private class Result(val name: String, val audioMs: Long, val computeMs: Double, val latencyMs: Double, val finishMs: Double, val text: String)

    @Test
    fun benchmark() {
        val models = args.getString("bench").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        assumeTrue("pass -e bench <models> to run", models.isNotEmpty())
        val threads = args.getString("threads")?.toInt() ?: 2
        val tailMs = args.getString("tailMs")?.toInt()
        val wavs = listOf("test_wavs/0.wav", "test_wavs/1.wav") +
            (assets.list("bench_wavs") ?: emptyArray()).sorted().map { "bench_wavs/$it" }
        for (m in models) {
            val config = when (m) {
                "nemotron" -> SpeechModels.sttConfig(threads)
                "zipformer" -> Zipformer.config(threads)
                else -> error("unknown model '$m'")
            }
            run(m, config, threads, tailMs ?: SttRecognizer.TAIL_PADDING_MS, wavs)
        }
    }

    private fun run(name: String, config: OnlineRecognizerConfig, threads: Int, tailMs: Int, wavs: List<String>) {
        System.gc()
        val heap0 = Debug.getNativeHeapAllocatedSize()
        val t0 = SystemClock.elapsedRealtime()
        val recognizer = SttRecognizer(Stt(assets, config), tailMs)
        val loadMs = SystemClock.elapsedRealtime() - t0
        val heapMb = (Debug.getNativeHeapAllocatedSize() - heap0) / (1 shl 20)
        val pssMb = Debug.getPss() / 1024
        Log.i(TAG, "[$name] ${recognizer.model}: load $loadMs ms, native heap +$heapMb MB, process PSS $pssMb MB, threads $threads, tail $tailMs ms")
        val (batch, results) = recognizer.use { r ->
            wavs.map { transcribe(r, it, paced = false) } to wavs.map { transcribe(r, it, paced = true) }
        }
        for (x in results) {
            Log.i(TAG, "[$name] ${x.name}: ${x.audioMs} ms audio, RTF ${f3(x.computeMs / x.audioMs)}, latency ${x.latencyMs.toLong()} ms (finish ${x.finishMs.toLong()} ms): ${x.text}")
        }
        val audio = results.sumOf { it.audioMs }
        val compute = results.sumOf { it.computeMs }
        val lat = results.map { it.latencyMs }.sorted()
        val fin = results.map { it.finishMs }.sorted()
        Log.i(
            TAG,
            "[$name] SUMMARY ${results.size} wavs, ${audio / 1000} s audio: RTF ${f3(compute / audio)} paced, " +
                "${f3(batch.sumOf { it.computeMs } / audio)} batch, " +
                "latency median ${lat[lat.size / 2].toLong()} ms p90 ${lat[lat.size * 9 / 10].toLong()} ms max ${lat.last().toLong()} ms, " +
                "finish median ${fin[fin.size / 2].toLong()} ms, load $loadMs ms, native heap +$heapMb MB, PSS $pssMb MB",
        )
    }

    private fun transcribe(r: SttRecognizer, path: String, paced: Boolean): Result {
        val wave = WaveReader.readWave(assets, path)
        check(wave.sampleRate == SAMPLE_RATE_HZ) { "$path: ${wave.sampleRate} Hz" }
        val samples = wave.samples
        val stream = r.open()
        var computeNs = 0L
        val start = System.nanoTime()
        try {
            var i = 0
            while (i < samples.size) {
                val n = minOf(CHUNK, samples.size - i)
                // The mic hands over a chunk once its last sample is captured.
                val due = start + (i + n) * 1_000_000_000L / SAMPLE_RATE_HZ
                while (paced && System.nanoTime() < due) Thread.sleep(1)
                val a = System.nanoTime()
                stream.accept(samples.copyOfRange(i, i + n))
                computeNs += System.nanoTime() - a
                i += n
            }
            val lastSampleNs = start + samples.size * 1_000_000_000L / SAMPLE_RATE_HZ
            val f = System.nanoTime()
            val text = stream.finish().trim()
            val end = System.nanoTime()
            computeNs += end - f
            return Result(
                path.substringAfterLast('/'), samples.size * 1000L / SAMPLE_RATE_HZ,
                computeNs / 1e6, (end - lastSampleNs) / 1e6, (end - f) / 1e6, text,
            )
        } finally {
            stream.release()
        }
    }

    private fun f3(x: Double) = "%.3f".format(java.util.Locale.ROOT, x)
}
