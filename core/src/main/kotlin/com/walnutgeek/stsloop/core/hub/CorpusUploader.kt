package com.walnutgeek.stsloop.core.hub

import java.io.IOException
import java.util.concurrent.Executor

/** The phone's Corpus, read-only, as the uploader sees it. Called from the uploader's one thread only. */
interface UploadSource {
    /** The directory names of every published Turn, oldest first. */
    fun turns(): List<String>

    /** Turn [name]'s `turn.json` and the length and SHA-256 of its Recording, read from disk now. */
    @Throws(IOException::class)
    fun offer(name: String): TurnOffer

    /** [length] bytes of Turn [name]'s Recording from [offset]. */
    @Throws(IOException::class)
    fun audio(name: String, offset: Long, length: Int): ByteArray
}

/** Which Turns the paired hub has acknowledged as stored, so they are never offered again. */
interface UploadLedger {
    fun uploaded(): Set<String>

    @Throws(IOException::class)
    fun add(name: String)
}

/**
 * Uploads the phone's Corpus to the hub, new and backlog Turns alike, over the
 * Session's [HubLink] (`protocol/README.md`, "Corpus upload"):
 *
 * - Once the link is online with the `corpus` capability, every Turn not in
 *   the [ledger] is offered, oldest first, one at a time; a Turn [published]
 *   meanwhile joins the end of the queue. A Turn the hub answers `stored` goes
 *   in the ledger, whether its Recording was just sent or the hub already
 *   held it, so a re-upload is a no-op on both sides.
 * - The Recording goes up in pieces of [pieceBytes], each sent only once the
 *   hub has acknowledged the last, from the offset the hub names. So at most
 *   one piece is ever queued on the socket, and a live message waits behind no
 *   more than one: upload never competes with turn-taking for the connection.
 * - A Turn rejected, answered with a status the phone does not know, or that
 *   cannot be read is skipped until the next Session (the next uploader). A
 *   lost connection drops the Turn on offer; it is offered again, from the
 *   start, on the next welcome.
 *
 * Every call only posts to [executor] and returns: all reading, hashing and
 * sending happens there (one background thread on the phone), never on the
 * caller's thread, which may be the link's or the loop's. The uploader only
 * reads the Corpus; it never changes a Turn.
 */
class CorpusUploader(
    private val source: UploadSource,
    private val ledger: UploadLedger,
    private val executor: Executor,
    /** Sends on the link; false when it is not online ([HubLink.send]). */
    private val send: (Message) -> Boolean,
    private val pieceBytes: Int = PIECE_BYTES,
    /** One line per event worth a log line. */
    private val log: (String) -> Unit = {},
) {
    init {
        require(pieceBytes > 0)
    }

    // Executor thread only, from here on.
    private var active = false
    private var current: TurnOffer? = null
    private val skipped = HashSet<String>()
    private var done: MutableSet<String>? = null
    private var queue = ArrayDeque<String>()
    private var rescan = true

    /** Turns stored on the hub by this uploader; read from any thread, for the log. */
    @Volatile
    var stored = 0
        private set

    /** The link's status changed ([HubLink.Listener.status]). */
    fun status(status: HubStatus) = post {
        current = null
        active = status is HubStatus.Online && Protocol.CORPUS in status.welcome.capabilities
        rescan = true
        next()
    }

    /** A message from the hub ([HubLink.Listener.turnAck]); all but [TurnAck] are ignored. */
    fun received(message: Message) {
        if (message is TurnAck) post { answered(message) }
    }

    /** A Turn was just published to the Corpus. */
    fun published() = post {
        rescan = true
        next()
    }

    private fun post(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Stopped: the Session is over, and the next one starts a new uploader.
        }
    }

    private fun answered(ack: TurnAck) {
        val offer = current
        if (!active || offer == null || ack.turn != offer.turn) {
            log("ignored a turn_ack for ${ack.turn}: not the Turn on offer")
            return
        }
        when (ack.status) {
            TurnAck.SEND -> sendPiece(offer, ack.offset ?: -1)
            TurnAck.STORED -> {
                current = null
                stored++
                done().add(offer.turn)
                try {
                    ledger.add(offer.turn)
                } catch (e: IOException) {
                    log("cannot record ${offer.turn} as uploaded (it will be offered again): $e")
                }
                next()
            }
            else -> skip(offer.turn, "the hub answered ${ack.status}${ack.reason?.let { " ($it)" } ?: ""}${ack.message?.let { ": $it" } ?: ""}")
        }
    }

    private fun sendPiece(offer: TurnOffer, offset: Long) {
        if (offset < 0 || offset > offer.bytes) return skip(offer.turn, "the hub asked for offset $offset of ${offer.bytes} bytes")
        val length = minOf(pieceBytes.toLong(), offer.bytes - offset).toInt()
        val bytes = try {
            source.audio(offer.turn, offset, length)
        } catch (e: IOException) {
            return skip(offer.turn, "cannot read its Recording: $e")
        }
        if (!send(TurnAudio.of(offer.turn, offset, bytes, last = offset + length == offer.bytes))) lost()
    }

    private fun skip(turn: String, why: String) {
        log("skipped Turn $turn until the next Session: $why")
        skipped += turn
        current = null
        next()
    }

    private fun lost() {
        // The socket refused the frame because it is closing or failed; OkHttp always reports that
        // ending, so the link's status change follows and the next welcome starts over.
        active = false
        current = null
    }

    private fun done(): MutableSet<String> = done ?: ledger.uploaded().toHashSet().also { done = it }

    /** Offers the next Turn, unless one is on offer or the hub is not there. */
    private fun next() {
        while (active && current == null) {
            val name = nextName() ?: return
            val offer = try {
                source.offer(name)
            } catch (e: IOException) {
                log("skipped Turn $name until the next Session: cannot read it: $e")
                skipped += name
                continue
            }
            if (!send(offer)) return lost()
            current = offer
        }
    }

    private fun nextName(): String? {
        if (queue.isEmpty() && rescan) {
            rescan = false
            val done = done()
            queue = ArrayDeque(source.turns().filter { it !in done && it !in skipped })
        }
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name !in done() && name !in skipped) return name
        }
        return null
    }

    companion object {
        /**
         * The Recording's piece size: 32 KiB, about 44 KB as a base64 frame, about
         * a second of 16 kHz audio. A live message queued behind one waits for it to
         * go out, about 0.35 s at 1 Mbit/s uplink.
         */
        const val PIECE_BYTES = 32 * 1024
    }
}
