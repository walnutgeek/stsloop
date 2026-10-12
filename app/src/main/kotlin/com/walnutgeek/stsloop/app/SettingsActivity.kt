package com.walnutgeek.stsloop.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.walnutgeek.stsloop.audio.SessionService
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.hub.HubAliases
import com.walnutgeek.stsloop.core.hub.HubStore
import com.walnutgeek.stsloop.core.settings.AliasText
import com.walnutgeek.stsloop.core.settings.BucketSettings
import com.walnutgeek.stsloop.core.settings.Edit
import com.walnutgeek.stsloop.core.settings.Seconds
import com.walnutgeek.stsloop.core.settings.TimingsSettings
import com.walnutgeek.stsloop.core.turn.Timings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The settings screen (`docs/mvp.md`, "The settings screen"): trailing
 * silence and the focus timeout, hub pairing, and every Bucket with its
 * aliases. It edits the same files adb can write (`files/timings.json`,
 * `files/buckets.json`, `files/hub/aliases.json`), which a Session reads at
 * its start, so every change applies from the next Session.
 *
 * Each save re-reads its file on a background thread and merges the one
 * change into it (`:core`'s [TimingsSettings] and [BucketSettings]), so a file
 * pushed over adb meanwhile is not overwritten wholesale.
 */
class SettingsActivity : Activity() {
    private lateinit var hub: HubPairingPane
    private lateinit var trailingSilence: EditText
    private lateinit var focusTimeout: EditText
    private lateinit var timingsNote: TextView
    private lateinit var bucketList: LinearLayout
    private lateinit var bucketsNote: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trailingSilence = secondsField()
        focusTimeout = secondsField()
        timingsNote = note()
        bucketList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bucketsNote = note()
        hub = HubPairingPane(this).apply { onChanged = ::loadBuckets }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading("Settings", 24f))
            addView(text("Changes apply from the next Session.", 14f))
            addView(heading("Timings"))
            addView(text("Trailing silence (seconds): the pause that ends what you say", 14f))
            addView(trailingSilence)
            addView(text("Focus timeout (seconds): how long a named Bucket keeps Focus before the Inbox", 14f))
            addView(focusTimeout)
            addView(button("Save timings", ::saveTimings))
            addView(timingsNote)
            addView(heading("Hub"))
            addView(hub.view)
            addView(heading("Buckets"))
            addView(text("Aliases are what else a Bucket is heard by, separated by commas. Its name always counts.", 14f))
            addView(bucketList)
            addView(bucketsNote)
        }
        setContentView(ScrollView(this).apply {
            addView(content)
            // targetSdk 35+ is edge-to-edge: keep content clear of the system bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
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

    /** Debug builds only: pair over adb with the QR code's text, as a scan would (forwarded by [MainActivity]). */
    private fun pairFromIntent(intent: Intent?) {
        val text = intent?.getStringExtra(EXTRA_PAIRING) ?: return
        intent.removeExtra(EXTRA_PAIRING)
        if (SessionService.testModeAllowed(this)) hub.paired(text) else Log.w(TAG, "ignoring a pairing extra: debug builds only")
    }

    override fun onResume() {
        super.onResume()
        loadTimings()
        hub.refresh()
        loadBuckets()
    }

    override fun onPause() {
        hub.pause()
        super.onPause()
    }

    // --- timings ---

    private fun loadTimings() = io.execute {
        val s = TimingsSettings(read(Timings.FILE))
        runOnUiThread {
            trailingSilence.setText(Seconds.format(s.timings.trailingSilenceMs))
            focusTimeout.setText(Seconds.format(s.timings.focusTimeoutMs))
            timingsNote.text = ignored(Timings.FILE, s.rejected)
        }
    }

    private fun saveTimings() {
        val silence = Seconds.parse(trailingSilence.text.toString())
        val timeout = Seconds.parse(focusTimeout.text.toString())
        if (silence == null || timeout == null) {
            timingsNote.text = "Type each as a number of seconds, such as 1.5."
            return
        }
        io.execute {
            val s = TimingsSettings(read(Timings.FILE))
            val outcome = apply(s.edit(trailingSilenceMs = silence, focusTimeoutMs = timeout), "Saved. The next Session uses them.")
            runOnUiThread { timingsNote.text = outcome.message }
        }
    }

    // --- Buckets ---

    private fun loadBuckets() = io.execute {
        val s = buckets()
        runOnUiThread {
            bucketList.removeAllViews()
            for (row in s.rows) bucketList.addView(BucketRow(row).view)
            bucketsNote.text = ignored("the Bucket files", s.rejected)
        }
    }

    /** Background thread: the Buckets as the next Session would hear them. */
    private fun buckets(): BucketSettings {
        val snapshot = try {
            HubStore(filesDir).descriptors()
        } catch (e: Exception) {
            Log.e(TAG, "cannot read the cached hub Descriptors", e)
            null
        }
        return BucketSettings(read(BucketConfig.FILE), read(HubAliases.FILE), snapshot)
    }

    /** One Bucket: its name, its aliases to edit, and a place for its recognisability result. */
    private inner class BucketRow(private val row: BucketSettings.Row) {
        private val aliases = EditText(this@SettingsActivity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(AliasText.format(row.aliases))
        }
        private val status = note()

        /** The name-recognisability check's result (#49); empty and hidden until that check exists. */
        private val recognisability = note().apply { visibility = TextView.GONE }

        val view = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
            addView(text(if (row.hubId == null) row.name else "${row.name}  (hub)", 18f))
            addView(recognisability)
            addView(aliases)
            addView(button("Save aliases", ::save))
            addView(status)
        }

        private fun save() {
            val wanted = AliasText.parse(aliases.text.toString())
            io.execute {
                // Re-read, so an edit to another Bucket, or a file pushed over adb, is kept.
                val s = buckets()
                val current = s.rows.firstOrNull { it.name == row.name && it.hubId == row.hubId }
                val outcome = if (current == null) {
                    Outcome(false, "${row.name} is gone; reopen the screen.")
                } else {
                    apply(s.edit(current, wanted), "Saved. The next Session hears it by these.")
                }
                runOnUiThread {
                    status.text = outcome.message
                    if (outcome.saved) aliases.setText(AliasText.format(wanted))
                }
            }
        }
    }

    // --- files ---

    /** Whether an edit was [saved], and what to show for it. */
    private class Outcome(val saved: Boolean, val message: String)

    /** Background thread: writes an [Edit.Save], or says why not. */
    private fun apply(edit: Edit, saved: String): Outcome = when (edit) {
        is Edit.Refused -> Outcome(false, edit.problem)
        is Edit.Save -> try {
            write(edit.file, edit.text)
            Outcome(true, saved)
        } catch (e: IOException) {
            Log.e(TAG, "cannot write ${edit.file}", e)
            Outcome(false, "Could not save: ${e.message}")
        }
    }

    /** The text of `files/<name>`, or null when there is none or it cannot be read (a Session then uses the defaults too). */
    private fun read(name: String): String? {
        val file = File(filesDir, name)
        if (!file.exists()) return null
        return try {
            file.readText()
        } catch (e: IOException) {
            Log.e(TAG, "cannot read $file", e)
            null
        }
    }

    /** Replaces `files/<name>` atomically, so a Session starting meanwhile reads the old file or the new, never half of one. */
    private fun write(name: String, text: String) {
        val file = File(filesDir, name)
        val dir = file.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val tmp = File(dir, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("cannot replace $file")
        Log.i(TAG, "saved $file")
    }

    private fun ignored(what: String, rejected: List<String>): String =
        if (rejected.isEmpty()) "" else "A Session ignores ${rejected.size} thing(s) in $what:\n" + rejected.joinToString("\n") { "• $it" }

    // --- views ---

    private fun secondsField() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun heading(s: String, size: Float = 20f) = text(s, size).apply { setPadding(0, 40, 0, 8) }

    private fun text(s: String, size: Float) = TextView(this).apply { text = s; textSize = size }

    private fun note() = TextView(this).apply { textSize = 13f; alpha = 0.7f }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        isAllCaps = false
        text = label
        setOnClickListener { onClick() }
    }

    companion object {
        private const val TAG = "stsloop.Settings"
        const val EXTRA_PAIRING = "pairing"

        // Config reads and writes, off the main thread; one for the process, so saves run in order.
        private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "stsloop-settings").apply { isDaemon = true } }
    }
}
