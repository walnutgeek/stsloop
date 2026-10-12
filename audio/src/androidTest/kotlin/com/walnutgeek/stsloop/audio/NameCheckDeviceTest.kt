package com.walnutgeek.stsloop.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.walnutgeek.stsloop.audio.speech.ModelDownloads
import com.walnutgeek.stsloop.audio.speech.SttRecognizer
import com.walnutgeek.stsloop.audio.speech.TtsSynth
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.hub.HubProvider
import com.walnutgeek.stsloop.core.hub.Protocol
import com.walnutgeek.stsloop.core.recognisability.NameCheckResults
import com.walnutgeek.stsloop.core.recognisability.NameCheckStore
import com.walnutgeek.stsloop.core.recognisability.NameChecker
import com.walnutgeek.stsloop.core.recognisability.ProbeKind
import com.walnutgeek.stsloop.core.recognisability.Probes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The name-recognisability check (#49) on the phone: the system TTS voice
 * speaks every phrase of the default capture Buckets, the fixed commands and
 * a herdr hub's agent Buckets into the shipped recogniser, and the results
 * are filed and announced once, as a Session would. Writes only under the
 * test's own cache directory. The verdicts are logged
 * (`adb logcat -s NameCheckDeviceTest`); they are the model's, so the test
 * asserts only that every phrase got one.
 */
@RunWith(AndroidJUnit4::class)
class NameCheckDeviceTest {
    private companion object {
        const val TAG = "NameCheckDeviceTest"

        /** What a herdr hub sends (protocol/fixtures/v1/descriptors/herdr.json). */
        val HERDR = """
            {"type": "descriptors", "buckets": [
              {"id": "hub", "name": "hub", "aliases": ["the hub"], "type": "hub", "primitives": ["request"],
               "commands": [{"id": "blocked", "phrases": ["what's blocked", "what is blocked"], "primitive": "request", "anywhere": true}]},
              {"id": "herdr:w4:p1", "name": "woodglue agent", "aliases": ["woodglue"], "type": "agent", "primitives": ["request"],
               "commands": [{"id": "status", "phrases": ["status"], "primitive": "request"}, {"id": "read", "phrases": ["what does it say", "read it"], "primitive": "request"}]},
              {"id": "herdr:w8:p1", "name": "stsloop agent", "aliases": ["stsloop"], "type": "agent", "primitives": ["request", "draft"],
               "commands": [{"id": "status", "phrases": ["status"], "primitive": "request"}, {"id": "read", "phrases": ["what does it say", "read it"], "primitive": "request"},
                 {"id": "read_back", "phrases": ["read it back"], "primitive": "draft"}, {"id": "send", "phrases": ["send"], "primitive": "draft"},
                 {"id": "cancel", "phrases": ["cancel"], "primitive": "draft"}]}
            ]}
        """.trimIndent()
    }

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(target.cacheDir, "name-check-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun everyPhraseIsCheckedFiledAndAFailingBucketPhraseIsAnnouncedOnce() {
        val registry = HubProvider.registry(BucketConfig.DEFAULT, Protocol.decode(HERDR) as Descriptors)
        val probes = Probes.of(registry)
        val store = NameCheckStore(File(root, "files"))
        val synth = required(TtsSynth.open(target, File(root, "tts")))
        val stt = SttRecognizer.load(context.assets)
        val model = stt.model
        val variants = NameChecker.variants(synth.voices)
        Log.i(TAG, "model $model; variants ${variants.joinToString { it.id }}")
        val t0 = System.currentTimeMillis()
        try {
            val checker = NameChecker(stt, SAMPLE_RATE_HZ)
            for (p in store.read().pending(probes, model)) {
                val heard = checker.check(p, variants, synth) { false }
                assertNotNull("no variant of \"${p.spoken}\" could be spoken", heard)
                store.update { it.with(p.key, model, heard!!, System.currentTimeMillis()) }
            }
        } finally {
            synth.close()
            stt.close()
        }
        val results = store.read()
        Log.i(TAG, "checked ${probes.size} phrases x ${variants.size} variants in ${System.currentTimeMillis() - t0} ms")
        for (p in probes) {
            val v = results.verdict(p, probes, model)!!
            Log.i(TAG, "result: ${p.kind.name.lowercase()} ${p.owner} \"${p.spoken}\": ${v.level} ${v.passes}/${v.heard.size}" +
                (if (v.misheardAs.isEmpty()) "" else "; misheard as ${v.misheardAs.joinToString(" | ") { "'$it'" }}"))
        }
        assertEquals(emptyList<Any>(), results.pending(probes, model))
        assertEquals("each variant files a transcript", variants.size, results.verdict("errands", model)!!.heard.size)

        // A Session's start: the flagged Bucket phrases are said once, and never again.
        val flagged = probes.filter { it.kind == ProbeKind.BUCKET && results.verdict(it, probes, model)!!.flagged }.map { it.spoken }
        var first = emptyList<String>()
        store.update { r -> first = r.toAnnounce(probes, model); r.announced(first, model) }
        assertEquals(flagged, first)
        Log.i(TAG, "announced: ${NameCheckResults.announcement(first)}")
        var second = listOf("unset")
        store.update { r -> second = r.toAnnounce(probes, model); r.announced(second, model) }
        assertEquals(emptyList<String>(), second)
        assertNull(NameCheckResults.announcement(second))
        assertTrue(File(root, "files/${NameCheckResults.FILE}").isFile)
    }

    /**
     * The check as the app runs it ([NameCheck.request]), over the files a
     * Session reads: here the test APK's own `files/`, never the app's. Then
     * a Session's start announces the Bucket's name once, and the next none.
     */
    @Test
    fun aRequestChecksWhatTheNextSessionHearsAndItsNameIsAnnouncedOnce() {
        val files = target.filesDir
        val buckets = File(files, BucketConfig.FILE)
        val results = File(files, NameCheckResults.FILE)
        buckets.writeText("""{"buckets": [{"name": "stsloop", "aliases": ["the loop"]}]}""")
        results.delete()
        try {
            val done = CountDownLatch(1)
            NameCheck.request(target, "device test") { done.countDown() }
            assertTrue("the check did not end", done.await(10, TimeUnit.MINUTES))
            val model = required(NameCheck.modelId(target))
            val probes = NameCheck.probes(files)
            assertEquals(listOf("stsloop", "the loop"), probes.filter { it.kind == ProbeKind.BUCKET }.map { it.key })
            assertEquals("every phrase the Session hears was checked", emptyList<Any>(), NameCheck.results(target).pending(probes, model))
            for (p in probes) Log.i(TAG, "request: \"${p.spoken}\": ${NameCheck.results(target).verdict(p, probes, model)!!.level}")

            val models = required(ModelDownloads.locate(target))
            val registry = HubProvider.registry(TurnCapture.loadBuckets(files), null)
            val flagged = NameCheck.results(target).toAnnounce(probes, model)
            val first = NameCheck.announceOnce(files, models, registry)
            Log.i(TAG, "first Session says: $first")
            assertEquals(NameCheckResults.announcement(flagged), first)
            assertNull("said once, not every Session", NameCheck.announceOnce(files, models, registry))
        } finally {
            buckets.delete()
            results.delete()
        }
    }

    private fun <T> required(value: T?): T {
        assertNotNull("the phone has no TTS engine with an installed en-US voice, or no models", value)
        return value!!
    }
}
