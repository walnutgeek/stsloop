package com.walnutgeek.stsloop.core

/** Version of the `turn.json` format written to the Corpus. */
const val CORPUS_SCHEMA = 1

/** File names inside a Turn directory. */
const val AUDIO_FILE = "audio.wav"
const val TURN_FILE = "turn.json"

/**
 * One Turn as recorded in the Corpus (`turn.json`, schema 1).
 *
 * Timestamps are epoch milliseconds, UTC. Fields owned by later tickets
 * (`vad`, `transcript`, `kind`, `declaration`, `bucket`, …) are not modelled
 * yet and are absent from the JSON.
 */
data class Turn(
    val id: String,
    val sessionId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val audio: TurnAudio,
    val appVersion: String,
    val tombstonedBy: String? = null,
) {
    /** `<started_at>-<id>`, e.g. `2026-10-06T14:22:07.431Z-a3f1c9`. */
    val directoryName: String get() = turnDirectoryName(startedAtMs, id)
}

/** The Recording of a Turn: a 16-bit mono PCM WAV file in the Turn directory. */
data class TurnAudio(
    val file: String,
    val sha256: String,
    val sampleRate: Int,
    val durationMs: Long,
)

fun turnDirectoryName(startedAtMs: Long, id: String): String = "${UtcTimestamp.format(startedAtMs)}-$id"

/**
 * Appends Turns to the Corpus. The Corpus is append-only and a Turn directory
 * is immutable once written: it appears complete, or not at all.
 */
interface CorpusWriter {
    /** Opens a new Turn whose Recording is streamed in as it is captured. */
    fun begin(id: String, sessionId: String, startedAtMs: Long, sampleRate: Int): TurnInProgress
}

/** A Turn being captured. Exactly one of [finish] or [abandon] must be called. */
interface TurnInProgress {
    /** Appends the first [count] PCM16 samples of [samples] to the Recording. */
    fun append(samples: ShortArray, count: Int)

    /** Seals the Recording, writes `turn.json`, and publishes the Turn directory. */
    fun finish(endedAtMs: Long, appVersion: String): Turn

    /** Discards everything written so far; nothing appears in the Corpus. */
    fun abandon()
}
