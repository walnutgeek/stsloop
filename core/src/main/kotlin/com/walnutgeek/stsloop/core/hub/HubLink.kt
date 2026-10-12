package com.walnutgeek.stsloop.core.hub

/**
 * Opens WebSockets. The phone's is OkHttp; tests use a fake.
 *
 * [open] returns at once and never calls [events] from inside itself: every
 * event arrives later, on any thread.
 */
fun interface HubTransport {
    /** Connects to [url] with the header `Authorization: Bearer [token]`. */
    fun open(url: String, token: String, events: HubSocket.Events): HubSocket
}

/** One WebSocket to the hub. */
interface HubSocket {
    /** Queues one text frame; false if the socket is closing or closed. */
    fun send(text: String): Boolean

    fun close(code: Int, reason: String)

    interface Events {
        fun opened()
        fun received(text: String)

        /** The hub closed the connection with [code]. */
        fun closed(code: Int, reason: String)

        /**
         * The connection failed or broke. [httpStatus] is the status that
         * answered the upgrade instead of a WebSocket (401 for a wrong token),
         * null when there was no HTTP answer.
         */
        fun failed(httpStatus: Int?, error: Throwable?)
    }
}

/** Runs a task later, on some thread. */
fun interface Scheduler {
    fun schedule(delayMs: Long, task: Runnable): Cancellable
}

fun interface Cancellable {
    fun cancel()
}

/** Where the phone's connection to the hub stands. */
sealed interface HubStatus {
    /** No hub has been paired. */
    data object Unpaired : HubStatus

    /** Connecting, or connected and waiting for the welcome. */
    data object Connecting : HubStatus

    /** Welcomed: the hub's Descriptors arrive as it sends them. */
    data class Online(val welcome: Welcome) : HubStatus

    /** Not connected; [why] for the log. A retry is pending unless the link is stopped. */
    data class Offline(val why: String) : HubStatus

    /** The hub refused the handshake; nothing is retried until the next [HubLink.start]. */
    data class Refused(val refusal: Refusal) : HubStatus

    /** The hub answered 401: the token is wrong, and the phone must be paired again. Not retried. */
    data object Unauthorised : HubStatus
}

/**
 * The phone's connection to its paired hub, for as long as its holder needs it: a Session, or the pairing screen (`protocol/README.md`,
 * "The wire, v1"). [start] connects; once welcomed, every `descriptors`
 * snapshot goes to the [Listener]. The link never holds up the loop: the loop
 * only reads [status] (or [online]), which is a volatile field.
 *
 * - On open it sends [Protocol.HELLO] and waits [WELCOME_TIMEOUT_MS] for the
 *   `welcome`. Messages it does not know, broken frames, and `descriptors`
 *   before the welcome are ignored.
 * - A connection that is lost, fails, or is not welcomed in time is retried,
 *   after [FIRST_RETRY_MS], doubling to [MAX_RETRY_MS]; a welcome resets the
 *   delay. [networkChanged] retries at once, and replaces a live connection,
 *   which may be bound to a network that is gone: the hub keeps the newest.
 * - A `refusal`, a `welcome` in another major (the phone speaks only its
 *   own, ADR-0006), or a 401 for the token, is not retried until the next
 *   [start]: none fixes itself while a Session runs.
 *
 * Thread-safe: transport events, timers and calls may come from any thread.
 * The [Listener] is called with the link's lock held, so it must return
 * quickly and must not wait on another thread that calls the link.
 */
class HubLink(
    private val pairing: Pairing?,
    private val transport: HubTransport,
    private val scheduler: Scheduler,
    private val listener: Listener = object : Listener {},
) {
    interface Listener {
        fun status(status: HubStatus) {}

        /** A snapshot of every hub Bucket; it replaces the last one. */
        fun descriptors(descriptors: Descriptors) {}

        /** A frame was ignored, and why; for the log. */
        fun ignored(why: String) {}
    }

    @Volatile
    var status: HubStatus = if (pairing == null) HubStatus.Unpaired else HubStatus.Offline("not started")
        private set

    /** Whether the hub is connected and has welcomed the phone. */
    val online: Boolean get() = status is HubStatus.Online

    /** Whether a connection has failed, or been refused, since [start] or the last welcome. */
    @Volatile
    private var failed = true

    /**
     * Whether the loop should say "hub offline": the link is not [online],
     * and a connection has failed since [start] or the last welcome. The first
     * try of a Session, and a reconnect after a network change while online,
     * do not count: the hub is most likely seconds from welcoming the phone.
     */
    val offline: Boolean get() = failed && !online

    private var started = false

    /** Bumped for every connection and by [stop]; events and timers of an older one are ignored. */
    private var attempt = 0
    private var socket: HubSocket? = null
    private var timer: Cancellable? = null
    private var retryMs = FIRST_RETRY_MS

    @Synchronized
    fun start() {
        if (started) return
        started = true
        retryMs = FIRST_RETRY_MS
        failed = pairing == null
        if (pairing == null) set(HubStatus.Unpaired) else connect()
    }

    /** Closes the connection; nothing is retried until [start]. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        attempt++
        cancelTimer()
        socket?.close(NORMAL, "session ended")
        socket = null
        if (pairing != null) set(HubStatus.Offline("stopped"))
    }

    /** The phone's network changed: whatever the connection was bound to may be gone. */
    @Synchronized
    fun networkChanged() {
        if (!started || pairing == null) return
        if (status is HubStatus.Refused || status is HubStatus.Unauthorised) return
        retryMs = FIRST_RETRY_MS
        connect()
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    private fun connect() {
        val pairing = checkNotNull(pairing)
        cancelTimer()
        socket?.close(NORMAL, "reconnecting")
        val id = ++attempt
        set(HubStatus.Connecting)
        socket = transport.open(pairing.url, pairing.token, Events(id))
        timer = scheduler.schedule(WELCOME_TIMEOUT_MS) { welcomeTimedOut(id) }
    }

    @Synchronized
    private fun welcomeTimedOut(id: Int) {
        if (id != attempt || status != HubStatus.Connecting) return
        socket?.close(NORMAL, "no welcome")
        socket = null
        lost("no welcome in ${WELCOME_TIMEOUT_MS / 1000} s")
    }

    private fun lost(why: String) {
        cancelTimer()
        set(HubStatus.Offline(why))
        // Retires the lost connection: its late events (a close after a failure, say) are stale.
        val id = ++attempt
        val delay = retryMs
        retryMs = minOf(retryMs * 2, MAX_RETRY_MS)
        timer = scheduler.schedule(delay) { retry(id) }
    }

    @Synchronized
    private fun retry(id: Int) {
        if (started && id == attempt) connect()
    }

    private fun set(next: HubStatus) {
        when (next) {
            is HubStatus.Online -> failed = false
            is HubStatus.Offline, is HubStatus.Refused, HubStatus.Unauthorised, HubStatus.Unpaired -> failed = true
            HubStatus.Connecting -> Unit
        }
        if (next == status) return
        status = next
        listener.status(next)
    }

    private inner class Events(private val id: Int) : HubSocket.Events {
        private val current get() = started && id == attempt

        override fun opened(): Unit = synchronized(this@HubLink) {
            if (current) socket?.send(Protocol.encode(Protocol.HELLO))
        }

        override fun received(text: String): Unit = synchronized(this@HubLink) {
            if (!current) return
            val message = try {
                Protocol.decode(text)
            } catch (e: BadMessage) {
                listener.ignored("a broken ${e.messageType ?: "frame"}: ${e.message}")
                return
            }
            val waiting = status == HubStatus.Connecting
            when {
                message is Welcome && waiting && message.protocol.major == Protocol.VERSION.major -> {
                    cancelTimer()
                    retryMs = FIRST_RETRY_MS
                    set(HubStatus.Online(message))
                }
                message is Welcome && waiting -> {
                    cancelTimer()
                    socket?.close(NORMAL, "unsupported protocol")
                    socket = null
                    val major = message.protocol.major
                    set(HubStatus.Refused(Refusal(
                        "unsupported_protocol",
                        "The hub answered in protocol $major and the phone speaks ${Protocol.VERSION.major}. " +
                            if (major > Protocol.VERSION.major) "Update the phone app." else "Upgrade the hub.",
                    )))
                }
                message is Refusal && waiting -> {
                    cancelTimer()
                    set(HubStatus.Refused(message))
                }
                message is Descriptors && status is HubStatus.Online -> listener.descriptors(message)
                else -> listener.ignored(if (message == null) "a message of a type the phone does not know" else "an unexpected ${message::class.simpleName}")
            }
        }

        override fun closed(code: Int, reason: String): Unit = synchronized(this@HubLink) {
            if (!current) return
            socket = null
            if (status !is HubStatus.Refused) lost("closed $code $reason".trim())
        }

        override fun failed(httpStatus: Int?, error: Throwable?): Unit = synchronized(this@HubLink) {
            if (!current) return
            socket = null
            when {
                httpStatus == UNAUTHORISED -> {
                    cancelTimer()
                    set(HubStatus.Unauthorised)
                }
                status is HubStatus.Refused -> Unit
                else -> lost(listOfNotNull(httpStatus?.let { "HTTP $it" }, error?.toString()).joinToString(": ").ifEmpty { "failed" })
            }
        }
    }

    companion object {
        /** How long the phone waits, from opening a connection, for the hub's welcome. */
        const val WELCOME_TIMEOUT_MS = 10_000L
        const val FIRST_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 30_000L

        private const val NORMAL = 1000
        private const val UNAUTHORISED = 401
    }
}
