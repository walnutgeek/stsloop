package com.walnutgeek.stsloop.core

import com.walnutgeek.stsloop.core.SessionEffect.LaunchCapture
import com.walnutgeek.stsloop.core.SessionEffect.ShowIdleControls
import com.walnutgeek.stsloop.core.SessionEffect.SignalStop
import com.walnutgeek.stsloop.core.SessionEvent.CaptureEnded
import com.walnutgeek.stsloop.core.SessionEvent.Start
import com.walnutgeek.stsloop.core.SessionEvent.Stop
import com.walnutgeek.stsloop.core.SessionState.Capturing
import com.walnutgeek.stsloop.core.SessionState.Finishing
import com.walnutgeek.stsloop.core.SessionState.Idle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionMachineTest {
    private fun step(state: SessionState, event: SessionEvent) = SessionMachine.on(state, event)

    @Test
    fun `start from idle launches capture`() {
        assertEquals(Transition(Capturing, listOf(LaunchCapture)), step(Idle, Start))
    }

    @Test
    fun `start while capturing is a no-op`() {
        assertEquals(Transition(Capturing, emptyList()), step(Capturing, Start))
    }

    @Test
    fun `stop while capturing signals the capture thread and waits for the Turn`() {
        assertEquals(Transition(Finishing(restart = false), listOf(SignalStop)), step(Capturing, Stop))
    }

    @Test
    fun `stop while idle shows idle controls`() {
        assertEquals(Transition(Idle, listOf(ShowIdleControls)), step(Idle, Stop))
    }

    @Test
    fun `start while the Turn is being written queues a restart`() {
        assertEquals(Transition(Finishing(restart = true), emptyList()), step(Finishing(restart = false), Start))
    }

    @Test
    fun `stop cancels a queued restart`() {
        assertEquals(Transition(Finishing(restart = false), emptyList()), step(Finishing(restart = true), Stop))
    }

    @Test
    fun `capture ending after stop goes idle and keeps Start reachable`() {
        assertEquals(Transition(Idle, listOf(ShowIdleControls)), step(Finishing(restart = false), CaptureEnded))
    }

    @Test
    fun `capture ending with a queued restart launches a new capture`() {
        assertEquals(Transition(Capturing, listOf(LaunchCapture)), step(Finishing(restart = true), CaptureEnded))
    }

    @Test
    fun `capture failing on its own goes idle`() {
        assertEquals(Transition(Idle, listOf(ShowIdleControls)), step(Capturing, CaptureEnded))
    }

    @Test
    fun `a stray capture-ended while idle changes nothing`() {
        assertEquals(Transition(Idle, emptyList()), step(Idle, CaptureEnded))
    }

    @Test
    fun `only idle counts as no Session`() {
        assertEquals(false, Idle.isActive)
        assertEquals(true, Capturing.isActive)
        assertEquals(true, Finishing(restart = false).isActive)
    }

    @Test
    fun `controls offer exactly one action, the one that changes the state`() {
        assertEquals(SessionControls("No Session", SessionAction.START), SessionControls.of(Idle))
        assertEquals(SessionControls("Listening", SessionAction.STOP), SessionControls.of(Capturing))
        assertEquals(SessionControls("Saving Turn", SessionAction.START), SessionControls.of(Finishing(restart = false)))
        assertEquals(SessionControls("Restarting", SessionAction.STOP), SessionControls.of(Finishing(restart = true)))
    }
}

class StartGateTest {
    @Test
    fun `start needs the microphone`() {
        assertEquals(StartGate.Verdict.NEEDS_MICROPHONE, StartGate.check(micGranted = false, controlsVisible = true))
    }

    @Test
    fun `start needs visible controls, or the Session could not be stopped eyes-free`() {
        assertEquals(StartGate.Verdict.NEEDS_NOTIFICATIONS, StartGate.check(micGranted = true, controlsVisible = false))
    }

    @Test
    fun `microphone is reported first when both are missing`() {
        assertEquals(StartGate.Verdict.NEEDS_MICROPHONE, StartGate.check(micGranted = false, controlsVisible = false))
    }

    @Test
    fun `start allowed with both`() {
        assertEquals(StartGate.Verdict.ALLOWED, StartGate.check(micGranted = true, controlsVisible = true))
    }
}
