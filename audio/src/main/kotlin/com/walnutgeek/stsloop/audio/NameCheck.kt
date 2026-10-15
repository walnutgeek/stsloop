package com.walnutgeek.stsloop.audio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.walnutgeek.stsloop.audio.speech.InstalledModels
import com.walnutgeek.stsloop.audio.speech.ModelDownloads
import com.walnutgeek.stsloop.audio.speech.TtsSynth
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.BucketRegistry
import com.walnutgeek.stsloop.core.hub.HubAliases
import com.walnutgeek.stsloop.core.hub.HubProvider
import com.walnutgeek.stsloop.core.hub.HubStore
import com.walnutgeek.stsloop.core.recognisability.NameCheckResults
import com.walnutgeek.stsloop.core.recognisability.NameCheckStore
import com.walnutgeek.stsloop.core.recognisability.NameChecker
import com.walnutgeek.stsloop.core.recognisability.Probe
import com.walnutgeek.stsloop.core.recognisability.Probes
import com.walnutgeek.stsloop.core.settings.BucketSettings
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The name-recognisability check (#49; `docs/mvp.md`): every Bucket name,
 * alias and command phrase the next Session would listen for is spoken by the
 * system TTS voice ([TtsSynth]) into the Session's own recogniser, in several
 * voices and speeds ([NameChecker]), and what it heard is filed in
 * `files/recognisability.json` ([NameCheckStore]) by phrase and model. Only
 * phrases with no result for the installed model are checked, so a [request]
 * with nothing new returns without loading anything.
 *
 * It stays off the loop's way: it does not start while a Session is active,
 * and once one starts it stops before its next variant, filing only the
 * phrases it finished; the Session's end requests it again. The recogniser
 * takes ~750 MB and a second one would also take CPU from the loop's own
 * decoding, so the check waits rather than share. A Session starting mid-run
 * overlaps it by at most one variant (about a second) or, if the check is
 * still loading its recogniser, by that load. It is requested whenever the phrases
 * may have changed: at pairing, when a Session ends (the hub may have sent
 * new Descriptors), after an alias edit, and when the app or the settings
 * screen opens.
 *
 * After each run it learns aliases (#81): a Bucket phrase heard consistently
 * as other words gains them ([BucketSettings.learn]), written to the Bucket
 * files under [SettingsFiles.LOCK]; the new aliases are then checked too.
 */
object NameCheck {
    private const val TAG = "stsloop.NameCheck"

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "name-check").apply { isDaemon = true } }
    private val queued = AtomicBoolean()

    /** Callers of [request] waiting for the next run to end. */
    private val waiting = mutableListOf<() -> Unit>()

    /** When the TTS engine last could not be started; it is not tried again for [NO_TTS_RETRY_MS]. */
    @Volatile
    private var noTtsAtMs: Long? = null
    private const val NO_TTS_RETRY_MS = 10 * 60_000L

    /** At most this many rounds of checking, then learning, per run. */
    private const val MAX_ROUNDS = 3

    /** Called on the check's thread after each phrase is filed, and when a run ends. */
    @Volatile
    var listener: (() -> Unit)? = null

    /** While a run is under way: how many phrases it has done, and of how many. */
    @Volatile
    var progress: Pair<Int, Int>? = null
        private set

    /**
     * Any thread: check whatever is not yet checked, now or once no Session is
     * active. [why] is for the log. [then] is called on the check's thread
     * when the run that follows this request ends, however it ends.
     */
    fun request(context: Context, why: String, then: (() -> Unit)? = null) {
        val app = context.applicationContext
        if (then != null) synchronized(waiting) { waiting += then }
        if (!queued.compareAndSet(false, true)) return // one run queued is enough: it reads the files when it starts
        executor.execute {
            queued.set(false)
            val done = synchronized(waiting) { waiting.toList().also { waiting.clear() } }
            try {
                run(app, why)
            } catch (e: Throwable) {
                Log.e(TAG, "the check failed", e)
            } finally {
                progress = null
                listener?.invoke()
                for (d in done) runCatching(d).onFailure { Log.e(TAG, "a caller's follow-up failed", it) }
            }
        }
    }

    /** What has been heard so far. */
    fun results(context: Context): NameCheckResults = NameCheckStore(context.filesDir).read()

    /** The model results are filed under, or null when no speech models are installed. */
    fun modelId(context: Context): String? = ModelDownloads.locate(context)?.modelId

    /**
     * Session start: what the loop says once about [registry] on [models]
     * ([NameCheckResults.announce]): the aliases it learned, then the Bucket
     * phrases it still cannot reliably hear, each marked announced; null
     * when there is nothing new. They are marked before they are said: a
     * Session stopped before its first Silence never says them, which is
     * better than saying them every Session.
     */
    fun announceOnce(files: File, models: InstalledModels, registry: BucketRegistry): String? {
        var say: String? = null
        NameCheckStore(files).update { r ->
            r.announce(registry, models.modelId).let { (after, text) ->
                say = text
                after
            }
        }
        return say
    }

    /**
     * Background thread: the Bucket files' settings, as the next Session
     * would hear them. Call under [SettingsFiles.LOCK] to change them. Throws
     * when a file exists but cannot be read.
     */
    fun bucketSettings(files: File): BucketSettings {
        val snapshot = try {
            HubStore(files).descriptors()
        } catch (e: Exception) {
            Log.e(TAG, "cannot read the cached hub Descriptors", e)
            null
        }
        return BucketSettings(SettingsFiles.read(files, BucketConfig.FILE), SettingsFiles.read(files, HubAliases.FILE), snapshot)
    }

    /**
     * Learns what [model] consistently heard a Bucket phrase as (#81),
     * writing the Bucket files and recording each alias in the results; logs
     * what was refused. Returns how many aliases were learned.
     */
    fun learn(files: File, model: String): Int = synchronized(SettingsFiles.LOCK) {
        val store = NameCheckStore(files)
        val learning = try {
            bucketSettings(files).learn(store.read(), model)
        } catch (e: java.io.IOException) {
            Log.e(TAG, "not learning aliases: cannot read the Bucket files", e)
            return 0
        }
        for (r in learning.refused) Log.w(TAG, r.message)
        if (learning.learned.isEmpty()) return 0
        // Recorded first: if the write fails, the next run finds the phrase still flagged and learns it again.
        store.update { it.learned(learning.learned) }
        for (save in learning.saves) SettingsFiles.write(files, save.file, save.text)
        for (l in learning.learned) Log.i(TAG, "learned \"${l.alias}\" for \"${l.phrase}\" on ${l.model}")
        learning.learned.size
    }

    /** Every phrase the next Session would listen for, from the same files it reads. */
    fun probes(files: File): List<Probe> {
        val snapshot = try {
            HubStore(files).descriptors()
        } catch (e: Exception) {
            Log.e(TAG, "cannot read the cached hub Descriptors", e)
            null
        }
        val aliases = TurnCapture.loadHubAliases(files)
        val registry = HubProvider.registry(TurnCapture.loadBuckets(files), snapshot, aliases)
        return Probes.of(registry)
    }

    private fun run(context: Context, why: String) {
        if (SessionService.isActive) {
            Log.i(TAG, "not checking ($why): a Session is active; its end asks again")
            return
        }
        val models = ModelDownloads.locate(context)
        if (models == null) {
            Log.i(TAG, "not checking ($why): no speech models yet")
            return
        }
        // A learned alias is a new phrase, so it is checked in another round; a learned alias is usually
        // heard as itself, so the rounds end at once, and are bounded in case they do not.
        repeat(MAX_ROUNDS) {
            if (!check(context, models, why)) return
            if (learn(context.filesDir, models.modelId) == 0) return
        }
    }

    /** Checks every phrase not yet checked on [models]; false when it stopped (no voice, or a Session started). */
    private fun check(context: Context, models: InstalledModels, why: String): Boolean {
        val model = models.modelId
        val store = NameCheckStore(context.filesDir)
        val all = probes(context.filesDir)
        val pending = store.read().pending(all, model)
        if (pending.isEmpty()) return true
        Log.i(TAG, "checking ${pending.size} phrase(s) ($why): ${pending.joinToString { it.key }}")
        val t0 = SystemClock.elapsedRealtime()
        val noTts = noTtsAtMs
        if (noTts != null && SystemClock.elapsedRealtime() - noTts < NO_TTS_RETRY_MS) {
            Log.i(TAG, "not checking ($why): there was no TTS engine a moment ago")
            return false
        }
        val synth = TtsSynth.open(context, File(context.cacheDir, "name-check"))
        if (synth == null) {
            noTtsAtMs = SystemClock.elapsedRealtime()
            return false
        }
        noTtsAtMs = null
        synth.use {
            val variants = NameChecker.variants(synth.voices)
            Log.i(TAG, "variants: ${variants.joinToString { it.id }}")
            models.loadStt().use { stt ->
                val checker = NameChecker(stt, SAMPLE_RATE_HZ)
                var done = 0
                progress = 0 to pending.size
                listener?.invoke()
                for (p in pending) {
                    val heard = checker.check(p, variants, synth) { SessionService.isActive }
                    if (heard == null) {
                        if (SessionService.isActive) {
                            Log.i(TAG, "a Session started; stopping after $done of ${pending.size}")
                            return false
                        }
                        Log.w(TAG, "could not speak \"${p.spoken}\" in any voice")
                        continue
                    }
                    val r = store.update { it.with(p.key, model, heard, System.currentTimeMillis()) }
                    val v = r.verdict(p, all, model)!!
                    Log.i(TAG, "\"${p.key}\" (${p.kind.name.lowercase()} ${p.owner}): ${v.level} ${v.passes}/${heard.size}; heard ${heard.joinToString { "${it.variant} '${it.text}'" }}")
                    progress = ++done to pending.size
                    listener?.invoke()
                }
            }
        }
        Log.i(TAG, "checked ${pending.size} phrase(s) in ${SystemClock.elapsedRealtime() - t0} ms")
        return true
    }
}
