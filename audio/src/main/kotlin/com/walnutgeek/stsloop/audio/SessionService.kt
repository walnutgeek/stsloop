package com.walnutgeek.stsloop.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import com.walnutgeek.stsloop.core.Ids
import com.walnutgeek.stsloop.core.TurnInProgress
import java.io.File

/**
 * A Session: a `microphone`-typed foreground service that owns the mic from
 * Start until Stop, then writes the whole capture as one Turn to the Corpus.
 *
 * Must be started from a visible activity — RECORD_AUDIO is while-in-use, so a
 * microphone FGS started from the background throws SecurityException.
 */
class SessionService : Service() {

    companion object {
        private const val TAG = "stsloop.Session"
        private const val ACTION_START = "com.walnutgeek.stsloop.session.START"
        private const val ACTION_STOP = "com.walnutgeek.stsloop.session.STOP"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1
        private const val CHUNK_SAMPLES = SAMPLE_RATE_HZ / 10 // 100 ms

        /** True while a Session holds the microphone. */
        @Volatile
        var isActive: Boolean = false
            private set

        /** Starts a Session. Call only from a visible activity with RECORD_AUDIO granted. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, SessionService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }

        fun corpusDir(context: Context) = File(context.filesDir, "corpus")
    }

    @Volatile
    private var capturing = false
    @Volatile
    private var captureThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> stopSession()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // the activity holds RECORD_AUDIO before starting a Session
    private fun startSession() {
        if (captureThread != null) return
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: SecurityException) {
            // Missing RECORD_AUDIO, or not started from a visible activity.
            Log.e(TAG, "cannot start a microphone foreground service", e)
            stopSelf()
            return
        }
        isActive = true
        capturing = true
        val sessionId = Ids.next()
        captureThread = Thread({ capture(sessionId) }, "session-$sessionId").apply { start() }
        Log.i(TAG, "Session $sessionId started")
    }

    private fun stopSession() {
        capturing = false
        if (captureThread == null) stopSelf()
    }

    /** Capture thread: mic → Corpus writer, then end the service. */
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
                maxOf(minBytes, CHUNK_SAMPLES * 2) * 4,
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
            val endedAt = System.currentTimeMillis()
            val written = turn.finish(endedAt, appVersion())
            turn = null
            if (peak == 0) Log.w(TAG, "Turn ${written.id} is all zeros: the mic was silenced")
            Log.i(TAG, "Session $sessionId wrote Turn ${written.directoryName} (${written.audio.durationMs} ms, peak $peak)")
        } catch (e: Exception) {
            Log.e(TAG, "Session $sessionId failed", e)
            turn?.abandon()
        } finally {
            record?.release()
            isActive = false
            captureThread = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        capturing = false
        super.onDestroy()
    }

    private fun appVersion(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Session", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("stsloop Session")
            .setContentText("Listening")
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
