package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.AUDIO_FILE
import com.walnutgeek.stsloop.core.TURN_FILE
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonException
import com.walnutgeek.stsloop.core.parseTurnDirectoryName
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The Corpus directory on disk as an [UploadSource]: every published Turn
 * directory (`<started_at>-<id>`, holding `turn.json`). It only reads: a Turn
 * directory never changes once published, so reading it while the loop
 * publishes more is safe. `sessions/` (test-mode route logs) and anything else
 * that is not a Turn directory are left out. The Recording is the file its
 * `turn.json` names, resolved as `scripts/corpus_load.py` does: a plain file
 * name, else `audio.wav`.
 */
class CorpusDirectory(private val dir: File) : UploadSource {
    /** The last Turn offered and its Recording, so each piece does not re-read its `turn.json`. */
    private var last: Pair<String, File>? = null

    override fun turns(): List<String> =
        dir.listFiles { f -> f.isDirectory && parseTurnDirectoryName(f.name) != null && File(f, TURN_FILE).isFile }
            .orEmpty().map { it.name }.sorted()

    override fun offer(name: String): TurnOffer {
        val turnDir = turnDir(name)
        val text = File(turnDir, TURN_FILE).readBytes().toString(Charsets.UTF_8)
        val audio = audioFile(turnDir, text)
        last = name to audio
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        audio.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                bytes += n
            }
        }
        return TurnOffer(name, text, digest.digest().joinToString("") { "%02x".format(it) }, bytes)
    }

    override fun audio(name: String, offset: Long, length: Int): ByteArray {
        val file = last?.takeIf { it.first == name }?.second
            ?: turnDir(name).let { audioFile(it, File(it, TURN_FILE).readBytes().toString(Charsets.UTF_8)) }
        return RandomAccessFile(file, "r").use { raf ->
            ByteArray(length).also {
                raf.seek(offset)
                try {
                    raf.readFully(it)
                } catch (e: EOFException) {
                    throw IOException("$file is shorter than offset $offset + $length", e)
                }
            }
        }
    }

    private fun turnDir(name: String): File {
        if (parseTurnDirectoryName(name) == null) throw IOException("not a Turn directory name: $name")
        return File(dir, name)
    }

    private fun audioFile(turnDir: File, turnJson: String): File {
        val audio = try {
            Json.parseObject(turnJson)["audio"] as? Map<*, *>
        } catch (e: JsonException) {
            throw IOException("${File(turnDir, TURN_FILE)} is not JSON: ${e.message}", e)
        }
        val named = audio?.get("file") as? String
        val name = if (!named.isNullOrEmpty() && File(named).name == named && named != "..") named else AUDIO_FILE
        return File(turnDir, name)
    }
}

/**
 * [UploadLedger] as a text file, one Turn directory name per line, appended to
 * as the hub acknowledges each Turn. Losing a line costs only a re-offer, which
 * the hub answers `stored` at once, so appends are not synced, and a torn last
 * line is ignored on read.
 */
class UploadLedgerFile(private val file: File) : UploadLedger {
    override fun uploaded(): Set<String> {
        if (!file.isFile) return emptySet()
        // Only whole lines: the last one may have been cut short by a crash mid-append.
        return file.readText(Charsets.UTF_8).split('\n').dropLast(1).filter { parseTurnDirectoryName(it) != null }.toSet()
    }

    override fun add(name: String) {
        file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("cannot create $it") }
        FileOutputStream(file, true).use { it.write("$name\n".toByteArray(Charsets.UTF_8)) }
    }
}
