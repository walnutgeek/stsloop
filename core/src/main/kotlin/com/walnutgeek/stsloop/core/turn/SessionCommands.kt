package com.walnutgeek.stsloop.core.turn

import com.walnutgeek.stsloop.core.Classification
import com.walnutgeek.stsloop.core.Command
import com.walnutgeek.stsloop.core.CommandInvocation
import com.walnutgeek.stsloop.core.Turn

/**
 * Carries out whole-utterance commands (`docs/mvp.md`, "Commands") against
 * one Session's Turns. Confined to the thread that publishes the Session's
 * Turns, and fed every Turn it publishes, in order, through [published].
 *
 * A command is decided in two steps, because its Turn records what it did:
 * [plan] works out the effect before the command Turn is written (for
 * "scratch that", which Turn it tombstones), and [published] takes it into
 * account only once that Turn is in the Corpus. A command Turn that fails
 * to write has done nothing, and the loop says nothing for it.
 *
 * "Scratch that" ([Command.SCRATCH_THAT]) drops **what the loop last read
 * back to you**, or is about to: the most recent Turn of this Session that
 * is not a command and has a transcript worth echoing ([EchoText]), whether
 * or not its echo has played yet (it may still be waiting for Silence, and a
 * test Session echoes nothing). So:
 * - Turns with no transcript, or an empty one (noise the recognizer heard
 *   nothing in), are skipped: the person never heard them, and they are not
 *   what "that" means.
 * - Command Turns are skipped too.
 * - If that Turn is already tombstoned, there is nothing to drop: a repeated
 *   "scratch that" (say, because "dropped." went unheard) never reaches a
 *   second Turn further back.
 * - Only this Session: a Session is started and ended deliberately, so a
 *   new one starts with nothing to drop.
 *
 * When there is nothing to drop, the command Turn is still written (with
 * `tombstones: null`) and the loop says [NOTHING_TO_DROP].
 */
class SessionCommands {
    /** What a command does: the [classification] its Turn is written with, and what the loop says back. */
    data class Outcome(val classification: Classification, val say: String)

    /** Directory name of the Turn "scratch that" would drop now, or null. */
    private var dropTarget: String? = null

    /** The effect of [invocation], given the Turns published so far. Changes nothing until [published]. */
    fun plan(invocation: CommandInvocation): Outcome = when (invocation.command) {
        Command.SCRATCH_THAT -> {
            val target = dropTarget
            Outcome(Classification.command(invocation.copy(tombstones = target)), if (target != null) DROPPED else NOTHING_TO_DROP)
        }
    }

    /** [turn] is in the Corpus. Call for every Turn, in order, commands included. */
    fun published(turn: Turn) {
        val command = turn.classification?.command
        when {
            command != null -> if (command.tombstones != null) dropTarget = null
            EchoText.of(turn.transcript?.text) != null -> dropTarget = turn.directoryName
        }
    }

    companion object {
        /** Said once a Turn is tombstoned. */
        const val DROPPED = "dropped."

        /** Said when "scratch that" finds nothing to drop. */
        const val NOTHING_TO_DROP = "nothing to drop."
    }
}
