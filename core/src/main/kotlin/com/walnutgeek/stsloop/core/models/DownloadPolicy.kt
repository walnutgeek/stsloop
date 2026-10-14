package com.walnutgeek.stsloop.core.models

import com.walnutgeek.stsloop.core.models.ModelStore.Companion.mb

/** The phone's default network, as the download policy sees it. */
enum class NetworkKind { NONE, UNMETERED, METERED }

/** Where the first-run model download stands, for the app's screen. */
sealed interface DownloadStatus {
    data object Ready : DownloadStatus

    /** Not started, or stopped for the network; [pendingBytes] still to fetch. */
    data class Waiting(val pendingBytes: Long) : DownloadStatus

    /** Installing; [progress] is null until the first report. */
    data class Running(val progress: InstallProgress?) : DownloadStatus

    data class Failed(val reason: ModelInstallException.Reason, val message: String, val pendingBytes: Long) : DownloadStatus

    /** One sentence for the screen, given the network now. */
    fun describe(network: NetworkKind): String = when (this) {
        Ready -> "Speech models ready."
        is Waiting -> "Speech models: ${mb(pendingBytes)} to download" + when (network) {
            NetworkKind.UNMETERED -> "."
            NetworkKind.METERED -> ", waiting for Wi-Fi."
            NetworkKind.NONE -> ", waiting for a network."
        }
        is Running -> when (progress?.phase) {
            null -> "Preparing the speech model download."
            InstallProgress.Phase.DOWNLOADING ->
                "Downloading speech models: ${progress.done / 1_000_000} of ${mb(progress.total)}."
            InstallProgress.Phase.VERIFYING -> "Checking the speech models."
            InstallProgress.Phase.UNPACKING ->
                "Unpacking speech models: ${progress.done / 1_000_000} of ${mb(progress.total)}."
        }
        is Failed -> when (reason) {
            ModelInstallException.Reason.NETWORK, ModelInstallException.Reason.CANCELLED ->
                "Speech model download stopped: $message. It resumes on Wi-Fi."
            else -> "Speech model download failed: $message. Tap Retry."
        }
    }
}

/**
 * When the first-run download runs: by itself on an unmetered network (Wi-Fi),
 * on mobile data only once the user has allowed it. A dropped connection
 * resumes by itself; a hash mismatch or a full disk waits for the user, so a
 * wrong pin cannot download 464 MB over and over (within one process: a
 * restart tries once more). With nothing left to fetch, only unpacking
 * remains, which needs no network at all.
 */
object DownloadPolicy {
    enum class Action {
        /** Start (or resume) the install. */
        START,

        /** Stop the running install; it resumes later from its partial download. */
        STOP,

        /** Not now: show that it is waiting for the network. */
        WAIT,

        /** Leave things as they are. */
        NOTHING,
    }

    /** [toFetch]: bytes still to download ([ModelStore.pendingDownloadBytes]). */
    fun next(status: DownloadStatus, network: NetworkKind, meteredAllowed: Boolean, toFetch: Long): Action {
        val allowed = toFetch == 0L || network == NetworkKind.UNMETERED ||
            (network == NetworkKind.METERED && meteredAllowed)
        return when (status) {
            DownloadStatus.Ready -> Action.NOTHING
            is DownloadStatus.Running -> if (allowed || network == NetworkKind.NONE) Action.NOTHING else Action.STOP
            is DownloadStatus.Waiting -> if (allowed) Action.START else Action.WAIT
            is DownloadStatus.Failed -> when (status.reason) {
                ModelInstallException.Reason.NETWORK, ModelInstallException.Reason.CANCELLED ->
                    if (allowed) Action.START else Action.WAIT
                else -> Action.NOTHING
            }
        }
    }

    /**
     * Whether the download needs the app kept in the foreground (a foreground
     * service), so that Android does not freeze it once the screen is off:
     * while it runs, and while a dropped connection waits for a retry the
     * network allows. Waiting for Wi-Fi or for the user holds nothing; the
     * next [next] that says [Action.START] holds it again.
     */
    fun holdsForeground(status: DownloadStatus, network: NetworkKind, meteredAllowed: Boolean, toFetch: Long): Boolean =
        when (status) {
            is DownloadStatus.Running -> true
            is DownloadStatus.Failed -> next(status, network, meteredAllowed, toFetch) == Action.START
            DownloadStatus.Ready, is DownloadStatus.Waiting -> false
        }
}

/**
 * What the download's notification shows while it holds the foreground: the
 * screen's sentence ([DownloadStatus.describe]) and a progress bar.
 */
data class DownloadNotice(val text: String, val bar: Bar) {
    sealed interface Bar {
        /** No bar: nothing is in progress (a retry is pending). */
        data object None : Bar

        /** An indeterminate bar: working, with nothing to measure yet. */
        data object Busy : Bar

        data class Percent(val value: Int) : Bar
    }

    companion object {
        /** Notification updates faster than this are dropped by the platform anyway. */
        const val MIN_INTERVAL_MS = 1_000L

        fun of(status: DownloadStatus, network: NetworkKind): DownloadNotice {
            val bar = when (status) {
                is DownloadStatus.Running -> {
                    val p = status.progress
                    if (p == null || p.phase == InstallProgress.Phase.VERIFYING || p.total <= 0) Bar.Busy
                    else Bar.Percent((p.done * 100 / p.total).toInt().coerceIn(0, 100))
                }
                else -> Bar.None
            }
            return DownloadNotice(status.describe(network), bar)
        }

        /** Whether to post [next], given what was posted ([previous]) [msSince] ago. */
        fun due(previous: DownloadNotice?, next: DownloadNotice, msSince: Long): Boolean = when {
            previous == null -> true
            previous == next -> false
            previous.bar::class != next.bar::class -> true
            else -> msSince >= MIN_INTERVAL_MS
        }
    }
}
