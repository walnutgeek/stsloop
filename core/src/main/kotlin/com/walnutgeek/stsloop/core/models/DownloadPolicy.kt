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
}
