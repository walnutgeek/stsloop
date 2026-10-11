package com.walnutgeek.stsloop.audio

import com.walnutgeek.stsloop.core.AUDIO_FILE
import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.TURN_FILE
import com.walnutgeek.stsloop.core.Transcript
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnAudio
import com.walnutgeek.stsloop.core.TurnInProgress
import com.walnutgeek.stsloop.core.TurnJson
import com.walnutgeek.stsloop.core.TurnVad
import com.walnutgeek.stsloop.core.Wav
import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.failure.RecordingLevel
import com.walnutgeek.stsloop.core.parseTurnDirectoryName
import com.walnutgeek.stsloop.core.testmode.TurnTest
import com.walnutgeek.stsloop.core.turnDirectoryName
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Writes the Corpus as plain files under [corpusDir].
 *
 * A Turn is assembled in [stagingDir] (outside the Corpus, so a folder sync
 * never sees a half-written Turn), under its Session's id
 * (`<staging>/<session_id>/<turn directory>`), and published with one atomic
 * rename once `audio.wav` and `turn.json` (transcript included) are complete
 * and synced. Both directories must be on the same filesystem. Each
 * Recording's level is measured as it is written, and `audio.silent` says
 * whether it is effectively silent ([RecordingLevel]).
 *
 * A process that dies mid-Turn leaves its staging directory behind;
 * [recover] publishes it.
 */
class FileCorpusWriter(
    private val corpusDir: File,
    private val stagingDir: File,
) : CorpusWriter {

    override fun begin(id: String, sessionId: String, startedAtMs: Long, sampleRate: Int): TurnInProgress {
        val name = turnDirectoryName(startedAtMs, id)
        val staged = File(File(stagingDir, sessionId), name)
        if (!staged.mkdirs()) throw IOException("cannot create $staged")
        return FileTurn(name, staged, id, sessionId, startedAtMs, sampleRate)
    }

    /** What [recover] did with one staged Turn directory. */
    data class Recovered(val name: String, val outcome: Outcome, val turn: Turn? = null, val error: Exception? = null)

    enum class Outcome {
        /** Complete but never renamed: published as it was. */
        PUBLISHED,

        /** Its Recording was sealed and published with a `turn.json` marked `recovered`. */
        RECOVERED,

        /** No samples at all: deleted. */
        DISCARDED,

        /** Not a Turn directory, its name is already in the Corpus, or too long to seal: left where it is. */
        LEFT,

        /** Reading or publishing it failed ([Recovered.error]): left where it is, tried again next time. */
        FAILED,
    }

    /**
     * Publishes every Turn a dead process left in [stagingDir] (#15): the loss
     * of a Turn to a crash should not be silent, and its Recording is the part
     * that matters (`CONTEXT.md`, Recording). A staged Turn with its
     * whole `turn.json` is published as it is. One without (or with a torn
     * one) is sealed: its WAV header
     * is rewritten from the samples on disk (a torn last sample dropped), and it
     * is published with a `turn.json` that has no `vad`, `transcript` or `kind`,
     * `"recovered": true`, and this [appVersion]. A Turn staged by an older
     * version (directly under [stagingDir], with no Session directory) gets
     * session id `unknown`. A staged Turn with no samples is deleted.
     *
     * Only safe while no Turn is being written through this staging directory:
     * call it before a process's first Session, never during one.
     */
    fun recover(appVersion: String, sampleRate: Int): List<Recovered> {
        val out = ArrayList<Recovered>()
        for (child in stagingDir.listFiles()?.sortedBy { it.name } ?: return out) {
            if (!child.isDirectory) continue
            if (parseTurnDirectoryName(child.name) != null) {
                out += recoverOne(child, LEGACY_SESSION, appVersion, sampleRate)
                continue
            }
            for (turn in child.listFiles()?.sortedBy { it.name } ?: continue) {
                if (turn.isDirectory) out += recoverOne(turn, child.name, appVersion, sampleRate)
            }
            child.delete() // only when empty: a Session directory that held nothing more
        }
        return out
    }

    private fun recoverOne(staged: File, sessionId: String, appVersion: String, sampleRate: Int): Recovered =
        try {
            recoverOrThrow(staged, sessionId, appVersion, sampleRate)
        } catch (e: IOException) {
            Recovered(staged.name, Outcome.FAILED, error = e)
        }

    private fun recoverOrThrow(staged: File, sessionId: String, appVersion: String, sampleRate: Int): Recovered {
        val name = staged.name
        val (startedAtMs, id) = parseTurnDirectoryName(name) ?: return Recovered(name, Outcome.LEFT)
        if (File(corpusDir, name).exists()) return Recovered(name, Outcome.LEFT)
        val wavFile = File(staged, AUDIO_FILE)
        val json = File(staged, TURN_FILE)
        if (json.isFile && wavFile.isFile) {
            // turn.json is written after the WAV is sealed and synced: a whole one means a whole Turn.
            if (isWholeTurnJson(json)) {
                publish(staged, name)
                return Recovered(name, Outcome.PUBLISHED)
            }
            if (!json.delete()) throw IOException("cannot replace the torn $json")
        }
        val dataBytes = if (wavFile.isFile) (wavFile.length() - Wav.HEADER_BYTES).coerceAtLeast(0) and 1L.inv() else 0L
        if (dataBytes == 0L) {
            staged.deleteRecursively()
            return Recovered(name, Outcome.DISCARDED)
        }
        if (dataBytes > Int.MAX_VALUE - Wav.HEADER_BYTES) return Recovered(name, Outcome.LEFT) // too long for a WAV header
        val level = RecordingLevel(sampleRate)
        RandomAccessFile(wavFile, "rw").use { wav ->
            wav.setLength(Wav.HEADER_BYTES + dataBytes)
            wav.seek(Wav.HEADER_BYTES.toLong())
            val bytes = ByteArray(64 * 1024) // even, and dataBytes is even: every read is whole samples
            val samples = ShortArray(bytes.size / Wav.BYTES_PER_SAMPLE)
            var left = dataBytes
            while (left > 0) {
                val n = minOf(bytes.size.toLong(), left).toInt()
                wav.readFully(bytes, 0, n)
                val count = n / Wav.BYTES_PER_SAMPLE
                Wav.littleEndianToPcm16(bytes, count, samples)
                level.add(samples, count)
                left -= n
            }
            seal(wav, sampleRate, dataBytes.toInt())
        }
        val turn = Turn(
            id = id,
            sessionId = sessionId,
            startedAtMs = startedAtMs,
            audio = describe(wavFile, sampleRate, dataBytes / Wav.BYTES_PER_SAMPLE, level),
            appVersion = appVersion,
            recovered = true,
        )
        writeTurnJson(staged, turn)
        publish(staged, name)
        return Recovered(name, Outcome.RECOVERED, turn)
    }

    private inner class FileTurn(
        private val name: String,
        private val staged: File,
        private val id: String,
        private val sessionId: String,
        private val startedAtMs: Long,
        private val sampleRate: Int,
    ) : TurnInProgress {
        private val wavFile = File(staged, AUDIO_FILE)
        private val wav = RandomAccessFile(wavFile, "rw").apply { write(ByteArray(Wav.HEADER_BYTES)) }
        private val level = RecordingLevel(sampleRate)
        private var bytes = ByteArray(0)
        private var samples = 0L

        override fun append(samples: ShortArray, count: Int) {
            val n = count * Wav.BYTES_PER_SAMPLE
            if (bytes.size < n) bytes = ByteArray(n)
            Wav.pcm16ToLittleEndian(samples, count, bytes)
            wav.write(bytes, 0, n)
            level.add(samples, count)
            this.samples += count
        }

        override fun finish(
            appVersion: String,
            vad: TurnVad?,
            transcript: Transcript?,
            classification: Classification?,
            test: TurnTest?,
        ): Turn {
            val dataBytes = samples * Wav.BYTES_PER_SAMPLE
            require(dataBytes <= Int.MAX_VALUE - Wav.HEADER_BYTES) { "Recording too long for a WAV file" }
            seal(wav, sampleRate, dataBytes.toInt())
            wav.close()

            val turn = Turn(
                id = id,
                sessionId = sessionId,
                startedAtMs = startedAtMs,
                audio = describe(wavFile, sampleRate, samples, level),
                appVersion = appVersion,
                vad = vad,
                transcript = transcript,
                classification = classification,
                test = test,
            )
            writeTurnJson(staged, turn)
            publish(staged, name)
            return turn
        }

        override fun abandon() {
            runCatching { wav.close() }
            staged.deleteRecursively()
        }
    }

    /** Writes the real WAV header over the placeholder and syncs the Recording to disk. */
    private fun seal(wav: RandomAccessFile, sampleRate: Int, dataBytes: Int) {
        wav.seek(0)
        wav.write(Wav.header(sampleRate, dataBytes))
        wav.fd.sync()
    }

    private fun describe(wavFile: File, sampleRate: Int, samples: Long, level: RecordingLevel) = TurnAudio(
        file = AUDIO_FILE,
        sha256 = sha256(wavFile),
        sampleRate = sampleRate,
        durationMs = Wav.durationMs(samples, sampleRate),
        silent = level.silent,
    )

    private fun isWholeTurnJson(file: File): Boolean =
        runCatching { Json.parseObject(file.readText())["schema"] != null }.getOrDefault(false)

    private fun writeTurnJson(staged: File, turn: Turn) {
        FileOutputStream(File(staged, TURN_FILE)).use {
            it.write(TurnJson.encode(turn).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
    }

    private fun publish(staged: File, name: String) {
        corpusDir.mkdirs()
        val published = File(corpusDir, name)
        if (!staged.renameTo(published)) throw IOException("cannot publish $staged to $published")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** The session id of a recovered Turn staged before Turns were staged under their Session. */
        const val LEGACY_SESSION = "unknown"
    }
}
