package com.walnutgeek.stsloop.core

import com.walnutgeek.stsloop.core.SessionEffect.LaunchCapture
import com.walnutgeek.stsloop.core.SessionEffect.SignalStop
import com.walnutgeek.stsloop.core.SessionEffect.StopServiceKeepControls
import com.walnutgeek.stsloop.core.SessionEvent.CaptureEnded
import com.walnutgeek.stsloop.core.SessionEvent.Start
import com.walnutgeek.stsloop.core.SessionEvent.StartRefused
import com.walnutgeek.stsloop.core.SessionEvent.Stop
import com.walnutgeek.stsloop.core.SessionState.Capturing
import com.walnutgeek.stsloop.core.SessionState.NoSession
import com.walnutgeek.stsloop.core.SessionState.Saving
import com.walnutgeek.stsloop.core.SessionState.SavingThenRestart
import com.walnutgeek.stsloop.core.SessionState.StartBlocked
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SessionMachineTest {
    private fun step(state: SessionState, event: SessionEvent) = SessionMachine.on(state, event)

    @Test
    fun `start with no Session launches capture`() {
        assertEquals(Transition(Capturing, listOf(LaunchCapture)), step(NoSession, Start))
    }

    @Test
    fun `start while capturing is a no-op`() {
        assertEquals(Transition(Capturing, emptyList()), step(Capturing, Start))
    }

    @Test
    fun `stop while capturing signals the capture thread and waits for the Turn`() {
        assertEquals(Transition(Saving, listOf(SignalStop)), step(Capturing, Stop))
    }

    @Test
    fun `stop with no Session stops the service`() {
        assertEquals(Transition(NoSession, listOf(StopServiceKeepControls)), step(NoSession, Stop))
    }

    @Test
    fun `start while the Turn is being saved queues a restart`() {
        assertEquals(Transition(SavingThenRestart, emptyList()), step(Saving, Start))
    }

    @Test
    fun `stop cancels a queued restart`() {
        assertEquals(Transition(Saving, emptyList()), step(SavingThenRestart, Stop))
    }

    @Test
    fun `Turn saved after stop ends the Session and keeps Start reachable`() {
        assertEquals(Transition(NoSession, listOf(StopServiceKeepControls), sessionEnded = true), step(Saving, CaptureEnded))
    }

    @Test
    fun `Turn saved with a queued restart ends that Session and launches a new capture`() {
        assertEquals(Transition(Capturing, listOf(LaunchCapture), sessionEnded = true), step(SavingThenRestart, CaptureEnded))
    }

    @Test
    fun `capture failing on its own ends the Session`() {
        assertEquals(Transition(NoSession, listOf(StopServiceKeepControls), sessionEnded = true), step(Capturing, CaptureEnded))
    }

    @Test
    fun `only the capture ending ends a Session, not the Stop that asks for it`() {
        assertEquals(false, step(Capturing, Stop).sessionEnded)
        assertEquals(false, step(SavingThenRestart, Stop).sessionEnded)
        assertEquals(false, step(NoSession, Stop).sessionEnded)
        assertEquals(false, step(NoSession, Start).sessionEnded)
        assertEquals(false, step(NoSession, StartRefused(StartRefusal.MODELS)).sessionEnded)
    }

    @Test
    fun `a stray capture-ended with no Session changes nothing`() {
        assertEquals(Transition(NoSession, emptyList()), step(NoSession, CaptureEnded))
    }

    @Test
    fun `a refused start says why and stops the service`() {
        val blocked = StartBlocked(StartRefusal.MICROPHONE)
        assertEquals(Transition(blocked, listOf(StopServiceKeepControls)), step(NoSession, StartRefused(StartRefusal.MICROPHONE)))
    }

    @Test
    fun `start after a refusal behaves like a fresh start`() {
        assertEquals(Transition(Capturing, listOf(LaunchCapture)), step(StartBlocked(StartRefusal.MICROPHONE), Start))
    }

    @Test
    fun `a refused start leaves a running Session alone`() {
        assertEquals(Transition(Capturing, emptyList()), step(Capturing, StartRefused(StartRefusal.MICROPHONE)))
    }

    @Test
    fun `only capturing and saving count as an active Session`() {
        assertEquals(false, NoSession.isActive)
        assertEquals(false, StartBlocked(StartRefusal.MICROPHONE).isActive)
        assertEquals(true, Capturing.isActive)
        assertEquals(true, Saving.isActive)
        assertEquals(true, SavingThenRestart.isActive)
    }

    @Test
    fun `controls offer exactly one action, the one that moves things forward`() {
        assertEquals(SessionControls("No Session", SessionAction.START, SessionIcon.IDLE), SessionControls.of(NoSession))
        assertEquals(SessionControls("Listening", SessionAction.STOP, SessionIcon.MIC), SessionControls.of(Capturing))
        assertEquals(SessionControls("Saving Turn", SessionAction.START, SessionIcon.MIC), SessionControls.of(Saving))
        assertEquals(SessionControls("Restarting", SessionAction.STOP, SessionIcon.MIC), SessionControls.of(SavingThenRestart))
    }

    @Test
    fun `the mic icon shows only while a Session is active`() {
        assertEquals(SessionIcon.IDLE, SessionControls.of(NoSession).icon)
        assertEquals(SessionIcon.IDLE, SessionControls.of(StartBlocked(StartRefusal.MICROPHONE)).icon)
        for (active in listOf(Capturing, Saving, SavingThenRestart)) assertEquals(SessionIcon.MIC, SessionControls.of(active).icon)
    }

    @Test
    fun `a blocked start points at the app, which can fix it`() {
        assertEquals(
            SessionControls("Microphone permission needed. Open the app.", SessionAction.OPEN_APP, SessionIcon.IDLE),
            SessionControls.of(StartBlocked(StartRefusal.MICROPHONE)),
        )
        assertEquals(
            SessionControls("Could not start from here. Open the app.", SessionAction.OPEN_APP, SessionIcon.IDLE),
            SessionControls.of(StartBlocked(StartRefusal.NOT_ALLOWED)),
        )
        assertEquals(
            SessionControls("Speech models not downloaded yet. Open the app.", SessionAction.OPEN_APP, SessionIcon.IDLE),
            SessionControls.of(StartBlocked(StartRefusal.MODELS)),
        )
    }
}

class SessionScreenTest {
    @Test
    fun `with no Session the screen offers Start`() {
        assertEquals(SessionScreen("Start Session", true, "No Session."), SessionScreen.of(NoSession))
        assertEquals(SessionScreen("Start Session", true, "No Session."), SessionScreen.of(StartBlocked(StartRefusal.MODELS)))
    }

    @Test
    fun `a capturing Session offers Stop`() {
        assertEquals(SessionScreen("Stop Session", true, "Session active."), SessionScreen.of(Capturing))
    }

    @Test
    fun `after Stop the screen says the last Turn is being saved, not that the Session is still on`() {
        assertEquals(SessionScreen("Stopping…", false, "Saving the last Turn."), SessionScreen.of(Saving))
    }

    @Test
    fun `a queued restart can still be stopped`() {
        assertEquals(SessionScreen("Stop Session", true, "Saving the last Turn, then starting again."), SessionScreen.of(SavingThenRestart))
    }
}

class StartGateTest {
    @Test
    fun `start needs the microphone`() {
        assertEquals(StartRefusal.MICROPHONE, StartGate.check(micGranted = false, controlsVisible = true, modelsReady = true))
    }

    @Test
    fun `start needs visible controls, or the Session could not be stopped eyes-free`() {
        assertEquals(StartRefusal.NOTIFICATIONS, StartGate.check(micGranted = true, controlsVisible = false, modelsReady = true))
    }

    @Test
    fun `microphone is reported first when all are missing`() {
        assertEquals(StartRefusal.MICROPHONE, StartGate.check(micGranted = false, controlsVisible = false, modelsReady = false))
    }

    @Test
    fun `start needs the speech models, which a release build downloads on first run`() {
        assertEquals(StartRefusal.MODELS, StartGate.check(micGranted = true, controlsVisible = true, modelsReady = false))
    }

    @Test
    fun `missing models are reported last, as the app fixes them unasked`() {
        assertEquals(StartRefusal.NOTIFICATIONS, StartGate.check(micGranted = true, controlsVisible = false, modelsReady = false))
    }

    @Test
    fun `start allowed with all three`() {
        assertNull(StartGate.check(micGranted = true, controlsVisible = true, modelsReady = true))
    }
}
