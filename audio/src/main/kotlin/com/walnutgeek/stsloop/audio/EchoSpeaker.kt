package com.walnutgeek.stsloop.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.walnutgeek.stsloop.core.turn.Speaker
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The loop's voice: speaks each machine Turn with the system's offline
 * [TextToSpeech], as `USAGE_ASSISTANT` speech. It runs inside the Session's
 * `microphone` foreground service, which is what lets it play from the
 * background on Android 17 (`docs/phase0.md`, "Platform constraints on the loop").
 * The output route follows [AudioRoute]: the car's hands-free path when a
 * communication device is set, otherwise A2DP or the speaker.
 *
 * Every machine Turn ends exactly once through [ended] (on the monotonic
 * `elapsedRealtimeNanos` clock the capture thread uses), whether it played,
 * failed, was never started because the engine is unavailable, or was cut off
 * by [close]: the Half-duplex gate reopens the mic on that report.
 *
 * Playback can fail silently (Android 17's background playback hardening
 * fails without throwing), so each one is checked: if the engine reports
 * done but it never reported a start, no player with our usage was ever
 * active, or the stream volume was 0, the Turn is logged as
 * `nothing plausibly played` and counted in [unobserved]. Every done
 * report is passed to [played], `false` for those, so the loop can say so.
 *
 * An Inbound Item ([alert]) plays the earcon (`res/raw/stsloop_earcon.wav`,
 * core's `Earcon`) through the same engine (`TextToSpeech.playEarcon`, so it
 * takes the same route and attributes),
 * queued straight before its text; the machine Turn's end is the text's.
 * If the earcon cannot be prepared or played, the text is still said.
 *
 * [speak] may be called from any thread; all TTS work runs on this Session's
 * own handler thread.
 */
class EchoSpeaker(
    private val context: Context,
    private val sessionId: String,
    /** The engine reported a machine Turn done; [heard] is false when nothing plausibly played. */
    private val played: (heard: Boolean) -> Unit = {},
    private val ended: (id: Long, atNs: Long) -> Unit,
) : Speaker {
    private val am = context.getSystemService(AudioManager::class.java)
    private val thread = HandlerThread("echo-$sessionId").apply { start() }
    private val handler = Handler(thread.looper)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** One machine Turn in flight, handler thread only. */
    private class MachineTurn(val id: Long, val text: String, val earcon: Boolean = false) {
        var startedAtNs = -1L
        var playerSeen = false
        var volume = -1
    }

    // Handler thread only.
    private var tts: TextToSpeech? = null
    private var engine = Engine.STARTING
    private var earconReady = false
    private val waiting = ArrayList<MachineTurn>() // asked for before the engine was ready
    private val inFlight = LinkedHashMap<String, MachineTurn>()

    private enum class Engine { STARTING, READY, FAILED, CLOSED }

    @Volatile
    var spoken = 0
        private set

    /** Reported done, yet nothing plausibly played. */
    @Volatile
    var unobserved = 0
        private set

    private val failures = AtomicInteger()

    /** Never played through: the engine failed, refused, stopped it, or was unavailable. */
    val failed: Int get() = failures.get()

    /** Binds the TTS engine in the background; a machine Turn asked for meanwhile waits for it. */
    fun start() {
        handler.post {
            am.registerAudioPlaybackCallback(playback, handler)
            tts = TextToSpeech(context) { status -> handler.post { initialised(status) } }
        }
    }

    override fun speak(id: Long, text: String) = post(MachineTurn(id, text))

    override fun alert(id: Long, text: String) = post(MachineTurn(id, text, earcon = true))

    private fun post(p: MachineTurn) {
        if (!handler.post { request(p) }) end(p, "not spoken: the Session's speaker is closed")
    }

    /**
     * The mic is reopening without an end report for [id]: make sure it never
     * plays. Dropped if still waiting for the engine; stopped if in flight.
     */
    override fun abandon(id: Long) {
        handler.post {
            waiting.removeAll { it.id == id }
            if (inFlight.remove(key(id)) != null) {
                failures.incrementAndGet()
                Log.w(TAG, "Session $sessionId echo $id abandoned: no end report in time; stopping the engine")
                runCatching { tts?.stop() }
            }
        }
    }

    /**
     * Stops speaking and releases the engine. A machine Turn still playing or
     * waiting is ended now. Waits up to 2 s for the handler thread; safe to call twice.
     */
    fun close() {
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                engine = Engine.CLOSED
                for (p in waiting + inFlight.values) end(p, "stopped: the Session ended", closing = true)
                waiting.clear()
                inFlight.clear()
                runCatching { tts?.stop() }
                runCatching { tts?.shutdown() }
                tts = null
                runCatching { am.unregisterAudioPlaybackCallback(playback) }
            } finally {
                done.countDown()
            }
        }
        if (posted) done.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
        Log.i(TAG, "Session $sessionId echo: $spoken spoken, $unobserved with nothing plausibly played, $failed failed")
    }

    // --- handler thread ---

    private fun initialised(status: Int) {
        val t = tts ?: return
        if (engine != Engine.STARTING) return
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Session $sessionId: TextToSpeech failed to start (status $status); Turns are not echoed")
            engine = Engine.FAILED
        } else {
            val lang = t.setLanguage(Locale.US)
            t.setAudioAttributes(attributes)
            t.setOnUtteranceProgressListener(progress)
            earconReady = prepareEarcon(t)
            engine = Engine.READY
            Log.i(TAG, "Session $sessionId echo ready: engine ${t.defaultEngine}, voice ${t.voice?.name}, language result $lang")
        }
        val queued = waiting.toList()
        waiting.clear()
        for (p in queued) request(p)
    }

    private fun request(p: MachineTurn) {
        when (engine) {
            Engine.STARTING -> return run { waiting += p }
            Engine.FAILED, Engine.CLOSED -> return end(p, "not spoken: no TextToSpeech engine")
            Engine.READY -> Unit
        }
        val t = tts ?: return end(p, "not spoken: no TextToSpeech engine")
        val key = key(p.id)
        p.volume = am.getStreamVolume(attributes.volumeControlStream)
        if (p.earcon && earconReady) {
            val played = t.playEarcon(EARCON, TextToSpeech.QUEUE_ADD, null, "$EARCON_KEY${p.id}")
            if (played != TextToSpeech.SUCCESS) Log.w(TAG, "Session $sessionId echo ${p.id}: no earcon (playEarcon returned $played)")
        }
        inFlight[key] = p
        val result = t.speak(p.text, TextToSpeech.QUEUE_ADD, null, key)
        if (result != TextToSpeech.SUCCESS) {
            inFlight.remove(key)
            return end(p, "not spoken: TextToSpeech.speak returned $result")
        }
        Log.i(TAG, "Session $sessionId echo ${p.id} requested (${p.text.length} chars, volume ${p.volume}, outputs ${outputs()})")
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            val ns = SystemClock.elapsedRealtimeNanos()
            handler.post {
                val p = inFlight[utteranceId] ?: return@post
                p.startedAtNs = ns
                if (am.activePlaybackConfigurations.any(::isOurs)) p.playerSeen = true
                Log.i(TAG, "Session $sessionId echo ${p.id} playing on ${outputs()}")
            }
        }

        override fun onDone(utteranceId: String) = report(utteranceId, null)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = report(utteranceId, "failed")

        override fun onError(utteranceId: String, errorCode: Int) = report(utteranceId, "failed with error $errorCode")

        override fun onStop(utteranceId: String, interrupted: Boolean) = report(utteranceId, "stopped")

        private fun report(utteranceId: String, problem: String?) {
            val ns = SystemClock.elapsedRealtimeNanos()
            if (utteranceId.startsWith(EARCON_KEY)) return earcon(utteranceId, problem)
            handler.post {
                val p = inFlight.remove(utteranceId) ?: return@post // already ended (both onError overloads, or closed)
                if (problem != null) return@post end(p, problem, ns)
                spoken++
                val why = buildList {
                    if (p.startedAtNs < 0) add("the engine never reported a start")
                    if (!p.playerSeen) add("no player with our usage was ever active")
                    if (p.volume == 0) add("the stream volume was 0")
                }
                val playedMs = if (p.startedAtNs < 0) -1 else (ns - p.startedAtNs) / 1_000_000
                if (why.isEmpty()) {
                    Log.i(TAG, "Session $sessionId echo ${p.id} done after $playedMs ms")
                } else {
                    unobserved++
                    Log.w(TAG, "Session $sessionId echo ${p.id}: TTS reported done but nothing plausibly played (${why.joinToString("; ")})")
                }
                ended(p.id, ns)
                played(why.isEmpty())
            }
        }
    }

    /**
     * An earcon's report, if the engine sends one (Google's does not, on the
     * Pixel 10 Pro): its machine Turn's end is its text's, so this is only logged.
     */
    private fun earcon(utteranceId: String, problem: String?) {
        if (problem == null) Log.i(TAG, "Session $sessionId $utteranceId played")
        else Log.w(TAG, "Session $sessionId $utteranceId $problem; its sentence is still said")
    }

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            if (configs.any(::isOurs)) for (p in inFlight.values) if (p.startedAtNs >= 0) p.playerSeen = true
        }
    }

    /** Ends [p] without it having played through; counted in [failed] unless the Session is [closing]. */
    private fun end(p: MachineTurn, why: String, atNs: Long = SystemClock.elapsedRealtimeNanos(), closing: Boolean = false) {
        if (!closing) failures.incrementAndGet()
        Log.w(TAG, "Session $sessionId echo ${p.id} $why")
        ended(p.id, atNs)
    }

    private fun isOurs(p: AudioPlaybackConfiguration) = p.audioAttributes.usage == attributes.usage

    private fun outputs(): List<String>? =
        if (Build.VERSION.SDK_INT >= 33) am.getAudioDevicesForAttributes(attributes).map { AudioRoute.typeName(it.type) } else null

    private fun key(id: Long) = "echo-$id"

    /**
     * Registers the earcon with the engine as this library's raw resource:
     * the engine runs in its own process, which can read an app's resources
     * but not its private files. False (logged) if it cannot.
     */
    private fun prepareEarcon(t: TextToSpeech): Boolean = try {
        val result = t.addEarcon(EARCON, context.packageName, R.raw.stsloop_earcon)
        if (result != TextToSpeech.SUCCESS) Log.w(TAG, "Session $sessionId: addEarcon returned $result; Items play no earcon")
        result == TextToSpeech.SUCCESS
    } catch (e: Exception) {
        Log.e(TAG, "Session $sessionId: cannot prepare the earcon; Items play no earcon", e)
        false
    }

    private companion object {
        const val TAG = "stsloop.Echo"
        const val EARCON = "[stsloop-item]"
        const val EARCON_KEY = "earcon-"
    }
}
