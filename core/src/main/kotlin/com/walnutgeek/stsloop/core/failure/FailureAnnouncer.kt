package com.walnutgeek.stsloop.core.failure

/**
 * A failure that Android does not report by throwing, so the loop has to
 * notice it and say so (`docs/mvp.md`, "Platform constraints on the loop").
 * [spoken] is what the loop says when it starts.
 */
enum class SilentFailure(val spoken: String) {
    /** Another capture client took the mic: `AudioRecordingConfiguration.isClientSilenced()`. */
    MIC_SILENCED("The microphone is silenced by another app."),

    /** The live stream has been effectively silent for [SilentAudio.MIC_SILENT_AFTER_MS]. */
    MIC_SILENT("The microphone is recording only silence."),

    /** The last Turn's Recording is effectively silent ([RecordingLevel]). */
    SILENT_RECORDING("That recording was silent."),

    /** The engine reported a machine Turn done, but nothing plausibly played. */
    SPEECH_UNHEARD("My speech may not be playing."),
    ;

    /** Failures that already account for this one: while one of them is on, this one is not said. */
    internal val explainedBy: Set<SilentFailure>
        get() = when (this) {
            MIC_SILENCED, SPEECH_UNHEARD -> emptySet()
            MIC_SILENT -> setOf(MIC_SILENCED)
            SILENT_RECORDING -> setOf(MIC_SILENCED, MIC_SILENT)
        }
}

/**
 * Decides which [SilentFailure]s are said aloud, so that a failing Session
 * says what is wrong without nagging:
 *
 * - A failure is said when it **starts** (its [set] goes from off to on), and
 *   never again while it stays on.
 * - It is **not** said when another failure that already accounts for it is on
 *   ([SilentFailure.explainedBy]): a silenced client is also a silent mic and a
 *   silent Recording, and one sentence covers all three. This is judged when
 *   it starts only, so nothing is said late.
 * - It is not said again within [repeatAfterMs] of the last time it was said,
 *   however often it stops and starts (a flapping condition).
 * - Its end is logged, never said: the echo of the next Turn is what tells the
 *   driver the loop hears them again.
 *
 * [say] is called with the lock held and must not block (it queues a machine
 * Turn). Thread-safe: the capture thread, the STT thread and the platform's
 * callback threads all report here.
 */
class FailureAnnouncer(
    private val clockMs: () -> Long,
    private val repeatAfterMs: Long = REPEAT_AFTER_MS,
    private val say: (SilentFailure) -> Unit,
    private val listener: Listener = object : Listener {},
) {
    interface Listener {
        fun announced(failure: SilentFailure) {}
        fun suppressed(failure: SilentFailure, because: String) {}
        fun cleared(failure: SilentFailure) {}
    }

    private val on = HashSet<SilentFailure>()
    private val saidAtMs = HashMap<SilentFailure, Long>()

    @Synchronized
    fun active(failure: SilentFailure): Boolean = failure in on

    /** [failure] is now on ([active]) or off. Repeating the current state does nothing. */
    @Synchronized
    fun set(failure: SilentFailure, active: Boolean) {
        if (!active) {
            if (on.remove(failure)) listener.cleared(failure)
            return
        }
        if (!on.add(failure)) return
        val now = clockMs()
        val explanation = failure.explainedBy.firstOrNull { it in on }
        val last = saidAtMs[failure]
        when {
            explanation != null -> listener.suppressed(failure, "explained by $explanation")
            last != null && now - last < repeatAfterMs -> listener.suppressed(failure, "said ${(now - last) / 1000} s ago")
            else -> {
                saidAtMs[failure] = now
                listener.announced(failure)
                say(failure)
            }
        }
    }

    companion object {
        /** A failure is not said again within a minute, however it flaps. */
        const val REPEAT_AFTER_MS = 60_000L
    }
}
