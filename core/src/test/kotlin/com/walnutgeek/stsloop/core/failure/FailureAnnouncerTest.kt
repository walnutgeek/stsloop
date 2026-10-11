package com.walnutgeek.stsloop.core.failure

import com.walnutgeek.stsloop.core.failure.SilentFailure.MIC_SILENCED
import com.walnutgeek.stsloop.core.failure.SilentFailure.MIC_SILENT
import com.walnutgeek.stsloop.core.failure.SilentFailure.SILENT_RECORDING
import com.walnutgeek.stsloop.core.failure.SilentFailure.SPEECH_UNHEARD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Each failure is said once when it starts; flapping and overlapping causes do not become spam. */
class FailureAnnouncerTest {
    private var now = 0L
    private val said = mutableListOf<SilentFailure>()
    private val log = mutableListOf<String>()
    private val announcer = FailureAnnouncer(clockMs = { now }, repeatAfterMs = 60_000, say = { said += it }, listener = object : FailureAnnouncer.Listener {
        override fun announced(failure: SilentFailure) { log += "announced $failure" }
        override fun suppressed(failure: SilentFailure, because: String) { log += "suppressed $failure: $because" }
        override fun cleared(failure: SilentFailure) { log += "cleared $failure" }
    })

    @Test
    fun `a failure is said when it starts`() {
        announcer.set(MIC_SILENT, true)
        assertEquals(listOf(MIC_SILENT), said)
        assertTrue(announcer.active(MIC_SILENT))
    }

    @Test
    fun `a failure that persists is said only once`() {
        repeat(5) { now += 120_000; announcer.set(SPEECH_UNHEARD, true) }
        assertEquals(listOf(SPEECH_UNHEARD), said)
    }

    @Test
    fun `clearing is not said, only logged`() {
        announcer.set(MIC_SILENT, true)
        announcer.set(MIC_SILENT, false)
        assertEquals(listOf(MIC_SILENT), said)
        assertEquals(listOf("announced MIC_SILENT", "cleared MIC_SILENT"), log)
        assertFalse(announcer.active(MIC_SILENT))
    }

    @Test
    fun `clearing what never started is nothing`() {
        announcer.set(MIC_SILENT, false)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `a failure that recurs after the repeat interval is said again`() {
        announcer.set(MIC_SILENT, true)
        now += 10_000
        announcer.set(MIC_SILENT, false)
        now += 50_000 // 60 s after it was said
        announcer.set(MIC_SILENT, true)
        assertEquals(listOf(MIC_SILENT, MIC_SILENT), said)
    }

    @Test
    fun `a failure that flaps within the repeat interval is said once`() {
        repeat(10) {
            announcer.set(MIC_SILENT, true)
            now += 5_000
            announcer.set(MIC_SILENT, false)
            now += 1_000
        }
        assertEquals(listOf(MIC_SILENT), said)
        assertTrue(log.contains("suppressed MIC_SILENT: said 6 s ago"))
    }

    @Test
    fun `the repeat interval is per failure`() {
        announcer.set(MIC_SILENT, true)
        announcer.set(SPEECH_UNHEARD, true)
        assertEquals(listOf(MIC_SILENT, SPEECH_UNHEARD), said)
    }

    @Test
    fun `zeros from a silenced client are not said twice`() {
        announcer.set(MIC_SILENCED, true)
        now += 3_000
        announcer.set(MIC_SILENT, true)
        announcer.set(SILENT_RECORDING, true)
        assertEquals(listOf(MIC_SILENCED), said)
        assertTrue(log.contains("suppressed MIC_SILENT: explained by MIC_SILENCED"))
    }

    @Test
    fun `a silent Recording is explained by a silent mic`() {
        announcer.set(MIC_SILENT, true)
        announcer.set(SILENT_RECORDING, true)
        assertEquals(listOf(MIC_SILENT), said)
    }

    @Test
    fun `a silent Recording on a mic that is not known to be silent is said`() {
        announcer.set(SILENT_RECORDING, true)
        assertEquals(listOf(SILENT_RECORDING), said)
    }

    @Test
    fun `a failure suppressed as explained is not said later when its explanation clears`() {
        announcer.set(MIC_SILENCED, true)
        announcer.set(MIC_SILENT, true)
        announcer.set(MIC_SILENCED, false)
        assertEquals(listOf(MIC_SILENCED), said)
    }

    @Test
    fun `every failure has words to say`() {
        for (f in SilentFailure.entries) assertTrue(f.spoken.endsWith("."), "$f")
    }
}
