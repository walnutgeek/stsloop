package com.walnutgeek.stsloop.audio.speech

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.walnutgeek.stsloop.core.models.DownloadPolicy
import com.walnutgeek.stsloop.core.models.DownloadStatus
import com.walnutgeek.stsloop.core.models.ModelInstallException
import com.walnutgeek.stsloop.core.models.ModelStore
import com.walnutgeek.stsloop.core.models.NetworkKind
import com.walnutgeek.stsloop.core.models.SpeechManifest
import java.io.File

/**
 * The first-run download of the speech models, for the whole process.
 *
 * A release APK bundles no models. The first time the app is opened it
 * downloads them from the upstream sherpa-onnx releases into
 * `no_backup/models` (re-downloadable, so kept out of Auto Backup), each
 * checked against its pinned SHA-256 ([ModelStore]). [DownloadPolicy] says
 * when: by itself on Wi-Fi, over mobile data only once [allowMobileData] is
 * called. The download runs on a background thread while the process lives;
 * if the process dies, the next [ensure] resumes it from its partial file.
 *
 * Main thread only, except [store] and [locate].
 */
object ModelDownloads {
    private const val TAG = "stsloop.Models"
    private const val DIR = "models"
    private const val PROGRESS_EVERY_MS = 250L
    private const val RETRY_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    private var thread: Thread? = null
    @Volatile
    private var stopRequested = false
    private var meteredAllowed = false
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var appContext: Context? = null

    /** The one pending retry after a failure; re-posting replaces it rather than adding another. */
    private val retryLater = Runnable { appContext?.let(::apply) }

    /** What the screen shows. Read on the main thread. */
    var status: DownloadStatus = DownloadStatus.Waiting(0)
        private set

    /** The phone's default network, as last seen. */
    var network: NetworkKind = NetworkKind.NONE
        private set

    fun store(context: Context) = ModelStore(File(context.noBackupFilesDir, DIR), SpeechManifest.MANIFEST)

    /** The models a Session can load now, or null. Any thread; a few file checks. */
    fun locate(context: Context): InstalledModels? = InstalledModels.locate(context.assets, store(context))

    /** Call when the app is opened: works out the status and starts the download if the network allows. */
    fun ensure(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (thread == null) {
            status = if (locate(app) != null) DownloadStatus.Ready else idleStatus(app)
        }
        if (status == DownloadStatus.Ready) {
            unwatchNetwork(app)
            // A download a dead process left behind once its files were installed (up to 464 MB).
            if (thread == null) Thread({ runCatching { store(app).prune() } }, "stsloop-models-prune").start()
        } else {
            watchNetwork(app)
            apply(app)
        }
        notifyListeners()
    }

    /** The user's go-ahead to download over mobile data, for this process; also a retry after any failure. */
    fun allowMobileData(context: Context) {
        meteredAllowed = true
        ensure(context)
        if (thread == null && status is DownloadStatus.Failed) start(context.applicationContext)
    }

    /** Retry after a failure the policy will not retry by itself (a bad hash, a full disk). */
    fun retry(context: Context) {
        if (thread != null) return
        start(context.applicationContext)
    }

    /** [listener] runs on the main thread whenever [status] or [network] changes. */
    fun observe(listener: () -> Unit) {
        listeners += listener
    }

    fun unobserve(listener: () -> Unit) {
        listeners -= listener
    }

    /** True while the download waits for Wi-Fi, so the screen can offer mobile data instead. */
    val waitingForWifi: Boolean
        get() = !meteredAllowed && network == NetworkKind.METERED && when (val s = status) {
            is DownloadStatus.Waiting -> s.pendingBytes > 0
            is DownloadStatus.Failed -> s.pendingBytes > 0
            else -> false
        }

    private fun idleStatus(context: Context): DownloadStatus {
        val pending = store(context).pendingDownloadBytes()
        return when (val s = status) {
            is DownloadStatus.Failed -> s.copy(pendingBytes = pending)
            else -> DownloadStatus.Waiting(pending)
        }
    }

    private fun apply(context: Context) {
        main.removeCallbacks(retryLater)
        val toFetch = store(context).pendingDownloadBytes()
        when (DownloadPolicy.next(status, network, meteredAllowed, toFetch)) {
            DownloadPolicy.Action.START -> start(context)
            DownloadPolicy.Action.STOP -> {
                Log.i(TAG, "stopping the model download: the network is now metered")
                stopRequested = true
            }
            DownloadPolicy.Action.WAIT, DownloadPolicy.Action.NOTHING -> Unit
        }
    }

    private fun start(context: Context) {
        if (thread != null) return
        val store = store(context)
        stopRequested = false
        status = DownloadStatus.Running(null)
        notifyListeners()
        Log.i(TAG, "installing the speech models into ${store.root}: ${ModelStore.mb(store.pendingDownloadBytes())} to download")
        thread = Thread({ run(context, store) }, "stsloop-models").apply {
            isDaemon = true
            start()
        }
    }

    /** Background thread. */
    private fun run(context: Context, store: ModelStore) {
        var lastPost = 0L
        val startedAt = SystemClock.elapsedRealtime()
        val outcome: DownloadStatus = try {
            store.install(
                onProgress = { p ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPost >= PROGRESS_EVERY_MS || p.done == p.total) {
                        lastPost = now
                        main.post {
                            if (status is DownloadStatus.Running) {
                                status = DownloadStatus.Running(p)
                                notifyListeners()
                            }
                        }
                    }
                },
                cancelled = { stopRequested },
            )
            Log.i(TAG, "speech models installed in ${(SystemClock.elapsedRealtime() - startedAt) / 1000} s")
            DownloadStatus.Ready
        } catch (e: ModelInstallException) {
            val pending = runCatching { store.pendingDownloadBytes() }.getOrDefault(0)
            if (e.reason == ModelInstallException.Reason.CANCELLED) {
                Log.i(TAG, "model download stopped: ${e.message}")
                DownloadStatus.Waiting(pending)
            } else {
                Log.e(TAG, "model install failed (${e.reason})", e)
                DownloadStatus.Failed(e.reason, e.message ?: e.reason.name, pending)
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "model install failed", e)
            DownloadStatus.Failed(ModelInstallException.Reason.STORAGE, e.message ?: e.toString(), 0)
        }
        main.post {
            thread = null
            status = outcome
            when {
                outcome == DownloadStatus.Ready -> unwatchNetwork(context)
                // A dropped connection on a network that has not changed: try again in a while,
                // not at once, or a server that is down would be hammered.
                outcome is DownloadStatus.Failed -> main.postDelayed(retryLater, RETRY_MS)
                else -> apply(context)
            }
            notifyListeners()
        }
    }

    private fun watchNetwork(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        network = kindOf(cm.getNetworkCapabilities(cm.activeNetwork))
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) = changed(kindOf(caps))
            override fun onLost(n: Network) = changed(NetworkKind.NONE)

            // ConnectivityManager's own callback thread: hop to main, where all the state lives.
            private fun changed(kind: NetworkKind) {
                main.post {
                    if (kind == network) return@post
                    Log.i(TAG, "network now $kind")
                    network = kind
                    appContext?.let(::apply)
                    notifyListeners()
                }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        callback = cb
    }

    private fun unwatchNetwork(context: Context) {
        val cb = callback ?: return
        callback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
    }

    private fun kindOf(caps: NetworkCapabilities?): NetworkKind = when {
        caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkKind.NONE
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkKind.UNMETERED
        else -> NetworkKind.METERED
    }

    private fun notifyListeners() {
        for (l in listeners.toList()) l()
    }
}
