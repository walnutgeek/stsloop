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
        assertEquals(Transition(NoSession, listOf(StopServiceKeepControls)), step(Saving, CaptureEnded))
    }

    @Test
    fun `Turn saved with a queued restart launches a new capture`() {
        assertEquals(Transition(Capturing, listOf(LaunchCapture)), step(SavingThenRestart, CaptureEnded))
    }

    @Test
    fun `capture failing on its own ends the Session`() {
        assertEquals(Transition(NoSession, listOf(StopServiceKeepControls)), step(Capturing, CaptureEnded))
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
        assertEquals(SessionControls("No Session", SessionAction.START), SessionControls.of(NoSession))
        assertEquals(SessionControls("Listening", SessionAction.STOP), SessionControls.of(Capturing))
        assertEquals(SessionControls("Saving Turn", SessionAction.START), SessionControls.of(Saving))
        assertEquals(SessionControls("Restarting", SessionAction.STOP), SessionControls.of(SavingThenRestart))
    }

    @Test
    fun `a blocked start points at the app, which can fix it`() {
        assertEquals(
            SessionControls("Microphone permission needed. Open the app.", SessionAction.OPEN_APP),
            SessionControls.of(StartBlocked(StartRefusal.MICROPHONE)),
        )
        assertEquals(
            SessionControls("Could not start from here. Open the app.", SessionAction.OPEN_APP),
            SessionControls.of(StartBlocked(StartRefusal.NOT_ALLOWED)),
        )
    }
}

class StartGateTest {
    @Test
    fun `start needs the microphone`() {
        assertEquals(StartRefusal.MICROPHONE, StartGate.check(micGranted = false, controlsVisible = true))
    }

    @Test
    fun `start needs visible controls, or the Session could not be stopped eyes-free`() {
        assertEquals(StartRefusal.NOTIFICATIONS, StartGate.check(micGranted = true, controlsVisible = false))
    }

    @Test
    fun `microphone is reported first when both are missing`() {
        assertEquals(StartRefusal.MICROPHONE, StartGate.check(micGranted = false, controlsVisible = false))
    }

    @Test
    fun `start allowed with both`() {
        assertNull(StartGate.check(micGranted = true, controlsVisible = true))
    }
}
