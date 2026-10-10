package com.walnutgeek.stsloop.core

/**
 * Lifecycle of a Session as seen by the service that holds the microphone.
 *
 * [Saving] covers the gap after Stop while the capture thread is still
 * publishing its Turn; a Start in that gap is remembered ([SavingThenRestart])
 * rather than lost.
 */
sealed interface SessionState {
    val isActive: Boolean get() = this == Capturing || this == Saving || this == SavingThenRestart

    data object NoSession : SessionState

    /** No Session, because the last Start was refused. */
    data class StartBlocked(val refusal: StartRefusal) : SessionState
    data object Capturing : SessionState
    data object Saving : SessionState
    data object SavingThenRestart : SessionState
}

/** Why a Session could not start. Each one needs the app opened to fix. */
enum class StartRefusal {
    /** RECORD_AUDIO not granted. */
    MICROPHONE,

    /** The Session notification would not be shown. */
    NOTIFICATIONS,

    /** The platform refused the microphone foreground service (no exemption applied). */
    NOT_ALLOWED,
}

sealed interface SessionEvent {
    data object Start : SessionEvent
    data class StartRefused(val refusal: StartRefusal) : SessionEvent
    data object Stop : SessionEvent

    /** The capture thread has published (or abandoned) its Turn. */
    data object CaptureEnded : SessionEvent
}

sealed interface SessionEffect {
    /** Begin a new capture into a new Turn. */
    data object LaunchCapture : SessionEffect

    /** Ask the running capture to stop and publish its Turn. */
    data object SignalStop : SessionEffect

    /**
     * Leave the foreground and stop the service, releasing the microphone, but
     * keep the notification showing the new state's controls.
     */
    data object StopServiceKeepControls : SessionEffect
}

data class Transition(val state: SessionState, val effects: List<SessionEffect>)

object SessionMachine {
    fun on(state: SessionState, event: SessionEvent): Transition {
        val noSession = state == SessionState.NoSession || state is SessionState.StartBlocked
        return when (event) {
            SessionEvent.Start -> when {
                noSession -> Transition(SessionState.Capturing, listOf(SessionEffect.LaunchCapture))
                state == SessionState.Saving -> Transition(SessionState.SavingThenRestart, emptyList())
                else -> Transition(state, emptyList())
            }
            is SessionEvent.StartRefused ->
                if (noSession) Transition(SessionState.StartBlocked(event.refusal), listOf(SessionEffect.StopServiceKeepControls))
                else Transition(state, emptyList())
            SessionEvent.Stop -> when {
                noSession -> Transition(state, listOf(SessionEffect.StopServiceKeepControls))
                state == SessionState.Capturing -> Transition(SessionState.Saving, listOf(SessionEffect.SignalStop))
                else -> Transition(SessionState.Saving, emptyList())
            }
            SessionEvent.CaptureEnded -> when (state) {
                SessionState.SavingThenRestart -> Transition(SessionState.Capturing, listOf(SessionEffect.LaunchCapture))
                SessionState.Capturing, SessionState.Saving ->
                    Transition(SessionState.NoSession, listOf(SessionEffect.StopServiceKeepControls))
                else -> Transition(state, emptyList())
            }
        }
    }
}

enum class SessionAction { START, STOP, OPEN_APP }

/** What the Session's control surface (the notification) says, and the one action it offers. */
data class SessionControls(val status: String, val action: SessionAction) {
    companion object {
        fun of(state: SessionState): SessionControls = when (state) {
            SessionState.NoSession -> SessionControls("No Session", SessionAction.START)
            SessionState.Capturing -> SessionControls("Listening", SessionAction.STOP)
            SessionState.Saving -> SessionControls("Saving Turn", SessionAction.START)
            SessionState.SavingThenRestart -> SessionControls("Restarting", SessionAction.STOP)
            is SessionState.StartBlocked -> SessionControls(
                when (state.refusal) {
                    StartRefusal.MICROPHONE -> "Microphone permission needed. Open the app."
                    StartRefusal.NOTIFICATIONS -> "Notifications needed. Open the app."
                    StartRefusal.NOT_ALLOWED -> "Could not start from here. Open the app."
                },
                SessionAction.OPEN_APP,
            )
        }
    }
}

/**
 * Whether a Session may start; null when it may. The notification is the only
 * eyes-free way to stop (and later restart) a Session, so a Session never
 * starts without it.
 */
object StartGate {
    fun check(micGranted: Boolean, controlsVisible: Boolean): StartRefusal? = when {
        !micGranted -> StartRefusal.MICROPHONE
        !controlsVisible -> StartRefusal.NOTIFICATIONS
        else -> null
    }
}
