package com.walnutgeek.stsloop.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.walnutgeek.stsloop.audio.SessionService
import com.walnutgeek.stsloop.core.StartGate
import com.walnutgeek.stsloop.core.StartRefusal

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var settings: Button
    private lateinit var transcripts: TranscriptListPane
    private lateinit var corpusCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 18f }
        toggle = Button(this).apply {
            textSize = 24f
            setOnClickListener { if (SessionService.isActive) stopSession() else startSession() }
        }
        settings = Button(this).apply {
            text = "Enable notifications"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
        }
        transcripts = TranscriptListPane(this, SessionService.corpusDir(this)).apply {
            onLoaded = { n -> corpusCount.text = "Corpus: $n Turn(s), newest first" }
        }
        corpusCount = TextView(this).apply { textSize = 16f; setPadding(0, 32, 0, 8) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toggle)
            addView(settings)
            addView(status)
            addView(corpusCount)
            addView(transcripts.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            // targetSdk 35+ is edge-to-edge: keep content clear of the system bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left + 48, bars.top + 48, bars.right + 48, bars.bottom + 48)
                insets
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        transcripts.start()
    }

    override fun onPause() {
        transcripts.stop()
        super.onPause()
    }

    /** The visible action that starts a Session. RECORD_AUDIO is while-in-use, so it must be this. */
    private fun startSession() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            launchSession()
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun launchSession() {
        when (gate()) {
            StartRefusal.MICROPHONE -> {
                refresh()
                status.text = "Microphone permission is required to start a Session."
                return
            }
            StartRefusal.NOTIFICATIONS -> {
                // The notification is the only eyes-free way to stop or restart a Session,
                // and the only sign besides the mic indicator that one is running.
                refresh()
                status.text = "Notifications for stsloop are off. A Session is controlled from its " +
                    "notification, so it will not start without one."
                return
            }
            StartRefusal.NOT_ALLOWED, null -> Unit // NOT_ALLOWED comes only from the platform, below
        }
        try {
            SessionService.start(this)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: the app was not considered foreground.
            Log.e(TAG, "cannot start a Session", e)
            refresh()
            status.text = "Could not start a Session: ${e.message}"
            return
        }
        toggle.postDelayed(::refresh, 300)
    }

    private fun stopSession() {
        SessionService.stop(this)
        toggle.postDelayed(::refresh, 500)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) launchSession()
    }

    private fun gate() = StartGate.check(
        micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        controlsVisible = SessionService.controlsVisible(this),
    )

    private fun refresh() {
        toggle.text = if (SessionService.isActive) "Stop Session" else "Start Session"
        settings.visibility = if (SessionService.controlsVisible(this)) Button.GONE else Button.VISIBLE
        status.text = if (SessionService.isActive) "Session active." else "No Session."
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        const val TAG = "stsloop.Main"
    }
}
