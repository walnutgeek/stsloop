package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CommandInvocation
import com.walnutgeek.stsloop.core.Turn

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
 * - [Command.STOP_LISTENING]: says [STOPPED_LISTENING] and [Outcome.endsSession].
 */
class SessionCommands(private val focus: Focus) {
    /**
     * What a command does: the [classification] its Turn is written with, what
     * the loop says back, and whether the Session ends once that is said.
     */
    data class Outcome(val classification: Classification, val say: String, val endsSession: Boolean = false)

    /** The previous Turn, or null. Its transcript is never null or blank: only such a Turn becomes [previous]. */
    private var previous: Turn? = null

    /** Whether [previous] has been tombstoned. */
    private var dropped = false

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
            Command.WHERE_AM_I -> Outcome(plain, bucketAsWords(focus.bucket.name))
            Command.FOCUS -> Outcome(plain, bucketAsWords(invocation.focus!!))
            Command.STOP_LISTENING -> Outcome(plain, STOPPED_LISTENING, endsSession = true)
        }
    }

    /** [turn] is in the Corpus. Call for every Turn, in order, commands included. */
    fun published(turn: Turn) {
        val command = turn.classification?.command
        when {
            command != null -> if (command.tombstones != null) dropped = true
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

        /** Said before "stop listening" ends the Session. */
        const val STOPPED_LISTENING = "stopped listening."

        /** A Bucket's name as words a TTS engine reads: `house-project` → "house project.". */
        fun bucketAsWords(bucket: String): String = bucket.replace('-', ' ').replace('_', ' ').trim() + "."
    }
}
