package com.walnutgeek.stsloop.app

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.text.format.DateUtils
import android.util.Log
import android.widget.ProgressBar
import android.widget.Toast
import com.walnutgeek.stsloop.audio.SessionService
import com.walnutgeek.stsloop.audio.hub.HubConnection
import com.walnutgeek.stsloop.core.hub.HubFailure
import com.walnutgeek.stsloop.core.hub.HubProbe
import com.walnutgeek.stsloop.core.hub.HubReach
import com.walnutgeek.stsloop.core.hub.HubStore
import java.util.concurrent.Executors

/**
 * The reconnect nudge (#74): a dialog saying why the phone could not use its
 * hub ([HubFailure.text]), with **Retry** and **Ignore**. Shown after a
 * pairing whose first connection fails ([show]), and when the app or the
 * settings screen opens while the last try failed ([check]), never while a
 * Session runs: a Session says "can't reach the hub." instead.
 *
 * - **Retry** tries the hub at once, with progress. Success closes the dialog
 *   ("connected, N agents."); a failure shows its own words and stays open.
 * - **Ignore** is kept in [HubStore.reach]: no nudge until the failure kind
 *   changes, a connection succeeds once, or the hub is paired again.
 * - Back closes it for this failure only, until the app's process ends.
 *
 * UI thread only, but for [check]'s store read, which runs on a background thread.
 */
class HubNudge(private val activity: Activity) {
    private val store = HubStore(activity.filesDir)

    /** Called on the UI thread when a Retry or an Ignore has changed what is stored. */
    var onChanged: () -> Unit = {}

    private var dialog: AlertDialog? = null
    private var progress: ProgressBar? = null

    /** The connection a Retry is trying; null when none is. */
    private var trying: HubConnection? = null

    /** On opening a screen: nudges if the last try failed, the person has not ignored it, and no Session runs. */
    fun check() = io.execute {
        val paired = try {
            store.pairing() != null
        } catch (e: Exception) {
            Log.e(TAG, "cannot read the hub pairing", e)
            false
        }
        val reach = store.reach()
        if (!paired || !reach.nudge || reach.failedAtMs == dismissedFor) return@execute
        activity.runOnUiThread { if (!SessionService.isActive) open(reach.failure!!, reach.failedAtMs) }
    }

    /** Nudges about [failure] now: a fresh pairing's first connection failed. */
    fun show(failure: HubFailure) {
        if (!SessionService.isActive) open(failure, null)
    }

    /** Closes the dialog and stops a Retry; call when the screen goes away. */
    fun pause() {
        stopTrying()
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
    }

    private fun open(failure: HubFailure, failedAtMs: Long?) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (dialog != null) return run { dialog?.setMessage(failure.text) }
        val bar = ProgressBar(activity).apply {
            isIndeterminate = true
            visibility = ProgressBar.GONE
            setPadding(48, 16, 48, 16)
        }
        progress = bar
        val d = AlertDialog.Builder(activity)
            .setTitle("Can't use the hub")
            .setMessage(failure.text)
            .setView(bar)
            .setPositiveButton("Retry", null) // set below, so a click does not close the dialog
            .setNegativeButton("Ignore", null)
            .setOnCancelListener { if (failedAtMs != null) dismissedFor = failedAtMs }
            .create()
        d.setOnDismissListener {
            stopTrying()
            if (dialog === d) dialog = null
        }
        d.show()
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { retry() }
        d.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { ignore() }
        dialog = d
    }

    private fun ignore() {
        val d = dialog ?: return
        stopTrying()
        io.execute {
            try {
                store.updateReach(HubReach::ignore)
            } catch (e: Exception) {
                Log.e(TAG, "cannot record the Ignore", e)
            }
            activity.runOnUiThread { onChanged() }
        }
        d.dismiss()
    }

    private fun retry() {
        val d = dialog ?: return
        if (trying != null) return
        if (SessionService.isActive) {
            d.setMessage("A Session is running and is trying the hub itself. Retry once it has ended.")
            return
        }
        busy(true)
        d.setMessage("Trying the hub…")
        io.execute {
            lateinit var connection: HubConnection
            val probe = HubProbe { outcome -> activity.runOnUiThread { tried(connection, outcome) } }
            // Off the UI thread: it reads the stored pairing.
            connection = HubConnection(activity, store, "retry", onStatus = probe::status, onDescriptors = probe::descriptors)
            activity.runOnUiThread {
                if (dialog !== d || connection.pairing == null) {
                    connection.stop()
                    if (connection.pairing == null) d.dismiss() // forgotten meanwhile: nothing to retry
                    return@runOnUiThread
                }
                trying = connection
                connection.start()
                // The link gives up within its connect and welcome timeouts; this only guards a hub that welcomes and never sends Descriptors.
                d.window?.decorView?.postDelayed({ tried(connection, HubProbe.Outcome.Failed(HubFailure.NoWelcome)) }, RETRY_LIMIT_MS)
            }
        }
    }

    /** UI thread: the Retry over [connection] ended with [outcome]. */
    private fun tried(connection: HubConnection, outcome: HubProbe.Outcome) {
        if (trying !== connection) return
        stopTrying()
        Log.i(TAG, "retry: ${outcome.said}")
        onChanged()
        when (outcome) {
            is HubProbe.Outcome.Connected -> {
                dialog?.dismiss()
                Toast.makeText(activity, outcome.said, Toast.LENGTH_LONG).show()
            }
            is HubProbe.Outcome.Failed -> {
                busy(false)
                dialog?.setMessage(outcome.said)
            }
        }
    }

    private fun stopTrying() {
        trying?.stop()
        trying = null
    }

    private fun busy(on: Boolean) {
        progress?.visibility = if (on) ProgressBar.VISIBLE else ProgressBar.GONE
        dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = !on
    }

    companion object {
        private const val TAG = "stsloop.Nudge"
        private const val RETRY_LIMIT_MS = 20_000L

        /** The failure (by when it happened) whose nudge was closed with Back: not shown again in this process. */
        @Volatile
        private var dismissedFor: Long? = null

        private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "stsloop-nudge").apply { isDaemon = true } }

        /** The settings screen's hub line for [reach]: connected, or the last failure and when. */
        fun line(activity: Activity, reach: HubReach): String = reach.line { at ->
            val flags = if (DateUtils.isToday(at)) DateUtils.FORMAT_SHOW_TIME
            else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
            DateUtils.formatDateTime(activity, at, flags)
        }
    }
}
