package com.walnutgeek.stsloop.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.walnutgeek.stsloop.audio.SessionService

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 18f }
        toggle = Button(this).apply {
            textSize = 24f
            setOnClickListener { if (SessionService.isActive) stopSession() else startSession() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toggle)
            addView(status)
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
        if (requestCode != REQUEST_PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            // POST_NOTIFICATIONS is optional: without it the Session still runs, the notification is just hidden.
            launchSession()
        } else {
            status.text = "Microphone permission is required to start a Session."
        }
    }

    private fun refresh() {
        toggle.text = if (SessionService.isActive) "Stop Session" else "Start Session"
        val turns = SessionService.corpusDir(this).list()?.sorted().orEmpty()
        status.text = buildString {
            append(if (SessionService.isActive) "Session active.\n\n" else "No Session.\n\n")
            append("Corpus: ${turns.size} Turn(s)")
            turns.lastOrNull()?.let { append("\nLatest: $it") }
        }
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        const val TAG = "stsloop.Main"
    }
}
