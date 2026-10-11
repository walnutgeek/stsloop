package com.walnutgeek.stsloop.audio.testmode

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.walnutgeek.stsloop.audio.AudioRoute
import com.walnutgeek.stsloop.audio.AudioRoute.Companion.describe
import com.walnutgeek.stsloop.audio.AudioRoute.Companion.modeName
import com.walnutgeek.stsloop.audio.AudioRoute.Companion.typeName
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.testmode.Announcement
import com.walnutgeek.stsloop.core.testmode.AudioMode
import com.walnutgeek.stsloop.core.testmode.MicInput
import com.walnutgeek.stsloop.core.testmode.MicSource
import com.walnutgeek.stsloop.core.testmode.SESSIONS_DIR
import com.walnutgeek.stsloop.core.testmode.SessionLog
import com.walnutgeek.stsloop.core.testmode.TestConfig
import com.walnutgeek.stsloop.core.testmode.TestModeTracker
import com.walnutgeek.stsloop.core.testmode.TtsUsage
import com.walnutgeek.stsloop.core.testmode.TurnTest
import com.walnutgeek.stsloop.core.turn.Utterance
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Bluetooth test mode (#27) for one Session: picks the microphone path,
 * speaks [TestConfig.phrase] every [TestConfig.ttsIntervalMs] while capture
 * continues (deliberately not Half-duplex), and logs every routing fact to
 * `corpus/sessions/<started_at>-<session_id>.jsonl`. Each Turn gets a `test`
 * block from [turnTest].
 *
 * Call order, all from the capture thread: [prepare] before the AudioRecord
 * exists (audio mode, communication device), [attach] once it is built,
 * [started] after `startRecording()`, [captured] after every read, [close]
 * at the end. Platform callbacks and TTS run on this Session's own handler
 * thread and only log and update the [TestModeTracker].
 */
class TestModeSession(
    private val context: Context,
    val config: TestConfig,
    private val sessionId: String,
    corpusDir: File,
    private val sampleRate: Int,
) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val tracker = TestModeTracker(config, sampleRate)
    private val thread = HandlerThread("testmode-$sessionId").apply { start() }
    private val handler = Handler(thread.looper)
    private val events = EventLog(File(File(corpusDir, SESSIONS_DIR), SessionLog.fileName(System.currentTimeMillis(), sessionId)))
    private val ttsAttributes = AudioAttributes.Builder()
        .setUsage(usageOf(config.ttsUsage))
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var record: AudioRecord? = null
    private val route = AudioRoute(am, sessionId)
    private var bluetoothUnavailable = false

    /** Test seam: called once the audio mode and communication device are set, to force a failure. */
    internal var faultAfterRouteSet: (() -> Unit)? = null
    private var preferredInput: AudioDeviceInfo? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var phrases = 0
    private var lastRecording: String? = null

    // Per phrase, on the handler thread: where it started, and whether a player with its usage was seen.
    private val startSample = HashMap<String, Long>()
    private val playerSeen = HashMap<String, Boolean>()
    private val counts = linkedMapOf("requested" to 0, "started" to 0, "done" to 0, "errors" to 0, "stopped" to 0, "unobserved" to 0)

    // Requested and not yet ended. Not TextToSpeech.isSpeaking: that reads true right after init (observed).
    private val inFlight = HashSet<String>()
    private val requestedAtMs = HashMap<String, Long>()

    /** The `MediaRecorder.AudioSource` to record with. */
    val audioSource: Int = when (config.micSource) {
        MicSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        MicSource.MIC -> MediaRecorder.AudioSource.MIC
        MicSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        MicSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }

    /**
     * Before the AudioRecord is created: log the starting state, set the audio
     * mode and the communication device. If anything here throws, whatever was
     * already changed is put back before the exception leaves, so a failed
     * start never leaves the phone in call mode or on SCO. [close] must still
     * be called (it is idempotent about the route).
     */
    fun prepare() {
        try {
            setUp()
        } catch (t: Throwable) {
            Log.e(TAG, "Session $sessionId: test mode setup failed; restoring the audio route", t)
            log("prepare_failed", mapOf("error" to t.toString()))
            route.restore()
            throw t
        }
    }

    private fun setUp() {
        log("session_start", linkedMapOf(
            "session_id" to sessionId,
            "config" to configMap(),
            "summary" to config.summary(),
            "sdk" to Build.VERSION.SDK_INT,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "bluetooth_connect_granted" to granted(Manifest.permission.BLUETOOTH_CONNECT),
            "unprocessed_supported" to am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED),
            "mode" to modeName(am.mode),
            "inputs" to am.getDevices(AudioManager.GET_DEVICES_INPUTS).map(::describe),
            "outputs" to am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(::describe),
            "communication_devices" to am.availableCommunicationDevices.map(::describe),
            "communication_device" to am.communicationDevice?.let(::describe),
            "tts_output_devices" to ttsOutputs(),
        ))
        if (config.audioMode == AudioMode.IN_COMMUNICATION) {
            route.holdMode(AudioManager.MODE_IN_COMMUNICATION)
            log("mode_set", mapOf("requested" to "in_communication", "mode" to modeName(am.mode)))
        }
        when (config.micInput) {
            MicInput.BLUETOOTH -> {
                val handsFree = route.useHandsFree()
                if (handsFree == null) {
                    bluetoothUnavailable = true
                    log("bluetooth_unavailable", mapOf("communication_devices" to am.availableCommunicationDevices.map(::describe)))
                } else {
                    log("communication_device_set", mapOf("device" to describe(handsFree.device), "ok" to handsFree.ok))
                    preferredInput = handsFree.input
                }
            }
            MicInput.BUILTIN -> preferredInput = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            MicInput.DEFAULT -> Unit
        }
        faultAfterRouteSet?.invoke()
        am.addOnCommunicationDeviceChangedListener({ r -> handler.post(r) }, communicationListener)
        am.registerAudioDeviceCallback(deviceCallback, handler)
        am.registerAudioRecordingCallback(recordingCallback, handler)
        am.registerAudioPlaybackCallback(playbackCallback, handler)
    }

    /** Once the AudioRecord is built, before it starts: prefer the chosen input and watch its routing. */
    fun attach(record: AudioRecord) {
        this.record = record
        preferredInput?.let { d ->
            val ok = record.setPreferredDevice(d)
            log("input_preferred", mapOf("device" to describe(d), "ok" to ok))
        } ?: if (config.micInput != MicInput.DEFAULT) log("input_preferred", mapOf("device" to null, "ok" to false)) else Unit
        record.addOnRoutingChangedListener(routingListener, handler)
    }

    /** After `startRecording()`: record where capture is routed, then start the phrases. */
    fun started() {
        val r = record ?: return
        inputRouted(r.routedDevice, "started")
        if (!config.ttsOn) {
            log("tts_off")
            return
        }
        handler.post {
            tts = TextToSpeech(context) { status -> handler.post { ttsInit(status) } }
        }
    }

    /** Capture thread, after each read. */
    fun captured(samplesCaptured: Long, atNs: Long, chunk: ShortArray, count: Int) =
        tracker.captured(samplesCaptured, atNs, chunk, count)

    /** STT thread: the `test` block of a Turn about to be written. */
    fun turnTest(utterance: Utterance): TurnTest = tracker.turnTest(utterance, SystemClock.elapsedRealtimeNanos())

    /** STT thread: a Turn was published. */
    fun turnWritten(turn: Turn, utterance: Utterance) {
        val t = turn.test
        log("turn", linkedMapOf(
            "dir" to turn.directoryName,
            "start_sample" to utterance.startSample,
            "end_sample" to utterance.endSample,
            "transcript" to turn.transcript?.text,
            "input_devices" to t?.inputDevices,
            "tts_overlap_ms" to t?.ttsOverlapMs,
            "tts_phrases" to t?.ttsPhrases,
        ))
    }

    /**
     * Ends test mode: stops the phrases, unregisters, restores the route and
     * mode, and closes the log. Each step runs on its own, so one that throws
     * never keeps the audio route from being restored.
     */
    fun close() {
        try {
            step("stop TTS") { stopTts() }
            step("unregister communication listener") { am.removeOnCommunicationDeviceChangedListener(communicationListener) }
            step("unregister device callback") { am.unregisterAudioDeviceCallback(deviceCallback) }
            step("unregister recording callback") { am.unregisterAudioRecordingCallback(recordingCallback) }
            step("unregister playback callback") { am.unregisterAudioPlaybackCallback(playbackCallback) }
            step("unregister routing listener") { record?.removeOnRoutingChangedListener(routingListener) }
            val cleared = route.communicationSet
            route.restore()
            step("log the end") {
                log("session_end", linkedMapOf(
                    "phrases" to counts,
                    "communication_cleared" to cleared,
                    "mode" to modeName(am.mode),
                    "communication_device" to am.communicationDevice?.let(::describe),
                ))
            }
        } finally {
            thread.quitSafely()
            events.close()
        }
    }


    private inline fun step(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "Session $sessionId: could not $what", t)
        }
    }

    private fun stopTts() {
        val done = CountDownLatch(1)
        val posted = handler.post {
            handler.removeCallbacks(tick)
            // A phrase cut off by the end of the Session: close its interval here, since the
            // engine's onStop arrives after this thread has quit.
            for (id in inFlight.toList()) phraseEnded(id, SystemClock.elapsedRealtimeNanos(), "tts_stopped", null)
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "TTS shutdown failed", e)
            }
            tts = null
            done.countDown()
        }
        if (posted) done.await(2, TimeUnit.SECONDS)
    }

    // --- TTS ---

    private val tick = object : Runnable {
        override fun run() {
            speak("tts-${++phrases}", config.phrase(phrases))
            handler.postDelayed(this, config.ttsIntervalMs.toLong())
        }
    }

    private fun ttsInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            log("tts_init_failed", mapOf("status" to status))
            return
        }
        val lang = t.setLanguage(Locale.US)
        t.setAudioAttributes(ttsAttributes)
        t.setOnUtteranceProgressListener(progress)
        ttsReady = true
        log("tts_ready", mapOf(
            "engine" to t.defaultEngine,
            "voice" to t.voice?.name,
            "language_result" to lang,
            "usage" to config.ttsUsage.json,
            "volume_stream" to ttsAttributes.volumeControlStream,
        ))
        speak("announce", Announcement.text(config, tracker.currentInput, bluetoothUnavailable))
        handler.postDelayed(tick, config.ttsIntervalMs.toLong())
    }

    private fun speak(id: String, text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        // A phrase with no end callback after LOST_MS never played, or its callbacks were lost: say so, move on.
        val nowMs = SystemClock.elapsedRealtime()
        for (old in inFlight.filter { nowMs - (requestedAtMs[it] ?: nowMs) > LOST_MS }) {
            log("tts_lost", mapOf("phrase" to old, "started" to (old in startSample)))
            phraseEnded(old, SystemClock.elapsedRealtimeNanos(), "tts_lost", null)
        }
        if (inFlight.isNotEmpty()) {
            log("tts_skipped", mapOf("phrase" to id, "why" to "still speaking $inFlight"))
            return
        }
        counts["requested"] = counts.getValue("requested") + 1
        val stream = ttsAttributes.volumeControlStream
        val result = t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (result == TextToSpeech.SUCCESS) {
            inFlight += id
            requestedAtMs[id] = nowMs
        }
        log("tts_request", linkedMapOf(
            "phrase" to id,
            "text" to text,
            "result" to if (result == TextToSpeech.SUCCESS) "success" else "error",
            "volume" to am.getStreamVolume(stream),
            "volume_max" to am.getStreamMaxVolume(stream),
            "output_devices" to ttsOutputs(),
        ))
        if (am.getStreamVolume(stream) == 0) log("tts_inaudible", mapOf("phrase" to id, "why" to "stream volume is 0"))
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            val ns = SystemClock.elapsedRealtimeNanos()
            handler.post { phraseStarted(utteranceId, ns) }
        }

        override fun onDone(utteranceId: String) {
            val ns = SystemClock.elapsedRealtimeNanos()
            handler.post { phraseEnded(utteranceId, ns, "tts_done", null) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            val ns = SystemClock.elapsedRealtimeNanos()
            handler.post { phraseEnded(utteranceId, ns, "tts_error", null) }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            val ns = SystemClock.elapsedRealtimeNanos()
            handler.post { phraseEnded(utteranceId, ns, "tts_error", errorCode) }
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            val ns = SystemClock.elapsedRealtimeNanos()
            handler.post { phraseEnded(utteranceId, ns, "tts_stopped", null) }
        }
    }

    private fun phraseStarted(id: String, ns: Long) {
        counts["started"] = counts.getValue("started") + 1
        val sample = tracker.ttsStarted(id, ns)
        sample?.let { startSample[id] = it }
        playerSeen[id] = am.activePlaybackConfigurations.any(::isOurUsage)
        log("tts_start", linkedMapOf(
            "phrase" to id,
            "sample" to sample,
            "output_devices" to ttsOutputs(),
            "players" to am.activePlaybackConfigurations.map(::describePlayer),
            "communication_device" to am.communicationDevice?.let(::describe),
            "mode" to modeName(am.mode),
            "input" to tracker.currentInput,
            "recording" to ourRecording()?.let(::describeRecording),
        ))
    }

    private fun phraseEnded(id: String, ns: Long, event: String, errorCode: Int?) {
        if (!inFlight.remove(id)) return // already ended (e.g. both onError overloads, or stopped at close)
        requestedAtMs.remove(id)
        val end = tracker.ttsDone(id, ns)
        val start = startSample.remove(id)
        val seen = playerSeen.remove(id) == true
        val counter = when (event) {
            "tts_done" -> "done"
            "tts_stopped" -> "stopped"
            else -> "errors"
        }
        counts[counter] = counts.getValue(counter) + 1
        val fields = linkedMapOf<String, Any?>("phrase" to id, "sample" to end)
        errorCode?.let { fields["error_code"] = it }
        if (start != null && end != null) {
            val sr = sampleRate
            val during = tracker.micDbfs(start, end)
            val before = tracker.micDbfs(maxOf(0L, start - sr), start)
            fields["duration_ms"] = (end - start) * 1000 / sr
            fields["mic_dbfs_during"] = during?.round1()
            fields["mic_dbfs_before"] = before?.round1()
            fields["mic_rise_db"] = if (during != null && before != null) (during - before).round1() else null
        }
        fields["player_seen"] = seen
        fields["output_devices"] = ttsOutputs()
        log(event, fields)
        if (event == "tts_done" && !seen) {
            // The engine said it finished, yet no player with our usage was ever active: likely silent.
            counts["unobserved"] = counts.getValue("unobserved") + 1
            log("tts_unobserved", mapOf("phrase" to id))
        }
    }

    // --- routing callbacks (handler thread) ---

    private val routingListener = AudioRouting.OnRoutingChangedListener { router ->
        inputRouted(router.routedDevice, "routing_changed")
    }

    private fun inputRouted(device: AudioDeviceInfo?, why: String) {
        val ns = SystemClock.elapsedRealtimeNanos()
        device?.let { tracker.inputRouted(typeName(it.type), ns) }
        log("input_routed", mapOf("why" to why, "sample" to tracker.sampleAt(ns), "device" to device?.let(::describe)))
    }

    private val communicationListener = AudioManager.OnCommunicationDeviceChangedListener { d ->
        log("communication_device", mapOf("device" to d?.let(::describe), "sample" to nowSample()))
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            log("devices_added", mapOf("devices" to added.map(::describe), "sample" to nowSample()))
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            log("devices_removed", mapOf("devices" to removed.map(::describe), "sample" to nowSample()))
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val ours = ourRecording(configs)
            val described = ours?.let(::describeRecording)
            val key = described?.toString()
            if (key == lastRecording) return
            lastRecording = key
            log("recording", mapOf("ours" to described, "others" to configs.size - (if (ours == null) 0 else 1), "sample" to nowSample()))
            if (ours?.isClientSilenced == true) Log.w(TAG, "Session $sessionId: our recording is silenced")
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            if (configs.any(::isOurUsage)) for (id in playerSeen.keys) playerSeen[id] = true
            log("playback", mapOf("players" to configs.map(::describePlayer), "sample" to nowSample()))
        }
    }

    // --- helpers ---

    private fun nowSample() = tracker.sampleAt(SystemClock.elapsedRealtimeNanos())

    private fun ourRecording(configs: List<AudioRecordingConfiguration> = am.activeRecordingConfigurations): AudioRecordingConfiguration? {
        val session = record?.audioSessionId ?: return null
        return configs.firstOrNull { it.clientAudioSessionId == session }
    }

    private fun isOurUsage(p: AudioPlaybackConfiguration) = p.audioAttributes.usage == ttsAttributes.usage

    private fun ttsOutputs(): List<Map<String, Any?>>? =
        if (Build.VERSION.SDK_INT >= 33) am.getAudioDevicesForAttributes(ttsAttributes).map { a ->
            mapOf("type" to typeName(a.type))
        } else null

    private fun configMap() = Json.parseObject(config.toJson()) // the file's own keys, once

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun log(event: String, fields: Map<String, Any?> = emptyMap()) {
        val line = SessionLog.line(System.currentTimeMillis(), event, fields)
        Log.i(TAG, "Session $sessionId ${line.trimEnd()}")
        events.append(line)
    }

    /** The append-only `.jsonl` file; lines are flushed as written, synced at close. Thread-safe. */
    private class EventLog(private val file: File) {
        private var out: FileOutputStream? = null
        private var closed = false

        @Synchronized
        fun append(line: String) {
            if (closed) return
            try {
                val o = out ?: run {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file, true).also { out = it }
                }
                o.write(line.toByteArray(Charsets.UTF_8))
                o.flush()
            } catch (e: Exception) {
                Log.e(TAG, "cannot append to $file", e)
            }
        }

        @Synchronized
        fun close() {
            closed = true
            runCatching { out?.fd?.sync() }
            runCatching { out?.close() }
        }
    }

    companion object {
        private const val TAG = "stsloop.TestMode"
        private const val LOST_MS = 30_000L

        fun usageOf(u: TtsUsage): Int = when (u) {
            TtsUsage.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
            TtsUsage.MEDIA -> AudioAttributes.USAGE_MEDIA
            TtsUsage.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            TtsUsage.VOICE_COMMUNICATION -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        }

        private fun Double.round1() = (this * 10).roundToInt() / 10.0

        @Suppress("DEPRECATION") // getAudioDeviceInfo: the only public per-player device on API 31+
        fun describePlayer(p: AudioPlaybackConfiguration): Map<String, Any?> = linkedMapOf(
            "usage" to p.audioAttributes.usage,
            "content" to p.audioAttributes.contentType,
            "device" to p.audioDeviceInfo?.let(::describe),
        )

        fun describeRecording(r: AudioRecordingConfiguration): Map<String, Any?> = linkedMapOf(
            "silenced" to r.isClientSilenced,
            "source" to r.clientAudioSource,
            "device" to r.audioDevice?.let(::describe),
        )
    }
}
