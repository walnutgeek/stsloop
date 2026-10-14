package com.walnutgeek.stsloop.audio.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.walnutgeek.stsloop.core.models.DownloadNotice

/**
 * Keeps the first-run model download ([ModelDownloads]) running with the
 * screen off: a `dataSync` foreground service, with the download's progress
 * as its notification. It holds no work of its own. The download stays on
 * [ModelDownloads]' thread; this only keeps the process out of the cached,
 * frozen state while [ModelDownloads.holdsForeground], and stops itself once
 * that is false.
 *
 * Why `dataSync` and not a user-initiated data transfer job: one path for
 * every supported API level (UIDT jobs are API 34+), and [DownloadPolicy]
 * stays the one place that decides Wi-Fi versus mobile data, rather than a
 * job's network constraint. Its 6 hours a day (targetSdk 35+) is far more
 * than a 465 MB download takes; [onTimeout] stops it, and the download then
 * goes on while the app is open, as it did before.
 *
 * Started only from [ModelDownloads], on the main thread. Without
 * POST_NOTIFICATIONS the service still runs; only its notification is hidden.
 */
class ModelDownloadService : Service() {
    private val changed: () -> Unit = { update() }
    private var posted: DownloadNotice? = null
    private var postedAt = 0L
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Speech model download", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        ModelDownloads.observe(changed)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notice = DownloadNotice.of(ModelDownloads.status, ModelDownloads.network)
        // Always first, even if the download has already finished: startForegroundService() requires it.
        try {
            startForeground(NOTIFICATION_ID, notification(notice), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException: the day's dataSync time is used up (targetSdk 35+).
            // A permission-free shortService still satisfies startForegroundService() before stopping.
            Log.w(TAG, "cannot hold the foreground for the model download", e)
            runCatching {
                startForeground(NOTIFICATION_ID, notification(notice), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            }.onFailure { Log.e(TAG, "cannot enter the foreground even briefly", it) }
            finish()
            return START_NOT_STICKY
        }
        posted = notice
        postedAt = SystemClock.elapsedRealtime()
        update()
        return START_NOT_STICKY
    }

    /** The day's 6 hours of `dataSync` are used up. The download carries on while the app is open. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "the model download's foreground time is used up for today")
        ModelDownloads.foregroundTimedOut()
        finish()
    }

    /** Main thread: follow [ModelDownloads]. */
    private fun update() {
        if (stopped) return
        if (!ModelDownloads.holdsForeground) {
            finish()
            return
        }
        val notice = DownloadNotice.of(ModelDownloads.status, ModelDownloads.network)
        val now = SystemClock.elapsedRealtime()
        if (!DownloadNotice.due(posted, notice, now - postedAt)) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(notice))
        posted = notice
        postedAt = now
    }

    private fun finish() {
        if (stopped) return
        stopped = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ModelDownloads.unobserve(changed)
        ModelDownloads.serviceStopped()
        super.onDestroy()
    }

    private fun notification(notice: DownloadNotice): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("stsloop")
            .setContentText(notice.text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                when (val bar = notice.bar) {
                    DownloadNotice.Bar.None -> Unit
                    DownloadNotice.Bar.Busy -> setProgress(0, 0, true)
                    is DownloadNotice.Bar.Percent -> setProgress(100, bar.value, false)
                }
            }
            .build()
    }

    companion object {
        private const val TAG = "stsloop.Models"
        private const val CHANNEL_ID = "model-download"

        /** SessionService uses 1. */
        private const val NOTIFICATION_ID = 2

        /**
         * Starts the service. Throws (ForegroundServiceStartNotAllowedException) when the app
         * may not start a foreground service now, such as from the background.
         */
        internal fun start(context: Context) {
            context.startForegroundService(Intent(context, ModelDownloadService::class.java))
        }
    }
}
