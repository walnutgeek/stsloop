package com.walnutgeek.stsloop.audio.hub

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.walnutgeek.stsloop.core.hub.Cancellable
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.hub.HubLink
import com.walnutgeek.stsloop.core.hub.HubSocket
import com.walnutgeek.stsloop.core.hub.HubStatus
import com.walnutgeek.stsloop.core.hub.HubStore
import com.walnutgeek.stsloop.core.hub.HubTransport
import com.walnutgeek.stsloop.core.hub.Pairing
import com.walnutgeek.stsloop.core.hub.Scheduler
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's connection to its paired hub, on Android: a [HubLink] over
 * OkHttp, with its timers on one background thread, and the default network
 * watched so a change of network reconnects at once, without user action.
 * Two networks are watched: the default one, and the best one that is not a
 * VPN, because with Tailscale up the default network is the VPN, which stays
 * put while Wi-Fi hands over to mobile data underneath it.
 * Each snapshot of Descriptors is cached in [store], on the timer thread so
 * no disk write happens under the link's lock, before [onDescriptors] hears of it.
 *
 * A Session holds one from start to end ([start], [stop]); the pairing screen
 * holds one to fetch the first Descriptors. [online] and [offline] read
 * volatiles, so the loop can ask on any thread without waiting. Construct it
 * off the main thread: it reads the stored pairing.
 */
class HubConnection(
    context: Context,
    private val store: HubStore,
    /** Who holds it, for the log ("Session 5e55…", "pairing"). */
    private val owner: String,
    private val onStatus: (HubStatus) -> Unit = {},
    private val onDescriptors: (Descriptors) -> Unit = {},
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "hub-$owner").apply { isDaemon = true }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(HubLink.WELCOME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // A ping unanswered for one interval fails the socket: how a connection on a dead network is noticed.
        .pingInterval(PING_S, TimeUnit.SECONDS)
        .build()

    /** The stored pairing; null when there is none, or it cannot be read (logged). */
    val pairing: Pairing? = try {
        store.pairing()
    } catch (e: Exception) {
        Log.e(TAG, "$owner: cannot read the hub pairing; not connecting", e)
        null
    }

    private val link = HubLink(pairing, OkHttpTransport(client), ExecutorScheduler(timers), object : HubLink.Listener {
        override fun status(status: HubStatus) {
            Log.i(TAG, "$owner: hub ${describe(status)}")
            onStatus(status)
        }

        override fun descriptors(descriptors: Descriptors) {
            Log.i(TAG, "$owner: hub Buckets ${descriptors.buckets.joinToString { "${it.name} (${it.id})" }}")
            // One thread, so snapshots are cached and announced in the order they came.
            runCatching {
                timers.execute {
                    try {
                        store.saveDescriptors(descriptors)
                    } catch (e: Exception) {
                        Log.e(TAG, "$owner: cannot cache the hub's Descriptors", e)
                    }
                    onDescriptors(descriptors)
                }
            }
        }

        override fun ignored(why: String) {
            Log.w(TAG, "$owner: ignored $why from the hub")
        }
    })

    val status: HubStatus get() = link.status
    val online: Boolean get() = link.online

    /** Whether the loop should say "hub offline" ([HubLink.offline]). */
    val offline: Boolean get() = link.offline

    /** Calls [HubLink.networkChanged] when the network it is told about changes; one per watched network. */
    private inner class Watch(private val which: String) : ConnectivityManager.NetworkCallback() {
        private var current: Network? = null
        private var lost = false

        override fun onAvailable(network: Network) {
            // The first call reports the network we started on; only a change is news.
            val changed = lost || (current != null && current != network)
            current = network
            lost = false
            if (changed) {
                Log.i(TAG, "$owner: the $which network changed; reconnecting to the hub")
                link.networkChanged()
            }
        }

        override fun onLost(network: Network) {
            if (network == current) lost = true
        }
    }
    private val watches = mutableListOf<ConnectivityManager.NetworkCallback>()

    fun start() {
        link.start()
        if (pairing == null || watches.isNotEmpty()) return
        val underlying = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val handler = Handler(Looper.getMainLooper())
        try {
            Watch("default").also { connectivity.registerDefaultNetworkCallback(it, handler); watches += it }
            Watch("underlying").also { connectivity.registerBestMatchingNetworkCallback(underlying, it, handler); watches += it }
        } catch (e: RuntimeException) {
            Log.e(TAG, "$owner: cannot watch the network; reconnects wait for the retry timer", e)
        }
    }

    /** Closes the connection and releases its threads; it cannot be started again. */
    fun stop() {
        for (w in watches) runCatching { connectivity.unregisterNetworkCallback(w) }
        watches.clear()
        link.stop()
        timers.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private class ExecutorScheduler(private val executor: ScheduledExecutorService) : Scheduler {
        override fun schedule(delayMs: Long, task: Runnable): Cancellable = try {
            val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            Cancellable { future.cancel(false) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Cancellable {} // stopped: nothing more runs
        }
    }

    /** One OkHttp WebSocket per [open]; only its first ending (close or failure) reaches the link. */
    private class OkHttpTransport(private val client: OkHttpClient) : HubTransport {
        override fun open(url: String, token: String, events: HubSocket.Events): HubSocket {
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            val ended = AtomicBoolean()
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = events.opened()

                override fun onMessage(webSocket: WebSocket, text: String) = events.received(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    if (ended.compareAndSet(false, true)) events.closed(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (ended.compareAndSet(false, true)) events.closed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // A failed upgrade (401) comes here, with the HTTP response.
                    if (ended.compareAndSet(false, true)) events.failed(response?.code, t)
                }
            })
            return object : HubSocket {
                override fun send(text: String) = ws.send(text)
                override fun close(code: Int, reason: String) {
                    ended.set(true)
                    if (!ws.close(code, reason)) ws.cancel()
                }
            }
        }
    }

    companion object {
        private const val TAG = "stsloop.Hub"
        private const val PING_S = 15L

        /** One line for the log and the screen. */
        fun describe(status: HubStatus): String = when (status) {
            HubStatus.Unpaired -> "not paired"
            HubStatus.Connecting -> "connecting"
            is HubStatus.Online -> "online (protocol ${status.welcome.protocol.major}.${status.welcome.protocol.minor})"
            is HubStatus.Offline -> "offline: ${status.why}"
            is HubStatus.Refused -> "refused: ${status.refusal.message}"
            HubStatus.Unauthorised -> "refused the token; pair again"
        }
    }
}
