package com.walnutgeek.stsloop.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.walnutgeek.stsloop.core.Ids
import com.walnutgeek.stsloop.core.SessionAction
import com.walnutgeek.stsloop.core.SessionControls
import com.walnutgeek.stsloop.core.SessionEffect
import com.walnutgeek.stsloop.core.SessionEvent
import com.walnutgeek.stsloop.core.SessionMachine
import com.walnutgeek.stsloop.core.SessionState
import com.walnutgeek.stsloop.core.StartGate
import com.walnutgeek.stsloop.core.StartRefusal
import com.walnutgeek.stsloop.core.Transition
import com.walnutgeek.stsloop.core.TurnInProgress
import com.walnutgeek.stsloop.core.Wav
import java.io.File

/**
 * A Session: a `microphone`-typed foreground service that owns the mic from
 * Start until Stop, then writes the whole capture as one Turn to the Corpus.
 *
 * Its notification is the Session's eyes-free control surface: Stop while a
 * Session runs and, once it ends, a detached plain notification that keeps
 * Start reachable without opening the app.
 *
 * RECORD_AUDIO is while-in-use, so a microphone FGS may only start from a
 * visible activity or a documented exemption. The Start action uses "the user
 * interacted with a notification": its PendingIntent targets this service
 * directly. (An activity trampoline from a notification is blocked on 12+.)
 */
class SessionService : Service() {

    companion object {
        private const val TAG = "stsloop.Session"
        private const val ACTION_START = "com.walnutgeek.stsloop.session.START"
        private const val ACTION_STOP = "com.walnutgeek.stsloop.session.STOP"
        private const val LEGACY_CHANNEL_ID = "session" // was IMPORTANCE_LOW; importance cannot be raised in place
        private const val CHANNEL_ID = "session-controls"
        private const val NOTIFICATION_ID = 1
        private const val CHUNK_SAMPLES = SAMPLE_RATE_HZ / 10 // 100 ms
        private const val DESTROY_JOIN_MS = 5_000L

        /** Owned by the main thread; readable anywhere for display. */
        @Volatile
        var state: SessionState = SessionState.NoSession
            private set

        /** True while a Session holds the microphone or is still writing its Turn. */
        val isActive: Boolean get() = state.isActive

        /** Starts a Session. Call only from a visible activity with RECORD_AUDIO granted. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, SessionService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }

        fun corpusDir(context: Context) = File(context.filesDir, "corpus")

        /**
         * Whether the Session notification would actually be shown: app notifications
         * allowed (POST_NOTIFICATIONS on 13+) and the Session channel not blocked.
         */
        fun controlsVisible(context: Context): Boolean {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (!nm.areNotificationsEnabled()) return false
            val channel = nm.getNotificationChannel(CHANNEL_ID) ?: return true // created on first use
            return channel.importance != NotificationManager.IMPORTANCE_NONE
        }
    }

    // [state] is owned by the main thread; only [capturing] is read by the capture thread.
    @Volatile
    private var capturing = false
    private var captureThread: Thread? = null
    private var lastStartId = 0
    private var destroyed = false
    private var posted: SessionControls? = null // what the notification currently shows
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        // Here, not per notification: deleting a channel an FGS is using throws.
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        // DEFAULT, not LOW: LOW files the notification under "Silent", collapsed, with its
        // action hidden. No sound or vibration, so state changes never alert.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Session controls", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> commit(SessionMachine.on(state, SessionEvent.Stop))
            else -> if (captureThread == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        // The activity checks the gate too; the notification's Start reaches here unchecked.
        val refusal = StartGate.check(
            micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            controlsVisible = controlsVisible(this),
        )
        if (refusal != null && captureThread == null) {
            Log.w(TAG, "Session start refused: $refusal")
            refuse(refusal)
            return
        }
        val next = SessionMachine.on(state, SessionEvent.Start)
        val controls = SessionControls.of(next.state)
        try {
            // Always, even if already capturing: startForegroundService() requires it.
            startForeground(NOTIFICATION_ID, notification(controls), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            posted = controls
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException (an IllegalStateException) or
            // SecurityException: started from the background without an exemption.
            Log.e(TAG, "cannot start a microphone foreground service", e)
            if (captureThread == null) refuse(StartRefusal.NOT_ALLOWED)
            return
        }
        commit(next)
    }

    /**
     * Stop after a refused Start, leaving the reason on the notification. We were started
     * with startForegroundService(), and stopping without ever calling startForeground()
     * makes the system crash the app; a permission-free shortService satisfies it.
     */
    private fun refuse(refusal: StartRefusal) {
        val blocked = SessionMachine.on(state, SessionEvent.StartRefused(refusal))
        val controls = SessionControls.of(blocked.state)
        try {
            startForeground(NOTIFICATION_ID, notification(controls), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            posted = controls
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot enter the foreground even briefly", e)
        }
        commit(blocked)
    }

    /** Main thread: commit one transition, run its effects, and update the notification. */
    private fun commit(transition: Transition) {
        state = transition.state
        for (effect in transition.effects) when (effect) {
            SessionEffect.LaunchCapture -> launchCapture()
            SessionEffect.SignalStop -> capturing = false
            SessionEffect.StopServiceKeepControls -> {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(lastStartId)
            }
        }
        postControls()
    }

    /** Show the current state's controls, unless the notification already does. */
    private fun postControls() {
        val controls = SessionControls.of(state)
        if (controls == posted) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(controls))
        posted = controls
    }

    private fun launchCapture() {
        val sessionId = Ids.next()
        capturing = true
        captureThread = Thread({ capture(sessionId) }, "session-$sessionId").apply { start() }
        Log.i(TAG, "Session $sessionId started")
    }

    /** Main thread, after the capture thread has published (or abandoned) its Turn. */
    private fun onCaptureEnded() {
        if (destroyed) return
        captureThread = null
        commit(SessionMachine.on(state, SessionEvent.CaptureEnded))
    }

    /** Capture thread: mic → Corpus writer until [capturing] goes false, then publish the Turn. */
    @SuppressLint("MissingPermission") // the activity holds RECORD_AUDIO before starting a Session
    private fun capture(sessionId: String) {
        val writer = FileCorpusWriter(corpusDir(this), File(filesDir, "corpus-staging"))
        var turn: TurnInProgress? = null
        var record: AudioRecord? = null
        try {
            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes, CHUNK_SAMPLES * Wav.BYTES_PER_SAMPLE) * 4,
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
            record.startRecording()
            val startedAt = System.currentTimeMillis()
            turn = writer.begin(Ids.next(), sessionId, startedAt, SAMPLE_RATE_HZ)

            val buf = ShortArray(CHUNK_SAMPLES)
            var peak = 0
            while (capturing) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) error("AudioRecord.read returned $n")
                for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(buf[i].toInt()))
                turn.append(buf, n)
            }
            record.stop()
            val written = turn.finish(appVersion())
            turn = null
            if (peak == 0) Log.w(TAG, "Turn ${written.id} is all zeros: the mic was silenced")
            Log.i(TAG, "Session $sessionId wrote Turn ${written.directoryName} (${written.audio.durationMs} ms, peak $peak)")
        } catch (e: Exception) {
            Log.e(TAG, "Session $sessionId failed", e)
            turn?.abandon()
        } finally {
            record?.release()
            main.post(::onCaptureEnded)
        }
    }

    override fun onDestroy() {
        // Normally reached only via StopServiceKeepControls. If the system tears us down
        // mid-Session, give the capture thread a bounded chance to publish the Turn.
        destroyed = true
        capturing = false
        captureThread?.join(DESTROY_JOIN_MS)
        if (state.isActive) {
            state = SessionState.NoSession
            stopForeground(STOP_FOREGROUND_DETACH)
            postControls()
        }
        super.onDestroy()
    }

    private fun appVersion(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

    private fun notification(controls: SessionControls): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val pending = when (controls.action) {
            // A notification action may start a microphone FGS from the background:
            // the notification-interaction exemption to while-in-use restrictions.
            SessionAction.START -> PendingIntent.getForegroundService(
                this, 1, Intent(this, SessionService::class.java).setAction(ACTION_START), PendingIntent.FLAG_IMMUTABLE,
            )
            SessionAction.STOP -> PendingIntent.getService(
                this, 0, Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            SessionAction.OPEN_APP -> open // a direct activity PendingIntent, not a trampoline
        }
        val label = when (controls.action) {
            SessionAction.START -> "Start"
            SessionAction.STOP -> "Stop"
            SessionAction.OPEN_APP -> "Open"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("stsloop")
            .setContentText(controls.status)
            .setContentIntent(open)
            .setOngoing(true) // survives "Clear all"; Android 14+ still lets the user swipe it away
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, label, pending).build())
            .build()
    }
}
