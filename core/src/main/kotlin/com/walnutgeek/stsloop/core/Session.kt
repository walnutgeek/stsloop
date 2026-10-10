package com.walnutgeek.stsloop.core

/**
 * Lifecycle of a Session as seen by the service that holds the microphone.
 *
 * [Finishing] covers the gap after Stop while the capture thread is still
 * publishing its Turn; a Start in that gap is remembered rather than lost.
 */
sealed interface SessionState {
    val isActive: Boolean get() = this != Idle

    data object Idle : SessionState
    data object Capturing : SessionState
    data class Finishing(val restart: Boolean) : SessionState
}

sealed interface SessionEvent {
    data object Start : SessionEvent
    data object Stop : SessionEvent

    /** The capture thread has published (or abandoned) its Turn. */
    data object CaptureEnded : SessionEvent
}

sealed interface SessionEffect {
    /** Begin a new capture into a new Turn. */
    data object LaunchCapture : SessionEffect

    /** Ask the running capture to stop and publish its Turn. */
    data object SignalStop : SessionEffect

    /** Release the microphone and leave the Start control reachable. */
    data object ShowIdleControls : SessionEffect
}

data class Transition(val state: SessionState, val effects: List<SessionEffect>)

object SessionMachine {
    fun on(state: SessionState, event: SessionEvent): Transition = when (event) {
        SessionEvent.Start -> when (state) {
            SessionState.Idle -> Transition(SessionState.Capturing, listOf(SessionEffect.LaunchCapture))
            SessionState.Capturing -> Transition(state, emptyList())
            is SessionState.Finishing -> Transition(SessionState.Finishing(restart = true), emptyList())
        }
        SessionEvent.Stop -> when (state) {
            SessionState.Idle -> Transition(state, listOf(SessionEffect.ShowIdleControls))
            SessionState.Capturing -> Transition(SessionState.Finishing(restart = false), listOf(SessionEffect.SignalStop))
            is SessionState.Finishing -> Transition(SessionState.Finishing(restart = false), emptyList())
        }
        SessionEvent.CaptureEnded -> when (state) {
            SessionState.Idle -> Transition(state, emptyList())
            SessionState.Capturing -> Transition(SessionState.Idle, listOf(SessionEffect.ShowIdleControls))
            is SessionState.Finishing ->
                if (state.restart) Transition(SessionState.Capturing, listOf(SessionEffect.LaunchCapture))
                else Transition(SessionState.Idle, listOf(SessionEffect.ShowIdleControls))
        }
    }
}

enum class SessionAction { START, STOP }

/** What the Session's control surface (the notification) says, and the one action it offers. */
data class SessionControls(val status: String, val action: SessionAction) {
    companion object {
        fun of(state: SessionState): SessionControls = when (state) {
            SessionState.Idle -> SessionControls("No Session", SessionAction.START)
            SessionState.Capturing -> SessionControls("Listening", SessionAction.STOP)
            is SessionState.Finishing ->
                if (state.restart) SessionControls("Restarting", SessionAction.STOP)
                else SessionControls("Saving Turn", SessionAction.START)
        }
    }
}

/**
 * Whether a Session may start. The notification is the only eyes-free way to
 * stop (and later restart) a Session, so a Session never starts without it.
 */
object StartGate {
    enum class Verdict { ALLOWED, NEEDS_MICROPHONE, NEEDS_NOTIFICATIONS }

    fun check(micGranted: Boolean, controlsVisible: Boolean): Verdict = when {
        !micGranted -> Verdict.NEEDS_MICROPHONE
        !controlsVisible -> Verdict.NEEDS_NOTIFICATIONS
        else -> Verdict.ALLOWED
    }
}
