package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.BucketRequest
import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CommandInvocation
import com.walnutgeek.stsloop.core.Turn
import com.walnutgeek.stsloop.core.TurnKind
import com.walnutgeek.stsloop.core.hub.Drafts
import com.walnutgeek.stsloop.core.hub.HubReports
import com.walnutgeek.stsloop.core.grammar.Descriptor
import com.walnutgeek.stsloop.core.grammar.Primitive
import com.walnutgeek.stsloop.core.summary.BucketSummaries
import com.walnutgeek.stsloop.core.summary.NoteRange

/**
 * Carries out whole-utterance commands (`docs/phase0.md`, "Commands") against
 * one Session's Turns. Confined to the thread that publishes the Session's
 * Turns, and fed every Turn it publishes, in order, through [published].
 *
 * A command is decided in two steps, because its Turn records what it did:
 * [plan] works out the effect before the command Turn is written (for
 * "scratch that", which Turn it tombstones), and [published] takes it into
 * account only once that Turn is in the Corpus. A command Turn that fails
 * to write has done nothing, and the loop says nothing for it.
 *
 * "Scratch that" and "repeat" act on the **previous Turn**: what the
 * loop last read back to you, or is about to. That is the most recent Turn
 * of this Session that is not a command and has a transcript worth echoing
 * ([EchoText]), whether or not its echo has played yet (it may still be
 * waiting for Silence, and a test Session echoes nothing). So:
 * - Turns with no transcript, or an empty one (noise the recognizer heard
 *   nothing in), are skipped: the person never heard them, and they are not
 *   what "that" means.
 * - Command Turns are skipped too, so "repeat" twice repeats the same Turn.
 * - If that Turn is tombstoned, no command reaches a Turn further back:
 *   "scratch that" says [NOTHING_TO_DROP] (a repeated "scratch that", say
 *   because "dropped." went unheard, never drops a second Turn), and
 *   "repeat" says [WAS_DROPPED].
 * - Only this Session: a Session is started and ended deliberately, so a
 *   new one starts with no previous Turn.
 *
 * The replies:
 * - [Command.SCRATCH_THAT]: tombstones the previous Turn and says [DROPPED];
 *   with none, the command Turn is still written (`tombstones: null`) and
 *   says [NOTHING_TO_DROP].
 * - [Command.REPEAT]: says its transcript again, or [NOTHING_TO_REPEAT].
 * - [Command.WHERE_AM_I] ("where am I", "what bucket"): says the name of
 *   the Bucket that has [focus] ([bucketAsWords]), "inbox." in the Inbox.
 * - [Command.FOCUS] (a lone Bucket alias): says the name of the Bucket it
 *   focuses. The Focus itself moves once its Turn is published ([CorpusSink]).
 * - For a hub Bucket, both add [HUB_OFFLINE] while [hubOnline] is false
 *   (ADR-0001): the Bucket still resolves from the cached Descriptors. Only a
 *   volatile status is read, so an unreachable hub never holds up a reply.
 * - Focusing a [Descriptor.degraded] Bucket adds [NEEDS_NEWER_APP], once per
 *   Session and Bucket (ADR-0006: "the loop says so once").
 * - [Command.REQUEST] (a command a Bucket declares, "status"): says nothing
 *   itself; its [Outcome.request] goes to the hub once the Turn is
 *   published, and the reply is spoken when it comes ([HubRequests]).
 * - [Command.STOP_LISTENING]: says [STOPPED_LISTENING] and [Outcome.endsSession].
 * - [Command.HUB_STATUS] and [Command.HUB_DETAILS] (#75): say nothing
 *   themselves; once the Turn is published, [Outcome.hubReport] is answered
 *   ([HubReports]): by the hub's report when it is online, by the phone alone otherwise.
 * - [Command.SUMMARIZE] ("summarize bucket", #76): reads the Notes the
 *   focused capture Bucket's summary covers ([BucketSummaries.range]) and
 *   records them in its Turn; once that is published, [Outcome.range] is
 *   summarised ([BucketSummaries.summarize]). A Bucket that holds no Notes
 *   (a hub Bucket without "what does it say") says [noNotes].
 *
 * The `draft` Primitive's commands (#45) act on the [drafts] of the agent
 * Bucket they name, and record the Draft as it stood ([DraftCommand.text]):
 * - [Command.READ_BACK]: says "draft for <Bucket>: <text>.", or [NO_DRAFT].
 * - [Command.CANCEL]: drops the Draft and says [DRAFT_DROPPED], or [NO_DRAFT].
 * - [Command.SEND]: with a Draft, the hub not known offline, and no earlier
 *   send of that Bucket still waiting on the hub, takes a new idempotency key
 *   ([DraftCommand.key]) and says "sending to <Bucket>: <text>."; once that
 *   readback is over the Draft goes to the hub ([Outcome.sendKey], [Drafts]).
 *   Otherwise it says [NOTHING_TO_SEND], [Drafts.OFFLINE] or [STILL_SENDING],
 *   and the Draft stays.
 * - Focusing a Bucket that has a Draft adds [DRAFT_WAITING].
 * - "Scratch that" on a Draft Turn takes its words back out of the Draft.
 */
class SessionCommands(
    private val focus: Focus,
    /** Whether the hub is connected and welcomed now; must return at once. */
    private val hubOnline: () -> Boolean = { false },
    /** This Session's Drafts. */
    private val drafts: Drafts,
    /** This Session's summaries: where "summarize bucket" reads its Notes. */
    private val summaries: BucketSummaries? = null,
) {
    /**
     * What a command does: the [classification] its Turn is written with, what
     * the loop says back, and whether the Session ends once that is said.
     */
    data class Outcome(
        val classification: Classification,
        val say: String?,
        val endsSession: Boolean = false,
        /** For [Command.REQUEST]: what to send to the hub once the Turn is published. */
        val request: BucketRequest? = null,
        /** For a [Command.SEND] that goes ahead: the key the Draft is sent under, once [say] (its readback) is over. */
        val sendKey: String? = null,
        /** For [Command.SUMMARIZE] on a capture Bucket: summarise [range] once the Turn is published. */
        val summarize: Boolean = false,
        /** The Notes to summarise; null when the Corpus could not be read. */
        val range: NoteRange? = null,
        /** For [Command.HUB_STATUS] and [Command.HUB_DETAILS]: answer it once the Turn is published ([HubReports]). */
        val hubReport: HubReports.Detail? = null,
    )

    /** The previous Turn, or null. Its transcript is never null or blank: only such a Turn becomes [previous]. */
    private var previous: Turn? = null

    /** Whether [previous] has been tombstoned. */
    private var dropped = false

    /** The degraded Buckets the loop has said so for, this Session. */
    private val toldDegraded = mutableSetOf<String>()

    /** The effect of [invocation], given the Turns published so far. Changes nothing until [published]. */
    fun plan(invocation: CommandInvocation): Outcome {
        val turn = previous?.takeUnless { dropped }
        val plain by lazy { Classification.command(invocation) }
        return when (invocation.command) {
            Command.SCRATCH_THAT ->
                Outcome(Classification.command(invocation.copy(tombstones = turn?.directoryName)), if (turn != null) DROPPED else NOTHING_TO_DROP)
            Command.REPEAT -> Outcome(
                plain,
                when {
                    previous == null -> NOTHING_TO_REPEAT
                    turn == null -> WAS_DROPPED
                    else -> turn.transcript!!.text
                },
            )
            Command.WHERE_AM_I -> Outcome(plain, say(focus.bucket))
            Command.FOCUS -> {
                val name = invocation.focus!!
                val bucket = focus.named(name)
                val degraded = bucket?.degraded == true && name !in toldDegraded
                val said = bucket?.let(::say) ?: bucketAsWords(name)
                val waiting = bucket?.id?.let(drafts::text) != null
                Outcome(plain, listOfNotNull(said, NEEDS_NEWER_APP.takeIf { degraded }, DRAFT_WAITING.takeIf { waiting }).joinToString(" "))
            }
            Command.READ_BACK, Command.CANCEL, Command.SEND -> draft(invocation)
            Command.REQUEST -> Outcome(plain, null, request = invocation.request)
            Command.STOP_LISTENING -> Outcome(plain, STOPPED_LISTENING, endsSession = true)
            Command.SUMMARIZE -> summary(invocation)
            Command.HUB_STATUS -> Outcome(plain, null, hubReport = HubReports.Detail.SHORT)
            Command.HUB_DETAILS -> Outcome(plain, null, hubReport = HubReports.Detail.LONG)
        }
    }

    private fun summary(invocation: CommandInvocation): Outcome {
        val s = invocation.summary!!
        val bucket = focus.named(s.bucket)
        if (bucket == null || bucket.hub || Primitive.NOTE !in bucket.primitives) return Outcome(Classification.command(invocation), noNotes(s.bucket))
        val range = summaries?.range(s.bucket)
        val recorded = s.copy(since = range?.since, notes = range?.notes.orEmpty().map { it.turn }, more = range?.more == true)
        return Outcome(Classification.command(invocation.copy(summary = recorded)), null, summarize = true, range = range)
    }

    private fun draft(invocation: CommandInvocation): Outcome {
        val d = invocation.draft!!
        val text = drafts.text(d.bucketId)
        val name = bucketAsWords(d.bucket).removeSuffix(".")
        fun outcome(say: String, key: String? = null) =
            Outcome(Classification.command(invocation.copy(draft = d.copy(text = text, key = key))), say, sendKey = key)
        return when (invocation.command) {
            Command.READ_BACK -> outcome(if (text == null) NO_DRAFT else "draft for $name: $text.")
            Command.CANCEL -> outcome(if (text == null) NO_DRAFT else DRAFT_DROPPED)
            else -> when {
                text == null -> outcome(NOTHING_TO_SEND)
                !hubOnline() -> outcome(Drafts.OFFLINE)
                drafts.sending(d.bucketId) -> outcome(STILL_SENDING)
                else -> outcome("sending to $name: $text.", key = drafts.newKey())
            }
        }
    }

    /** A Bucket's name, and whether its hub is offline when it is the hub's. */
    private fun say(bucket: Descriptor): String =
        if (bucket.hub && !hubOnline()) "${bucketAsWords(bucket.name)} $HUB_OFFLINE" else bucketAsWords(bucket.name)

    /** [turn] is in the Corpus. Call for every Turn, in order, commands included. */
    fun published(turn: Turn) {
        val command = turn.classification?.command
        command?.focus?.let { name -> if (focus.named(name)?.degraded == true) toldDegraded += name }
        val c = turn.classification
        if (c?.kind == TurnKind.DRAFT) drafts.append(c.bucketId!!, c.bucket!!, turn.directoryName, c.content!!)
        command?.draft?.let { d ->
            when (command.command) {
                Command.CANCEL -> drafts.cancel(d.bucketId)
                Command.SEND -> d.key?.let { drafts.take(d.bucketId, it) }
                else -> Unit
            }
        }
        when {
            command != null -> if (command.tombstones != null) {
                dropped = true
                drafts.drop(command.tombstones)
            }
            EchoText.of(turn.transcript?.text) != null -> {
                previous = turn
                dropped = false
            }
        }
    }

    companion object {
        /** Said once a Turn is tombstoned. */
        const val DROPPED = "dropped."

        /** Said when "scratch that" finds nothing to drop. */
        const val NOTHING_TO_DROP = "nothing to drop."

        /** Said when "repeat" finds the previous Turn tombstoned. */
        const val WAS_DROPPED = "that was dropped."

        /** Said when "repeat" finds no previous Turn. */
        const val NOTHING_TO_REPEAT = "nothing to repeat."

        /** Said after a hub Bucket's name while the hub cannot be reached. */
        const val HUB_OFFLINE = "hub offline."

        /** Said once after the name of a Bucket whose Descriptor names a Primitive this app does not know. */
        const val NEEDS_NEWER_APP = "some of its commands need a newer app."

        /** Said by "read it back" and "cancel" when the focused agent has no Draft. */
        const val NO_DRAFT = "no draft."

        /** Said once "cancel" has dropped the Draft. */
        const val DRAFT_DROPPED = "draft dropped."

        /** Said when "send" finds no Draft. */
        const val NOTHING_TO_SEND = "nothing to send."

        /** Said when "send" comes while the Bucket's last send is still waiting on the hub; the new Draft stays. */
        const val STILL_SENDING = "still sending the last one; draft kept."

        /** Said after the name of a Bucket focused while it has a Draft. */
        const val DRAFT_WAITING = "a draft is waiting."

        /** Said before "stop listening" ends the Session. */
        const val STOPPED_LISTENING = "stopped listening."

        /** Said when "summarize bucket" is heard under the Focus of a Bucket that holds no Notes. */
        fun noNotes(bucket: String): String = "${bucketAsWords(bucket).removeSuffix(".")} has no notes to summarize."

        /** A Bucket's name as words a TTS engine reads: `house-project` → "house project.". */
        fun bucketAsWords(bucket: String): String = bucket.replace('-', ' ').replace('_', ' ').trim() + "."
    }
}
