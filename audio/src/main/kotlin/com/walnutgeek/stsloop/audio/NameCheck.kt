package com.walnutgeek.stsloop.audio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.walnutgeek.stsloop.audio.speech.InstalledModels
import com.walnutgeek.stsloop.audio.speech.ModelDownloads
import com.walnutgeek.stsloop.audio.speech.TtsSynth
import com.walnutgeek.stsloop.core.grammar.BucketRegistry
import com.walnutgeek.stsloop.core.hub.HubProvider
import com.walnutgeek.stsloop.core.hub.HubStore
import com.walnutgeek.stsloop.core.recognisability.NameCheckResults
import com.walnutgeek.stsloop.core.recognisability.NameCheckStore
import com.walnutgeek.stsloop.core.recognisability.NameChecker
import com.walnutgeek.stsloop.core.recognisability.Probe
import com.walnutgeek.stsloop.core.recognisability.Probes
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
 * It never competes with a Session: it does not start while one is active,
 * and stops between two variants once one starts, filing only the phrases
 * it finished; the Session's end requests it again. The recogniser takes
 * ~750 MB and a second one would also take CPU from the loop's own decoding,
 * so the check waits rather than share. It is requested whenever the phrases
 * may have changed: at pairing, when a Session ends (the hub may have sent
 * new Descriptors), after an alias edit, and when the app or the settings
 * screen opens.
 */
object NameCheck {
    private const val TAG = "stsloop.NameCheck"

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "name-check").apply { isDaemon = true } }
    private val queued = AtomicBoolean()

    /** Called on the check's thread after each phrase is filed, and when a run ends. */
    @Volatile
    var listener: (() -> Unit)? = null

    /** While a run is under way: how many phrases it has done, and of how many. */
    @Volatile
    var progress: Pair<Int, Int>? = null
        private set

    /** Any thread: check whatever is not yet checked, now or once no Session is active. [why] is for the log. */
    fun request(context: Context, why: String) {
        val app = context.applicationContext
        if (!queued.compareAndSet(false, true)) return // one run queued is enough: it reads the files when it starts
        executor.execute {
            queued.set(false)
            try {
                run(app, why)
            } catch (e: Throwable) {
                Log.e(TAG, "the check failed", e)
            } finally {
                progress = null
                listener?.invoke()
            }
        }
    }

    /** What has been heard so far. */
    fun results(context: Context): NameCheckResults = NameCheckStore(context.filesDir).read()

    /** The model results are filed under, or null when no speech models are installed. */
    fun modelId(context: Context): String? = ModelDownloads.locate(context)?.modelId

    /**
     * Session start: the announcement for the Bucket phrases of [registry]
     * that [models] cannot reliably hear and no Session has announced,
     * marking them announced; null when there are none.
     */
    fun announceOnce(files: File, models: InstalledModels, registry: BucketRegistry): String? {
        val probes = Probes.of(registry)
        var say = emptyList<String>()
        NameCheckStore(files).update { r ->
            say = r.toAnnounce(probes, models.modelId)
            r.announced(say)
        }
        return NameCheckResults.announcement(say)
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
        val registry = HubProvider.registry(TurnCapture.loadBuckets(files), aliases.applyTo(snapshot))
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
        val model = models.modelId
        val store = NameCheckStore(context.filesDir)
        val all = probes(context.filesDir)
        val pending = store.read().pending(all, model)
        if (pending.isEmpty()) return
        Log.i(TAG, "checking ${pending.size} phrase(s) ($why): ${pending.joinToString { it.key }}")
        val t0 = SystemClock.elapsedRealtime()
        val synth = TtsSynth.open(context, File(context.cacheDir, "name-check")) ?: return
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
                            return
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
    }
}
