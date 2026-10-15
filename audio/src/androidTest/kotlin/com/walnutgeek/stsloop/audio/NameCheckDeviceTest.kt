package com.walnutgeek.stsloop.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.walnutgeek.stsloop.audio.speech.ModelDownloads
import com.walnutgeek.stsloop.audio.speech.SttRecognizer
import com.walnutgeek.stsloop.audio.speech.TtsSynth
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.PhraseGrammar
import com.walnutgeek.stsloop.core.hub.HubAliases
import com.walnutgeek.stsloop.core.recognisability.AliasLearner
import com.walnutgeek.stsloop.core.settings.BucketSettings
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.hub.HubProvider
import com.walnutgeek.stsloop.core.hub.Protocol
import com.walnutgeek.stsloop.core.recognisability.NameCheckResults
import com.walnutgeek.stsloop.core.recognisability.NameCheckStore
import com.walnutgeek.stsloop.core.recognisability.NameChecker
import com.walnutgeek.stsloop.core.recognisability.ProbeKind
import com.walnutgeek.stsloop.core.recognisability.Probes
import com.walnutgeek.stsloop.core.recognisability.Verdict
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
              {"id": "hub", "name": "the hub", "aliases": ["hub"], "type": "hub", "primitives": ["request"],
               "commands": [{"id": "blocked", "phrases": ["what's blocked", "what is blocked"], "primitive": "request", "anywhere": true}]},
              {"id": "herdr:w4:p1", "name": "woodglue agent", "aliases": ["woodglue"], "type": "agent", "primitives": ["request", "speak_item"],
               "commands": [{"id": "status", "phrases": ["status"], "primitive": "request"}, {"id": "read", "phrases": ["what does it say", "read it"], "primitive": "request"},
                 {"id": "quiet", "phrases": ["quiet", "mute alerts"], "primitive": "request"}, {"id": "unmute", "phrases": ["unmute", "unmute alerts"], "primitive": "request"}]},
              {"id": "herdr:w8:p1", "name": "stsloop agent", "aliases": ["stsloop"], "type": "agent", "primitives": ["request", "draft", "speak_item"],
               "commands": [{"id": "status", "phrases": ["status"], "primitive": "request"}, {"id": "read", "phrases": ["what does it say", "read it"], "primitive": "request"},
                 {"id": "read_back", "phrases": ["read it back"], "primitive": "draft"}, {"id": "send", "phrases": ["send it", "send"], "primitive": "draft"},
                 {"id": "cancel", "phrases": ["cancel"], "primitive": "draft"},
                 {"id": "option_1", "phrases": ["option one"], "primitive": "draft"}, {"id": "option_2", "phrases": ["option two"], "primitive": "draft"},
                 {"id": "option_3", "phrases": ["option three"], "primitive": "draft"}, {"id": "option_4", "phrases": ["option four"], "primitive": "draft"},
                 {"id": "my_answer", "phrases": ["my answer", "answer with my draft"], "primitive": "draft"},
                 {"id": "quiet", "phrases": ["quiet", "mute alerts"], "primitive": "request"}, {"id": "unmute", "phrases": ["unmute", "unmute alerts"], "primitive": "request"}]}
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

        // docs/mvp.md, "Commands" (#82): every primary command phrase, and the hub Bucket's name, is heard as
        // itself in every variant. Agent names are not the loop's to choose (#81), nor the person's capture names.
        val primaries = Command.entries.mapNotNull { it.phrases.firstOrNull() } + registry.inbox.name +
            registry.buckets.filter { it.hub }.flatMap { b -> b.commands.map { it.phrases.first() } } +
            registry.buckets.filter { it.id == "hub" }.map { it.name }
        val weak = primaries.distinct().mapNotNull { phrase ->
            val heard = results.verdict(Probes.key(phrase), model)!!.heard
            val passes = heard.count { Verdict.isPhrase(phrase, it.text) }
            Log.i(TAG, "primary \"$phrase\": $passes/${heard.size}")
            if (passes == heard.size) null else "$phrase $passes/${heard.size}"
        }
        assertEquals("primary phrases heard less than every time", emptyList<String>(), weak)

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
            // "stsloop" is heard as other words (#49), which it learns (#81) and then checks too.
            val config = TurnCapture.loadBuckets(files).buckets.single()
            Log.i(TAG, "buckets.json: ${buckets.readText()}")
            assertEquals(listOf("the loop"), config.aliases)
            assertTrue("learned ${config.learned}", config.learned.isNotEmpty())
            assertEquals(listOf("stsloop", "the loop") + config.learned.map(Probes::key), probes.filter { it.kind == ProbeKind.BUCKET }.map { it.key })
            assertEquals("every phrase the Session hears was checked", emptyList<Any>(), NameCheck.results(target).pending(probes, model))
            for (p in probes) Log.i(TAG, "request: \"${p.spoken}\": ${NameCheck.results(target).verdict(p, probes, model)!!.level}")

            val models = required(ModelDownloads.locate(target))
            val registry = HubProvider.registry(TurnCapture.loadBuckets(files), null)
            val first = NameCheck.announceOnce(files, models, registry)
            Log.i(TAG, "first Session says: $first")
            assertEquals(NameCheckResults.learnedAnnouncement(mapOf("stsloop" to config.learned)), first)
            assertNull("said once, not every Session", NameCheck.announceOnce(files, models, registry))
        } finally {
            buckets.delete()
            results.delete()
        }
    }

    /**
     * Learned aliases (#81) with the shipped recogniser: the herdr agents'
     * names are checked, "stsloop agent" learns what the phone really hears
     * for it, and that is then focused when the voice says "stsloop agent".
     * The announcement is said once. Writes only under the test's cache.
     */
    @Test
    fun stsloopAgentLearnsWhatThePhoneHearsAndIsFocusedByIt() {
        val settings = BucketSettings(null, null, Protocol.decode(HERDR) as Descriptors)
        val agents = setOf("stsloop agent", "woodglue agent")
        val probes = settings.probes.filter { it.kind == ProbeKind.BUCKET && it.owner in agents }
        val store = NameCheckStore(File(root, "files"))
        val synth = required(TtsSynth.open(target, File(root, "tts")))
        val stt = SttRecognizer.load(context.assets)
        val model = stt.model
        try {
            val variants = NameChecker.variants(synth.voices)
            val checker = NameChecker(stt, SAMPLE_RATE_HZ)
            for (p in probes) store.update { it.with(p.key, model, checker.check(p, variants, synth) { false }!!, System.currentTimeMillis()) }

            val learning = settings.learn(store.read(), model)
            for (l in learning.learned) Log.i(TAG, "learned: \"${l.phrase}\" -> \"${l.alias}\"")
            for (r in learning.refused) Log.i(TAG, "refused: ${r.message}")
            assertTrue("stsloop agent learned nothing: ${learning.learned}", learning.learned.any { it.phrase == "stsloop agent" })
            store.update { it.learned(learning.learned) }
            val learned = learning.saves.fold(settings) { s, save -> s.with(save) }
            Log.i(TAG, "hub/aliases.json: ${learning.saves.single { it.file == HubAliases.FILE }.text}")

            // Said aloud as written, "stsloop agent" now focuses its Bucket.
            val grammar = PhraseGrammar(learned.registry)
            val focused = variants.count { v ->
                val text = checker.hear(synth.synthesize("stsloop agent", v)!!)
                val focus = grammar.classify(text).command?.focus
                Log.i(TAG, "said \"stsloop agent\" (${v.id}): heard \"$text\", focus $focus")
                focus == "stsloop agent"
            }
            assertTrue("focused $focused of ${variants.size}", focused * AliasLearner.OUT_OF >= variants.size * AliasLearner.AGREE)

            val (said, first) = store.read().announce(learned.registry, model)
            Log.i(TAG, "first Session says: $first")
            assertTrue(first, first!!.contains("stsloop agent also answers to "))
            assertNull("said once", said.announce(learned.registry, model).second)
        } finally {
            synth.close()
            stt.close()
        }
    }

    private fun <T> required(value: T?): T {
        assertNotNull("the phone has no TTS engine with an installed en-US voice, or no models", value)
        return value!!
    }
}
