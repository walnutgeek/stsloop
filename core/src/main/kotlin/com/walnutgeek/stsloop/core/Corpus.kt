package com.walnutgeek.stsloop.core

import com.walnutgeek.stsloop.core.testmode.TurnTest

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
 * Recording maps to wall time exactly. `vad` is absent from the JSON when no
 * VAD cut the Turn, and `transcript` when the recognizer produced none. A
 * classified Turn always writes `kind`, `declaration`, `bucket`,
 * `bucket_source` and `content`, as `null` where unset; a command Turn also
 * writes `command` (and, for "scratch that", `tombstones`). `test` is present
 * only for Turns recorded in Bluetooth test mode (#27).
 *
 * There is no `tombstoned_by`: a Turn directory never changes once
 * published, so a Turn cannot record that it was tombstoned later. Readers
 * derive it from the command Turns' `tombstones`
 * ([com.walnutgeek.stsloop.core.corpus.TranscriptList.tombstonedBy]).
 */
data class Turn(
    val id: String,
    val sessionId: String,
    val startedAtMs: Long,
    val audio: TurnAudio,
    val appVersion: String,
    val vad: TurnVad? = null,
    val transcript: Transcript? = null,
    val classification: Classification? = null,
    val test: TurnTest? = null,
) {
    val kind: TurnKind? get() = classification?.kind

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

/**
 * What the on-device recognizer heard in a Turn's Recording.
 *
 * [text] is exactly what the engine produced (this model: upper case, no
 * punctuation); readers normalise it themselves.
 * [finishedAtMs] is wall time when the text was final. [latencyMs] is the
 * end-of-utterance to transcript latency on one monotonic clock: from when
 * the Turn's last sample was captured to when the text was final. It is not
 * `finished_at - ended_at`, which mixes the wall clock with the sample clock.
 */
data class Transcript(
    val text: String,
    val engine: String,
    val model: String,
    val finishedAtMs: Long,
    val latencyMs: Long,
)

/**
 * `kind` in `turn.json`. Corpus labels are permanent training data, so a Turn
 * is a [NOTE] only when its Bucket is known (today: Declared). Everything
 * else, transcribed or not, is [UNCLASSIFIED].
 */
enum class TurnKind(val json: String) {
    NOTE("note"),
    COMMAND("command"),
    UNCLASSIFIED("unclassified"),
}

/**
 * What the phrase grammar made of a Turn: its [kind] and, for a [TurnKind.NOTE],
 * the Bucket and the Note's [content]; for a [TurnKind.COMMAND], the [command].
 *
 * A Note is assigned to exactly one Bucket (CONTEXT.md), so it always has a
 * [bucket] and [content]. [bucketSource] says where the [bucket] came from,
 * so a later classifier's predictions never mix with Declarations (ground
 * truth). An unclassified Turn carries no label and no content.
 */
data class Classification(
    val kind: TurnKind,
    val declaration: Declaration? = null,
    val bucket: String? = null,
    val bucketSource: BucketSource? = null,
    val content: String? = null,
    val command: CommandInvocation? = null,
) {
    init {
        require((kind == TurnKind.COMMAND) == (command != null)) { "a command Turn, and only a command Turn, has a command" }
        require((bucket == null) == (bucketSource == null)) { "bucket and bucket_source go together" }
        require(declaration == null || (declaration.bucket == bucket && bucketSource == BucketSource.DECLARATION)) {
            "a Declaration sets the bucket, with bucket_source declaration"
        }
        require(kind == TurnKind.NOTE || (bucket == null && content == null)) { "only a Note has a bucket and content" }
        require(kind != TurnKind.NOTE || (bucket != null && content != null)) { "a Note has a bucket and content" }
    }

    companion object {
        val UNCLASSIFIED = Classification(TurnKind.UNCLASSIFIED)

        /** A Note whose Bucket was named aloud. */
        fun declared(declaration: Declaration, content: String) =
            Classification(TurnKind.NOTE, declaration, declaration.bucket, BucketSource.DECLARATION, content)

        /** A Turn that was a whole-utterance command. */
        fun command(invocation: CommandInvocation) = Classification(TurnKind.COMMAND, command = invocation)
    }
}

/**
 * A whole-utterance command (`docs/mvp.md`, "Commands"): [json] is its
 * `command.name` in `turn.json`, [phrases] what may be said for it. The
 * phrase grammar obeys a phrase only when it is the whole utterance.
 *
 * To add one (#14): an entry here, and its effect in
 * [com.walnutgeek.stsloop.core.turn.SessionCommands.plan]. The grammar needs no change.
 */
enum class Command(val json: String, val phrases: List<String>) {
    /** Tombstones the previous Turn (see [com.walnutgeek.stsloop.core.turn.SessionCommands]). */
    SCRATCH_THAT("scratch_that", listOf("scratch that", "discard that")),
}

/**
 * A command as invoked in one Turn: which [command], the words heard
 * (normalised), and, for [Command.SCRATCH_THAT], the directory name of the
 * Turn it tombstones, or null when there was nothing to drop.
 */
data class CommandInvocation(
    val command: Command,
    val matched: String,
    val tombstones: String? = null,
) {
    init {
        require(tombstones == null || command == Command.SCRATCH_THAT) { "only scratch_that tombstones a Turn" }
    }
}

/** A Bucket named aloud at one end of a Turn: which Bucket, which end, and the words heard (normalised). */
data class Declaration(
    val bucket: String,
    val position: DeclarationPosition,
    val matched: String,
)

/** `declaration.position` in `turn.json`. */
enum class DeclarationPosition(val json: String) {
    LEADING("leading"),
    TRAILING("trailing"),
}

/** `bucket_source` in `turn.json`; `classifier` comes later. */
enum class BucketSource(val json: String) {
    DECLARATION("declaration"),
}

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

    /**
     * Seals the Recording, writes `turn.json` (with [transcript],
     * [classification] and the test-mode [test] block when given), and
     * publishes the Turn directory in one step.
     */
    fun finish(
        appVersion: String,
        vad: TurnVad? = null,
        transcript: Transcript? = null,
        classification: Classification? = null,
        test: TurnTest? = null,
    ): Turn

    /** Discards everything written so far; nothing appears in the Corpus. */
    fun abandon()
}
