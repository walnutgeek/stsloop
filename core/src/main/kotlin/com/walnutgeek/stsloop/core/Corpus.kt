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
 * writes `command` (and, for "scratch that", `tombstones`; for a lone Bucket
 * alias, `focus`). `test` is present only for Turns recorded in Bluetooth
 * test mode (#27). [recovered] marks a Turn published after a crash from
 * what its Session had staged (#15).
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
    val recovered: Boolean = false,
) {
    val kind: TurnKind? get() = classification?.kind

    val endedAtMs: Long get() = startedAtMs + audio.durationMs

    /** `<started_at>-<id>`, e.g. `2026-10-06T14:22:07.431Z-a3f1c9`. */
    val directoryName: String get() = turnDirectoryName(startedAtMs, id)
}

/**
 * The Recording of a Turn: a 16-bit mono PCM WAV file in the Turn directory.
 * [silent] says whether it is effectively silent
 * ([com.walnutgeek.stsloop.core.failure.RecordingLevel]); null when it was
 * not measured, and absent from the JSON then.
 */
data class TurnAudio(
    val file: String,
    val sha256: String,
    val sampleRate: Int,
    val durationMs: Long,
    val silent: Boolean? = null,
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
 * is a [NOTE] only when its Bucket is known: Declared, or filed by Focus.
 * A [DRAFT] (#45) is a Turn appended to an agent Bucket's Draft, under its
 * Focus. Everything else, transcribed or not, is [UNCLASSIFIED].
 */
enum class TurnKind(val json: String) {
    NOTE("note"),
    COMMAND("command"),
    UNCLASSIFIED("unclassified"),
    DRAFT("draft"),
}

/**
 * What the phrase grammar made of a Turn: its [kind] and, for a [TurnKind.NOTE],
 * the Bucket and the Note's [content]; for a [TurnKind.DRAFT], the agent
 * Bucket (and its Descriptor [bucketId]) and the words appended to its Draft;
 * for a [TurnKind.COMMAND], the [command].
 *
 * A Note is assigned to exactly one Bucket (CONTEXT.md), so it always has a
 * [bucket] and [content]. [bucketSource] says where the [bucket] came from,
 * so a later classifier's predictions never mix with Declarations (ground
 * truth) or with Turns filed by Focus. An unclassified Turn carries no label
 * and no content.
 */
data class Classification(
    val kind: TurnKind,
    val declaration: Declaration? = null,
    val bucket: String? = null,
    val bucketSource: BucketSource? = null,
    val content: String? = null,
    val command: CommandInvocation? = null,
    /** For a [TurnKind.DRAFT]: the Descriptor id of its agent Bucket. */
    val bucketId: String? = null,
) {
    init {
        require((kind == TurnKind.COMMAND) == (command != null)) { "a command Turn, and only a command Turn, has a command" }
        require((bucket == null) == (bucketSource == null)) { "bucket and bucket_source go together" }
        require(declaration == null || (declaration.bucket == bucket && bucketSource == BucketSource.DECLARATION)) {
            "a Declaration sets the bucket, with bucket_source declaration"
        }
        require(bucketSource != BucketSource.DECLARATION || declaration != null) { "bucket_source declaration needs a Declaration" }
        val filed = kind == TurnKind.NOTE || kind == TurnKind.DRAFT
        require(filed || (bucket == null && content == null)) { "only a Note or a Draft Turn has a bucket and content" }
        require(!filed || (bucket != null && content != null)) { "a Note or a Draft Turn has a bucket and content" }
        require((kind == TurnKind.DRAFT) == (bucketId != null)) { "a Draft Turn, and only a Draft Turn, has a bucket id" }
        require(kind != TurnKind.DRAFT || bucketSource == BucketSource.FOCUS) { "a Draft Turn is filed by Focus" }
    }

    companion object {
        val UNCLASSIFIED = Classification(TurnKind.UNCLASSIFIED)

        /** A Note whose Bucket was named aloud. */
        fun declared(declaration: Declaration, content: String) =
            Classification(TurnKind.NOTE, declaration, declaration.bucket, BucketSource.DECLARATION, content)

        /** A Note filed under the Bucket that had Focus, with no Bucket named in the Turn. */
        fun focused(bucket: String, content: String) = Classification(TurnKind.NOTE, bucket = bucket, bucketSource = BucketSource.FOCUS, content = content)

        /** A Turn appended to the Draft of the agent Bucket [bucket] (Descriptor id [bucketId]), which had Focus. */
        fun drafted(bucket: String, bucketId: String, content: String) =
            Classification(TurnKind.DRAFT, bucket = bucket, bucketSource = BucketSource.FOCUS, content = content, bucketId = bucketId)

        /** A Turn that was a whole-utterance command. */
        fun command(invocation: CommandInvocation) = Classification(TurnKind.COMMAND, command = invocation)
    }
}

/**
 * A whole-utterance command (`docs/phase0.md`, "Commands"): [json] is its
 * `command.name` in `turn.json`, [phrases] what may be said for it. The
 * phrase grammar obeys a phrase only when it is the whole utterance.
 * The first phrase is the primary, the one the loop and the docs teach: it
 * must be heard in 6 of 6 variants by the recognisability check on the dev
 * phone (`docs/mvp.md`, "Commands"); the others are aliases.
 *
 * To add one: an entry here, and its effect in
 * [com.walnutgeek.stsloop.core.turn.SessionCommands.plan]. The grammar needs no change.
 *
 * A name is permanent once written, and so is what it did: a command whose
 * effect changes gets a new name, and the old one is no longer written.
 * `what_bucket` (Phase 0: say the previous Turn's Bucket) was retired that
 * way when "what bucket" became an alias of [WHERE_AM_I].
 */
enum class Command(val json: String, val phrases: List<String>) {
    /** Tombstones the previous Turn (see [com.walnutgeek.stsloop.core.turn.SessionCommands]). */
    SCRATCH_THAT("scratch_that", listOf("scratch that", "discard that")),

    /** Says the previous Turn's transcript again. */
    REPEAT("repeat", listOf("repeat", "say that again")),

    /** Says the Bucket that has Focus. */
    WHERE_AM_I("where_am_i", listOf("where am i", "what bucket", "which bucket")),

    /** Ends the Session, once its reply has been said. */
    STOP_LISTENING("stop_listening", listOf("stop listening", "end session")),

    /**
     * A Bucket alias said on its own: Focus that Bucket, and say its name. It
     * has no fixed phrase; the grammar matches the Buckets' aliases instead.
     */
    FOCUS("focus", emptyList()),

    /**
     * A command a Bucket declares with the `request` Primitive ("status",
     * "what's blocked"): sent to the Bucket's Provider on the hub, whose
     * reply is spoken. Its phrases come from the Descriptors, not from here.
     */
    REQUEST("request", emptyList()),

    /**
     * The `draft` Primitive (#45), for the agent Bucket that has Focus: say its
     * Draft. Its phrases come from the Descriptor (command id `read_back`).
     */
    READ_BACK("read_back", emptyList()),

    /** Read the Draft back, then send it to the hub with an idempotency key (command id `send`). */
    SEND("send", emptyList()),

    /** Drop the Draft (command id `cancel`). */
    CANCEL("cancel", emptyList()),

    /**
     * Picks an option of the question the focused agent's dialog asks next
     * (#80; command ids `option_1` … `option_9`, "option one" …): the
     * loop reads the choice back, and "send it" sends the whole answer.
     */
    OPTION("option", emptyList()),

    /** Takes the Draft as the free-text answer to the question the focused agent's dialog asks next (#80; `my_answer`). */
    MY_ANSWER("my_answer", emptyList()),

    /**
     * Speaks a short summary of the Notes said in the focused capture Bucket
     * since its last summary (#76): the hub's summariser when it can, the
     * phone alone otherwise ([com.walnutgeek.stsloop.core.summary.BucketSummaries]).
     * Under an agent Bucket's Focus the grammar hears it as that Bucket's
     * `read` request ("what does it say") instead.
     */
    SUMMARIZE("summarize", listOf("summarize bucket", "summarize", "sum it up")),

    /**
     * Says how the hub is, in short (#75): "hub connected, 6 agents, 2
     * blocked."; offline, the phone answers alone ([com.walnutgeek.stsloop.core.hub.HubReport]).
     * "How is the hub" is the primary (#82): the recogniser hears "hub" in
     * "hub status" as "hob" 3 times in 6, so that is only an alias.
     */
    HUB_STATUS("hub_status", listOf("how is the hub", "hub status", "hub health")),

    /**
     * Says how the hub is, at length: the last failure, herdr, the hooks, the
     * Turns waiting to upload (#75). "Details of the hub" is the primary
     * (#82): "hub details" is heard as "hug" or "hog details" 4 times in 6.
     */
    HUB_DETAILS("hub_details", listOf("details of the hub", "hub details", "hub info")),
    ;

    /** Whether it is one of the `draft` Primitive's commands, which carry a [DraftCommand]. */
    val draft: Boolean get() = this == READ_BACK || this == SEND || this == CANCEL || this == OPTION || this == MY_ANSWER

    companion object {
        /** The `draft` command a Descriptor's command id names, or null when the phone knows no such one. */
        fun draftOf(id: String): Command? = when (id) {
            "read_back" -> READ_BACK
            "send" -> SEND
            "cancel" -> CANCEL
            "my_answer" -> MY_ANSWER
            else -> if (optionOf(id) != null) OPTION else null
        }

        /** The option a command id `option_<n>` picks (1 is the first), or null for any other id. */
        fun optionOf(id: String): Int? = OPTION_ID.matchEntire(id)?.groupValues?.get(1)?.toInt()

        private val OPTION_ID = Regex("option_([1-9])")
    }
}

/**
 * A `draft` command as invoked, for the agent Bucket named [bucket] whose
 * Descriptor id is [bucketId]: the Draft's [text] as it stood (null when there
 * was none), and for a [Command.SEND] that went to the hub, its idempotency
 * [key] (null when nothing was sent: no Draft, the hub offline, or a send
 * still waiting on the hub).
 */
data class DraftCommand(
    val bucket: String,
    val bucketId: String,
    val text: String? = null,
    val key: String? = null,
    /** For [Command.OPTION]: the option picked, 1 for the first. */
    val option: Int? = null,
    /**
     * For [Command.OPTION] and [Command.MY_ANSWER] that took, and a [Command.SEND]
     * that sent, an answer to a dialog (#80): that dialog's id. Its [text] is then
     * the option's label, the answer's own text, or the whole answer as read back.
     */
    val dialog: String? = null,
)

/**
 * A [Command.REQUEST] as invoked: the [command] id of the Bucket named
 * [bucket], whose Descriptor id is [bucketId]. [replyWithinMs] is how long
 * its Descriptor asks the phone to wait for the reply; it is not written to
 * the Turn.
 */
data class BucketRequest(val bucket: String, val bucketId: String, val command: String, val replyWithinMs: Long? = null)

/**
 * A [Command.SUMMARIZE] as invoked, for the capture Bucket named [bucket]
 * (the Inbox included): [since], the directory name of the newest Note its
 * last summary covered (null when it has had none), and [notes], the
 * directory names of the Notes this summary covers, oldest first (empty when
 * there were none). [more] says that more Notes than these were said in
 * that range, and the oldest were left out.
 */
data class SummaryCommand(val bucket: String, val since: String? = null, val notes: List<String> = emptyList(), val more: Boolean = false)

/**
 * A command as invoked in one Turn: which [command], the words heard
 * (normalised), for [Command.SCRATCH_THAT] the directory name of the Turn
 * it tombstones (null when there was nothing to drop), for [Command.FOCUS]
 * the name of the Bucket it [focus]es (and, for a hub Bucket, its
 * Descriptor id [focusId]), for [Command.REQUEST] the [request], and for
 * [Command.SUMMARIZE] the [summary].
 */
data class CommandInvocation(
    val command: Command,
    val matched: String,
    val tombstones: String? = null,
    val focus: String? = null,
    val focusId: String? = null,
    val request: BucketRequest? = null,
    val draft: DraftCommand? = null,
    val summary: SummaryCommand? = null,
) {
    init {
        require(tombstones == null || command == Command.SCRATCH_THAT) { "only scratch_that tombstones a Turn" }
        require((focus != null) == (command == Command.FOCUS)) { "a focus command, and only a focus command, names a Bucket" }
        require(focusId == null || focus != null) { "a focus id goes with a focus" }
        require((request != null) == (command == Command.REQUEST)) { "a request command, and only a request command, has a request" }
        require((draft != null) == command.draft) { "a draft command, and only a draft command, has a draft" }
        require(draft?.key == null || command == Command.SEND) { "only send has a key" }
        require((summary != null) == (command == Command.SUMMARIZE)) { "a summarize command, and only a summarize command, has a summary" }
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
    /** The Bucket was named at one end of the Turn: ground truth. */
    DECLARATION("declaration"),

    /** No Bucket was named; the Turn was filed under the Bucket that had Focus. */
    FOCUS("focus"),
}

fun turnDirectoryName(startedAtMs: Long, id: String): String = "${UtcTimestamp.format(startedAtMs)}-$id"

/** The `started_at` and id a [turnDirectoryName] was made from, or null if [name] is not one. */
fun parseTurnDirectoryName(name: String): Pair<Long, String>? {
    val dash = name.lastIndexOf('-')
    if (dash <= 0) return null
    val id = name.substring(dash + 1)
    if (id.isEmpty() || !id.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return UtcTimestamp.parse(name.substring(0, dash))?.let { it to id }
}

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
