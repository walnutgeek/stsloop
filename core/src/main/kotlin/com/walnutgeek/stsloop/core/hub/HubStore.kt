package com.walnutgeek.stsloop.core.hub

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.reflect.KClass
import kotlin.reflect.cast

/**
 * What the phone keeps about its hub, under `<files>/hub/`, so both survive
 * app restarts and hub outages:
 * - `pairing.json`: the [Pairing] scanned from the hub's QR code (address and token);
 * - `descriptors.json`: the last `descriptors` snapshot, the cache that lets
 *   hub Buckets resolve by name while the hub is offline;
 * - `uploaded.txt`: the Turns this hub has acknowledged as stored ([uploads]),
 *   so the Corpus upload never offers them again.
 *
 * Each file holds its protocol message as the hub sent it, re-encoded, and
 * is replaced atomically. Reads throw [BadMessage] for a file that is not
 * its message, and [IOException] when it cannot be read; a missing file is null.
 * Calls are not synchronised: the pairing is written by the app's screen and
 * the cache by a Session's link, and a write is one rename.
 */
class HubStore(files: File) {
    private val dir = File(files, DIR)
    private val pairingFile = File(dir, PAIRING)
    private val descriptorsFile = File(dir, DESCRIPTORS)
    private val uploadsFile = File(dir, UPLOADED)

    fun pairing(): Pairing? = read(pairingFile, Pairing::class)

    /**
     * Stores [pairing]. Pairing with a hub at another address drops the cached
     * Descriptors and the record of uploaded Turns, which are that other hub's;
     * a new token for the same hub keeps them.
     */
    fun pair(pairing: Pairing) {
        // An unreadable old pairing counts as another hub's: its cache cannot be trusted either.
        val previous = runCatching { pairing() }.getOrNull()
        if (previous?.url != pairing.url) {
            descriptorsFile.delete()
            uploadsFile.delete()
        }
        write(pairingFile, pairing)
    }

    /** Drops the pairing, the cached Descriptors and the record of uploaded Turns. */
    fun forget() {
        pairingFile.delete()
        descriptorsFile.delete()
        uploadsFile.delete()
    }

    /** The Turns the paired hub has acknowledged as stored. */
    fun uploads(): UploadLedger = UploadLedgerFile(uploadsFile)

    fun descriptors(): Descriptors? = read(descriptorsFile, Descriptors::class)

    fun saveDescriptors(descriptors: Descriptors) = write(descriptorsFile, descriptors)

    private fun <T : Message> read(file: File, expected: KClass<T>): T? {
        if (!file.exists()) return null
        val message = Protocol.decode(file.readText())
        if (!expected.isInstance(message)) throw BadMessage("$file does not hold a ${expected.simpleName} message")
        return expected.cast(message)
    }

    private fun write(file: File, message: Message) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val tmp = File(dir, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(Protocol.encode(message).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("cannot replace $file")
    }

    companion object {
        const val DIR = "hub"
        const val PAIRING = "pairing.json"
        const val DESCRIPTORS = "descriptors.json"
        const val UPLOADED = "uploaded.txt"
    }
}
