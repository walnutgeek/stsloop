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
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.walnutgeek.stsloop.audio.NameCheck
import com.walnutgeek.stsloop.audio.SessionService
import com.walnutgeek.stsloop.audio.speech.ModelDownloads
import com.walnutgeek.stsloop.core.models.DownloadStatus
import com.walnutgeek.stsloop.core.models.NetworkKind
import com.walnutgeek.stsloop.core.SessionScreen
import com.walnutgeek.stsloop.core.StartGate
import com.walnutgeek.stsloop.core.StartRefusal
import com.walnutgeek.stsloop.core.testmode.TestConfig
import com.walnutgeek.stsloop.core.testmode.TtsUsage
import com.walnutgeek.stsloop.core.testmode.next

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var settings: Button
    private lateinit var transcripts: TranscriptListPane
    private lateinit var corpusCount: TextView
    private lateinit var models: TextView
    private lateinit var modelAction: Button
    private var testMode: TestModePane? = null
    private lateinit var openSettings: Button
    private lateinit var nudge: HubNudge
    private val modelsChanged: () -> Unit = { refreshModels() }
    // The screen follows the Session's state (#70). When a capture ends its last Turn is
    // published, so the Corpus is re-listed too, in case the pane's watcher missed it (#68).
    private val sessionChanged: (Boolean) -> Unit = { captureEnded ->
        if (SessionService.isActive) nudge.pause() // never during a Session (#74)
        refresh()
        if (captureEnded) transcripts.load()
    }
    private var askedForNotifications = false // once per screen: a denial must not loop through onResume

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
        // A release build downloads its speech models on first run (ModelDownloads); this says how far.
        models = TextView(this).apply { textSize = 16f; setPadding(0, 16, 0, 0) }
        modelAction = Button(this).apply {
            isAllCaps = false
            setOnClickListener {
                if (ModelDownloads.waitingForWifi) ModelDownloads.allowMobileData(this@MainActivity)
                else ModelDownloads.retry(this@MainActivity)
            }
        }
        transcripts = TranscriptListPane(this, SessionService.corpusDir(this)).apply {
            onLoaded = { n -> corpusCount.text = "Corpus: $n Turn(s), newest first" }
        }
        corpusCount = TextView(this).apply { textSize = 16f; setPadding(0, 32, 0, 8) }
        // Timings, hub pairing and Bucket aliases (#48).
        openSettings = Button(this).apply {
            isAllCaps = false
            text = "Settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        // Why the hub could not be reached, on opening the app after a Session that could not (#74).
        nudge = HubNudge(this)
        // Bluetooth test mode (#27) is a debug-build experiment, never part of the product UI.
        if (SessionService.testModeAllowed(this)) testMode = TestModePane()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toggle)
            addView(settings)
            addView(status)
            addView(models)
            addView(modelAction)
            addView(openSettings)
            testMode?.let { addView(it.view) }
            addView(corpusCount)
            addView(transcripts.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            // targetSdk 35+ is edge-to-edge: keep content clear of the system bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left + 48, bars.top + 48, bars.right + 48, bars.bottom + 48)
                insets
            }
        })
        pairFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pairFromIntent(intent)
    }

    /**
     * Debug builds only: pair over adb with the QR code's text (`--es pairing '<json>'`), as a scan would.
     * Pairing lives on the settings screen, which is not exported, so the extra is forwarded there.
     */
    private fun pairFromIntent(intent: Intent?) {
        val text = intent?.getStringExtra(EXTRA_PAIRING) ?: return
        intent.removeExtra(EXTRA_PAIRING)
        if (SessionService.testModeAllowed(this)) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAIRING, text))
        } else {
            Log.w(TAG, "ignoring a pairing extra: debug builds only")
        }
    }

    override fun onResume() {
        super.onResume()
        NameCheck.request(this, "app opened")
        ModelDownloads.observe(modelsChanged)
        ModelDownloads.ensure(this)
        SessionService.observe(sessionChanged)
        refresh()
        transcripts.start()
        nudge.check()
    }

    override fun onPause() {
        ModelDownloads.unobserve(modelsChanged)
        SessionService.unobserve(sessionChanged)
        transcripts.stop()
        nudge.pause()
        super.onPause()
    }

    /** The visible action that starts a Session. RECORD_AUDIO is while-in-use, so it must be this. */
    private fun startSession() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            // Optional ("Nearby devices"): asked for so the car's hands-free route can be used. A Session
            // starts without it and falls back to the phone mic if the platform then refuses the route.
            add(Manifest.permission.BLUETOOTH_CONNECT)
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
            StartRefusal.MODELS -> {
                refresh()
                status.text = "A Session needs the speech models, and they are not downloaded yet. " +
                    "It can start once they are."
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
        // The screen follows: sessionChanged refreshes it once the service commits the Start.
    }

    /** The screen follows: sessionChanged refreshes it, and re-lists the Corpus once the Session has ended. */
    private fun stopSession() {
        SessionService.stop(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) launchSession()
    }

    /**
     * The download's progress shows in a notification once the screen is off (#67),
     * so ask for notifications when one starts. The download runs either way.
     */
    private fun askForDownloadNotifications() {
        if (askedForNotifications || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askedForNotifications = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun gate() = StartGate.check(
        micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        controlsVisible = SessionService.controlsVisible(this),
        modelsReady = ModelDownloads.locate(this) != null,
    )

    private fun refresh() {
        val screen = SessionScreen.of(SessionService.state)
        toggle.text = screen.toggle
        toggle.isEnabled = screen.toggleEnabled
        settings.visibility = if (SessionService.controlsVisible(this)) Button.GONE else Button.VISIBLE
        status.text = screen.status
        refreshModels()
        testMode?.refresh()
    }

    /** Hidden once the models are in place; until then, how far the download is, and its one action. */
    private fun refreshModels() {
        val s = ModelDownloads.status
        // While it runs, the screen stays on as long as this activity is visible. Set only on a
        // change: every set is a window update, and this runs on each progress report.
        val running = s is DownloadStatus.Running
        val keepOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (running != (window.attributes.flags and keepOn != 0)) {
            if (running) window.addFlags(keepOn) else window.clearFlags(keepOn)
        }
        if (running) askForDownloadNotifications()
        models.visibility = if (s == DownloadStatus.Ready) TextView.GONE else TextView.VISIBLE
        models.text = s.describe(ModelDownloads.network)
        val retry = s is DownloadStatus.Failed && ModelDownloads.network != NetworkKind.NONE
        modelAction.text = if (ModelDownloads.waitingForWifi) "Download now over mobile data" else "Retry"
        modelAction.visibility = if (ModelDownloads.waitingForWifi || retry) Button.VISIBLE else Button.GONE
    }

    /**
     * Debug-only controls for Bluetooth test mode: each tap cycles one setting
     * and rewrites `files/testmode.json`, which the next Session reads, so
     * switching configuration while parked is Stop, tap, Start. The file is
     * read and written on a background thread; taps before the first read
     * are ignored.
     */
    private inner class TestModePane {
        /** The config shown; null until the first read finishes. */
        var config: TestConfig? = null
            private set
        private val enabled = small { c -> c.copy(enabled = !c.enabled) }
        private val label = small { c -> c.copy(label = TestConfig.LABELS.next(c.label)) }
        private val mic = small { c -> c.withPreset(TestConfig.MIC_PRESETS.next(c.preset)) }
        private val interval = small { c -> c.copy(ttsIntervalMs = TestConfig.TTS_INTERVALS_MS.next(c.ttsIntervalMs)) }
        private val usage = small { c -> c.copy(ttsUsage = TtsUsage.entries.next(c.ttsUsage)) }
        private val note = TextView(this@MainActivity).apply { textSize = 13f; alpha = 0.7f }
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 0)
            for (v in listOf(enabled, label, mic, interval, usage, note)) addView(v)
        }

        private fun small(change: (TestConfig) -> TestConfig) = Button(this@MainActivity).apply {
            textSize = 14f
            isAllCaps = false
            setOnClickListener { config?.let { save(change(it)) } }
        }

        private fun save(next: TestConfig) {
            config = next
            show(next)
            io.execute {
                val error = runCatching { SessionService.saveTestConfig(this@MainActivity, next) }.exceptionOrNull()
                if (error != null) {
                    Log.e(TAG, "cannot write the test mode config", error)
                    runOnUiThread { note.text = "Could not save: ${error.message}" }
                }
            }
        }

        /** Re-reads the file (it may have been pushed over adb). */
        fun refresh() {
            io.execute {
                val read = SessionService.testConfig(this@MainActivity)
                runOnUiThread {
                    config = read
                    show(read)
                }
            }
        }

        private fun show(c: TestConfig) {
            enabled.text = if (c.enabled) "Test mode: ON" else "Test mode: off"
            for (b in listOf(label, mic, interval, usage)) b.visibility = if (c.enabled) Button.VISIBLE else Button.GONE
            label.text = "Condition: ${c.label.ifEmpty { "(none)" }}"
            mic.text = "Mic: ${c.preset}"
            interval.text = if (c.ttsOn) "TTS every ${c.ttsIntervalMs / 1000} s" else "TTS off"
            usage.text = "TTS usage: ${c.ttsUsage.json}"
            note.text = when {
                !c.enabled -> ""
                SessionService.isActive -> "Changes apply at the next Session start."
                else -> "Applies at the next Session start."
            }
        }
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        const val REQUEST_NOTIFICATIONS = 2
        // Test-mode config reads and writes, off the main thread; one for the process.
        val io: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "stsloop-testmode").apply { isDaemon = true } }
        const val TAG = "stsloop.Main"
        const val EXTRA_PAIRING = "pairing"
    }
}
