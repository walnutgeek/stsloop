package com.walnutgeek.stsloop.core

/** Version of the `turn.json` format written to the Corpus. */
const val CORPUS_SCHEMA = 1

/** File names inside a Turn directory. */
const val AUDIO_FILE = "audio.wav"
const val TURN_FILE = "turn.json"

/**
 * One Turn as recorded in the Corpus (`turn.json`, schema 1).
 *
 * Timestamps are epoch milliseconds, UTC. `ended_at` is not observed
 * separately: it is `started_at + duration_ms`, so a sample offset into the
 * Recording maps to wall time exactly. Fields owned by later tickets
 * (`transcript`, `kind`, `declaration`, `bucket`, …) are not modelled yet and
 * are absent from the JSON; so is `vad` when no VAD cut the Turn.
 */
data class Turn(
    val id: String,
    val sessionId: String,
    val startedAtMs: Long,
    val audio: TurnAudio,
    val appVersion: String,
    val vad: TurnVad? = null,
    val tombstonedBy: String? = null,
) {
    val endedAtMs: Long get() = startedAtMs + audio.durationMs

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

/**
 * How the VAD cut a human Turn: speech from the first to the last speech
 * window (gaps shorter than the trailing Silence included), then the trailing
 * Silence that closed it.
 * Pre-roll before the onset makes up the rest of `duration_ms`.
 */
data class TurnVad(
    val speechMs: Long,
    val trailingSilenceMs: Long,
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
    fun finish(appVersion: String, vad: TurnVad? = null): Turn

    /** Discards everything written so far; nothing appears in the Corpus. */
    fun abandon()
}
