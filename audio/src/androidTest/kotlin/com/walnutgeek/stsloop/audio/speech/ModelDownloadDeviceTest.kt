package com.walnutgeek.stsloop.audio.speech

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import com.walnutgeek.stsloop.core.models.HttpBody
import com.walnutgeek.stsloop.core.models.ModelDownload
import com.walnutgeek.stsloop.core.models.ModelFile
import com.walnutgeek.stsloop.core.models.ModelInstallException
import com.walnutgeek.stsloop.core.models.ModelManifest
import com.walnutgeek.stsloop.core.models.ModelStore
import com.walnutgeek.stsloop.core.models.RangeHttp
import com.walnutgeek.stsloop.core.models.SpeechManifest
import com.walnutgeek.stsloop.core.models.UrlConnectionHttp
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The first-run model download (#50) on the device: the unpack and the
 * hashing on ART, models loaded from files rather than assets, and a real
 * fetch from the upstream release host. Works in its own directories under
 * the test APK's cache, never the app's `no_backup/models`.
 *
 * The full download of the shipped models (465 MB, then 662 MB unpacked) is
 * opt-in: `-Pandroid.testInstrumentationRunnerArguments.fullDownload=true`.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloadDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val assets = context.assets
    private val dir = File(context.cacheDir, "model-download-test").apply { deleteRecursively(); mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun debugBuildsFindTheBundledModels() {
        val models = InstalledModels.locate(assets, ModelStore(File(dir, "empty"), SpeechManifest.MANIFEST))
        assertNotNull(models)
        assertTrue(models!!.bundled)
    }

    @Test
    fun withNeitherBundledNorDownloadedModelsNothingLoads() {
        assertNull(InstalledModels.locate(null, ModelStore(File(dir, "empty"), SpeechManifest.MANIFEST)))
    }

    @Test
    fun aMissingModelFileThrowsInsteadOfKillingTheProcess() {
        val config = SpeechModels.sttConfig(root = "${dir.absolutePath}/")
        assertThrows(IllegalStateException::class.java) { Stt(null, config) }
        assertThrows(IllegalStateException::class.java) { SpeechModels.newVad(null, SpeechModels.vadConfig("${dir.absolutePath}/")) }
    }

    /** The bz2 decoder and tar reader run on ART, resume after a dropped connection, and the VAD loads from the result. */
    @Test
    fun anInterruptedTarballResumesUnpacksAndItsVadLoadsFromFiles() {
        val vad = assets.open(SpeechManifest.VAD).use { it.readBytes() }
        val tarball = tarBz2("./bundle/${SpeechManifest.VAD}" to vad)
        val manifest = ModelManifest(
            listOf(
                ModelDownload.TarBz2(
                    "test://bundle.tar.bz2", "bundle.tar.bz2", tarball.size.toLong(), sha(tarball),
                    listOf(ModelFile("bundle/${SpeechManifest.VAD}", vad.size.toLong(), SpeechManifest.VAD_DOWNLOAD.sha256)),
                ),
            ),
        )
        val http = FlakyHttp(tarball, cutAfter = tarball.size / 3)
        val store = ModelStore(File(dir, "models"), manifest, http)
        val e = assertThrows(ModelInstallException::class.java) { store.install() }
        assertEquals(ModelInstallException.Reason.NETWORK, e.reason)
        assertFalse(store.isReady)

        store.install()
        assertTrue(store.isReady)
        assertEquals(listOf(0L, (tarball.size / 3).toLong()), http.froms)

        val root = File(store.root, "bundle").absolutePath + "/"
        val loaded = SpeechModels.newVad(null, SpeechModels.vadConfig(root))
        try {
            assertTrue("expected speech in the wav", speechSegments(loaded, SpeechModels.vadConfig(root).sileroVadModelConfig.windowSize) > 0)
        } finally {
            loaded.release()
        }
    }

    /** sherpa-onnx's file loader, not the asset loader: the zipformer copied out of the test APK. */
    @Test
    fun theRecognizerLoadsFromFiles() {
        val models = File(dir, "files").apply { mkdirs() }
        val sub = File(models, Zipformer.DIR).apply { mkdirs() }
        for (name in assets.list(Zipformer.DIR)!!) {
            assets.open("${Zipformer.DIR}/$name").use { input -> File(sub, name).outputStream().use { input.copyTo(it) } }
        }
        Stt(null, Zipformer.config(root = models.absolutePath + "/")).use { stt ->
            assertEquals("${Zipformer.DIR}/epoch-99-avg-1-chunk-16-left-128.int8 modified_beam_search", SttRecognizer(stt).model)
            val text = transcribe(stt)
            assertTrue(text, text.contains("YELLOW LAMPS"))
        }
    }

    /** The real host: TLS, GitHub's redirect to its asset host, and the pinned hash of the VAD. */
    @Test
    fun theVadDownloadsFromTheUpstreamRelease() {
        val store = ModelStore(File(dir, "upstream"), ModelManifest(listOf(SpeechManifest.VAD_DOWNLOAD)), UrlConnectionHttp())
        store.install()
        assertTrue(store.isReady)
        val vad = SpeechModels.newVad(null, SpeechModels.vadConfig(store.root.absolutePath + "/"))
        vad.release()
    }

    @Test
    fun fullDownloadOfTheShippedModels() {
        assumeTrue(
            "opt-in: -Pandroid.testInstrumentationRunnerArguments.fullDownload=true",
            InstrumentationRegistry.getArguments().getString("fullDownload") == "true",
        )
        val store = ModelStore(File(dir, "full"), SpeechManifest.MANIFEST)
        var last = 0L
        val t0 = SystemClock.elapsedRealtime()
        store.install(onProgress = { p ->
            val now = SystemClock.elapsedRealtime()
            if (now - last > 5_000) {
                last = now
                Log.i(TAG, "${(now - t0) / 1000} s: ${p.phase} ${p.done / 1_000_000} of ${p.total / 1_000_000} MB")
            }
        })
        Log.i(TAG, "full install: ${(SystemClock.elapsedRealtime() - t0) / 1000} s")
        assertTrue(store.isReady)
        val models = checkNotNull(InstalledModels.locate(null, store))
        assertFalse(models.bundled)
        models.loadStt().use { r ->
            assertEquals("${SpeechManifest.STT_DIR}/int8 greedy_search", r.model)
            val stream = r.open()
            val wave = WaveReader.readWave(assets, WAV)
            stream.accept(wave.samples)
            val text = stream.finish()
            stream.release()
            Log.i(TAG, "downloaded model heard: $text")
            assertTrue(text, text.contains("yellow lamps"))
        }
    }

    private fun transcribe(stt: Stt): String {
        val wave = WaveReader.readWave(assets, WAV)
        val stream = stt.createStream()
        try {
            stream.acceptWaveform(wave.samples, wave.sampleRate)
            stream.acceptWaveform(FloatArray(wave.sampleRate * 3 / 10), wave.sampleRate)
            stream.inputFinished()
            while (stt.isReady(stream)) stt.decode(stream)
            return stt.text(stream).trim()
        } finally {
            stream.release()
        }
    }

    private fun speechSegments(vad: com.k2fsa.sherpa.onnx.Vad, window: Int): Int {
        val wave = WaveReader.readWave(assets, WAV)
        var segments = 0
        var i = 0
        while (i + window <= wave.samples.size) {
            vad.acceptWaveform(wave.samples.copyOfRange(i, i + window))
            while (!vad.empty()) { segments++; vad.pop() }
            i += window
        }
        vad.flush()
        while (!vad.empty()) { segments++; vad.pop() }
        return segments
    }

    /** Serves [body], honouring the offset; the first response drops after [cutAfter] bytes. */
    private class FlakyHttp(private val body: ByteArray, private var cutAfter: Int?) : RangeHttp {
        val froms = mutableListOf<Long>()

        override fun get(url: String, from: Long): HttpBody {
            froms += from
            val rest: InputStream = ByteArrayInputStream(body, from.toInt(), body.size - from.toInt())
            val cut = cutAfter
            cutAfter = null
            val stream = if (cut == null) rest else object : FilterInputStream(rest) {
                var sent = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (sent >= cut) throw IOException("connection reset")
                    val n = super.read(b, off, minOf(len, cut - sent))
                    if (n > 0) sent += n
                    return n
                }
            }
            return HttpBody(if (from > 0) 206 else 200, if (from > 0) from else null, stream)
        }
    }

    private fun tarBz2(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            for ((name, body) in entries) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = body.size.toLong() })
                tar.write(body)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha(b: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "ModelDownloadDeviceTest"
        const val WAV = "test_wavs/0.wav"
    }
}
