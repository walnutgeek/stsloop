package com.walnutgeek.stsloop.audio

import com.walnutgeek.stsloop.core.AUDIO_FILE
import com.walnutgeek.stsloop.core.CorpusWriter
import com.walnutgeek.stsloop.core.TURN_FILE
import com.walnutgeek.stsloop.core.Transcript
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnAudio
import com.walnutgeek.stsloop.core.TurnInProgress
import com.walnutgeek.stsloop.core.TurnJson
import com.walnutgeek.stsloop.core.TurnKind
import com.walnutgeek.stsloop.core.TurnVad
import com.walnutgeek.stsloop.core.Wav
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
 * never sees a half-written Turn) and published with one atomic rename once
 * `audio.wav` and `turn.json` (transcript included) are complete and synced. Both directories must be
 * on the same filesystem.
 */
class FileCorpusWriter(
    private val corpusDir: File,
    private val stagingDir: File,
) : CorpusWriter {

    override fun begin(id: String, sessionId: String, startedAtMs: Long, sampleRate: Int): TurnInProgress {
        val name = turnDirectoryName(startedAtMs, id)
        val staged = File(stagingDir, name)
        if (!staged.mkdirs()) throw IOException("cannot create $staged")
        return FileTurn(name, staged, id, sessionId, startedAtMs, sampleRate)
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
        private var bytes = ByteArray(0)
        private var samples = 0L

        override fun append(samples: ShortArray, count: Int) {
            val n = count * Wav.BYTES_PER_SAMPLE
            if (bytes.size < n) bytes = ByteArray(n)
            Wav.pcm16ToLittleEndian(samples, count, bytes)
            wav.write(bytes, 0, n)
            this.samples += count
        }

        override fun finish(appVersion: String, vad: TurnVad?, transcript: Transcript?, kind: TurnKind?): Turn {
            val dataBytes = samples * Wav.BYTES_PER_SAMPLE
            require(dataBytes <= Int.MAX_VALUE - Wav.HEADER_BYTES) { "Recording too long for a WAV file" }
            wav.seek(0)
            wav.write(Wav.header(sampleRate, dataBytes.toInt()))
            wav.fd.sync()
            wav.close()

            val turn = Turn(
                id = id,
                sessionId = sessionId,
                startedAtMs = startedAtMs,
                audio = TurnAudio(
                    file = AUDIO_FILE,
                    sha256 = sha256(wavFile),
                    sampleRate = sampleRate,
                    durationMs = Wav.durationMs(samples, sampleRate),
                ),
                appVersion = appVersion,
                vad = vad,
                transcript = transcript,
                kind = kind,
            )
            FileOutputStream(File(staged, TURN_FILE)).use {
                it.write(TurnJson.encode(turn).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            corpusDir.mkdirs()
            val published = File(corpusDir, name)
            if (!staged.renameTo(published)) throw IOException("cannot publish $staged to $published")
            return turn
        }

        override fun abandon() {
            runCatching { wav.close() }
            staged.deleteRecursively()
        }
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
}
