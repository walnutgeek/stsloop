package com.walnutgeek.stsloop.app

import android.app.Activity
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.walnutgeek.stsloop.audio.NameCheck
import com.walnutgeek.stsloop.audio.SessionService
import com.walnutgeek.stsloop.audio.speech.SayAloud
import com.walnutgeek.stsloop.audio.hub.HubConnection
import com.walnutgeek.stsloop.core.hub.BadMessage
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.hub.HubFailure
import com.walnutgeek.stsloop.core.hub.HubStore
import com.walnutgeek.stsloop.core.hub.Pairing
import com.walnutgeek.stsloop.core.hub.Protocol
import java.util.concurrent.Executors

/**
 * Hub pairing (`docs/mvp.md`, "Onboarding" step 3): scan the QR code that
 * `stsloop-hub init` prints, store the pairing ([HubStore]), and fetch the
 * hub's Descriptors once so its Buckets resolve from the first Session on,
 * hub or no hub. "Forget" drops the pairing and the cached Descriptors.
 *
 * A Session already running keeps the hub it started with; a new pairing
 * applies from the next Session, and is not tried from here meanwhile (the
 * hub keeps one connection, so a second would push the Session's off).
 *
 * Under the address, the hub line says whether the last try connected, or
 * why it failed and when ([HubReach]). A first connection that fails opens
 * the reconnect nudge ([HubNudge]).
 *
 * Store reads and writes run on a background thread; the screen is updated
 * on the UI thread.
 */
class HubPairingPane(private val activity: Activity, private val nudge: HubNudge) {
    private val store = HubStore(activity.filesDir)
    private val status = TextView(activity).apply { textSize = 16f; setPadding(0, 24, 0, 0) }
    private val pair = Button(activity).apply {
        isAllCaps = false
        setOnClickListener { scan() }
    }
    private val forget = Button(activity).apply {
        isAllCaps = false
        text = "Forget hub"
        setOnClickListener { io.execute { store.forget(); show(changed = true) } }
    }

    /** Called on the UI thread once the pairing or the cached Descriptors may have changed. */
    var onChanged: () -> Unit = {}
    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(status)
        addView(pair)
        addView(forget)
    }

    /** The connection fetching the first Descriptors after a pairing; UI thread only. */
    private var fetching: HubConnection? = null

    /** Shows what is stored. */
    fun refresh() = io.execute(::show)

    /** Stops a fetch still running; call when the screen goes away. */
    fun pause() {
        fetching?.stop()
        fetching = null
    }

    /**
     * Pairs from a pairing message as the QR code holds it. Also the debug
     * builds' adb path: `am start -n com.walnutgeek.stsloop/.app.MainActivity --es pairing '<json>'`,
     * which [MainActivity] forwards to the settings screen.
     */
    fun paired(text: String) {
        val pairing = try {
            Protocol.decode(text.trim()) as? Pairing ?: throw BadMessage("not a pairing")
        } catch (e: BadMessage) {
            Log.w(TAG, "not a hub pairing code: ${e.message}")
            say("That is not an stsloop hub pairing code. Run stsloop-hub init and scan the code it prints.")
            return
        }
        io.execute {
            try {
                store.pair(pairing)
            } catch (e: Exception) {
                Log.e(TAG, "cannot store the hub pairing", e)
                say("Could not save the pairing: ${e.message}")
                return@execute
            }
            Log.i(TAG, "paired with the hub at ${pairing.url}")
            if (SessionService.isActive) {
                show(note = "Paired with ${pairing.url}. The hub is used from the next Session.", changed = true)
                return@execute
            }
            lateinit var connection: HubConnection
            val checked = java.util.concurrent.atomic.AtomicBoolean()
            connection = HubConnection( // here, off the UI thread: it reads the pairing just stored
                activity, store, "pairing",
                onRecorded = { s -> s.failure?.let { f -> done(connection, "Paired with ${pairing.url}, but could not connect.", f) } },
                onDescriptors = { d ->
                    done(connection, "Paired. ${describe(d)}")
                    // Onboarding step 3 (docs/mvp.md): check the new names, then say the phone is ready.
                    if (!checked.compareAndSet(false, true)) return@HubConnection // a later snapshot of the same fetch
                    NameCheck.request(activity, "paired with the hub") {
                        if (!SessionService.isActive) SayAloud.once(activity, ready(d))
                    }
                },
            )
            activity.runOnUiThread { fetch(pairing, connection) }
        }
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let(::paired) ?: say("The QR code held no text.") }
            .addOnFailureListener { e ->
                Log.e(TAG, "the QR scanner failed", e)
                say("Could not scan: ${e.message}")
            }
    }

    /** UI thread: fetch the first Descriptors over [connection], made for [pairing]. */
    private fun fetch(pairing: Pairing, connection: HubConnection) {
        pause()
        say("Paired with ${pairing.url}. Connecting…")
        fetching = connection
        connection.start()
        status.postDelayed({
            if (fetching === connection) done(connection, "Paired with ${pairing.url}, but the hub is not reachable now. Its Buckets arrive when it is.")
        }, FETCH_TIMEOUT_MS)
    }

    /** Any thread: the fetch on [connection] is over; say [message], show what is stored, and nudge about a [failure]. */
    private fun done(connection: HubConnection, message: String, failure: HubFailure? = null) = activity.runOnUiThread {
        if (fetching !== connection) return@runOnUiThread
        fetching = null
        connection.stop()
        io.execute { show(note = message, changed = true) }
        failure?.let(nudge::show)
    }

    /** Background thread: reads the store and shows it, with [note] under it; [changed] tells [onChanged]. */
    private fun show(note: String? = null, changed: Boolean = false) {
        val (pairing, cached) = try {
            store.pairing() to store.descriptors()
        } catch (e: Exception) {
            Log.e(TAG, "cannot read the hub pairing", e)
            null to null
        }
        val text = when (pairing) {
            null -> "Hub: not paired."
            else -> "Hub: ${pairing.url}\n${HubNudge.line(activity, store.reach())}\n${cached?.let(::describe) ?: "No hub Buckets yet."}"
        }
        activity.runOnUiThread {
            status.text = if (note == null) text else "$text\n$note"
            pair.text = if (pairing == null) "Pair with hub (scan its QR code)" else "Pair again"
            forget.visibility = if (pairing == null) Button.GONE else Button.VISIBLE
            if (changed) onChanged()
        }
    }

    private fun say(message: String) = activity.runOnUiThread { status.text = message }

    /** "ready, 2 agents.": what the phone says once a pairing's Buckets are fetched and checked. */
    private fun ready(d: Descriptors): String = when (val n = d.buckets.count { it.type == "agent" }) {
        1 -> "ready, 1 agent."
        else -> "ready, $n agents."
    }

    private fun describe(d: Descriptors): String = when (d.buckets.size) {
        0 -> "The hub has no Buckets."
        1 -> "1 hub Bucket: ${d.buckets.single().name}."
        else -> "${d.buckets.size} hub Buckets: ${d.buckets.joinToString { it.name }}."
    }

    private companion object {
        const val TAG = "stsloop.Pairing"
        const val FETCH_TIMEOUT_MS = 20_000L // past the link's own connect and welcome timeouts
        val io = Executors.newSingleThreadExecutor { r -> Thread(r, "stsloop-pairing").apply { isDaemon = true } }
    }
}
