package com.walnutgeek.stsloop.core.models

import com.walnutgeek.stsloop.core.models.DownloadPolicy.Action
import com.walnutgeek.stsloop.core.models.DownloadStatus.Failed
import com.walnutgeek.stsloop.core.models.DownloadStatus.Ready
import com.walnutgeek.stsloop.core.models.DownloadStatus.Running
import com.walnutgeek.stsloop.core.models.DownloadStatus.Waiting
import com.walnutgeek.stsloop.core.models.InstallProgress.Phase
import com.walnutgeek.stsloop.core.models.ModelInstallException.Reason
import com.walnutgeek.stsloop.core.models.NetworkKind.METERED
import com.walnutgeek.stsloop.core.models.NetworkKind.NONE
import com.walnutgeek.stsloop.core.models.NetworkKind.UNMETERED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DownloadPolicyTest {
    private val pending = 464_588_905L
    private val waiting = Waiting(pending)
    private val running = Running(InstallProgress(Phase.DOWNLOADING, 1, pending))

    private fun next(status: DownloadStatus, network: NetworkKind, meteredAllowed: Boolean = false, toFetch: Long = pending) =
        DownloadPolicy.next(status, network, meteredAllowed, toFetch)

    @Test
    fun `starts by itself on Wi-Fi`() {
        assertEquals(Action.START, next(waiting, UNMETERED))
    }

    @Test
    fun `waits on mobile data unless the user allowed it`() {
        assertEquals(Action.WAIT, next(waiting, METERED))
        assertEquals(Action.START, next(waiting, METERED, meteredAllowed = true))
    }

    @Test
    fun `waits with no network at all, even when mobile data is allowed`() {
        assertEquals(Action.WAIT, next(waiting, NONE, meteredAllowed = true))
    }

    @Test
    fun `stops a running download when Wi-Fi gives way to mobile data`() {
        assertEquals(Action.STOP, next(running, METERED))
        assertEquals(Action.NOTHING, next(running, METERED, meteredAllowed = true))
        assertEquals(Action.NOTHING, next(running, UNMETERED))
    }

    @Test
    fun `a dropped connection resumes when the network allows`() {
        val dropped = Failed(Reason.NETWORK, "interrupted", pending)
        assertEquals(Action.START, next(dropped, UNMETERED))
        assertEquals(Action.WAIT, next(dropped, METERED))
    }

    @Test
    fun `a bad hash or a full disk waits for the user, so a wrong pin cannot loop on 464 MB`() {
        assertEquals(Action.NOTHING, next(Failed(Reason.INTEGRITY, "mismatch", pending), UNMETERED))
        assertEquals(Action.NOTHING, next(Failed(Reason.STORAGE, "full", pending), UNMETERED))
    }

    @Test
    fun `with everything fetched, unpacking needs no network and is never stopped for one`() {
        val unpacking = Running(InstallProgress(Phase.UNPACKING, 1, 2))
        assertEquals(Action.NOTHING, next(unpacking, METERED, toFetch = 0))
        assertEquals(Action.START, next(Waiting(0), METERED, toFetch = 0))
        assertEquals(Action.START, next(Waiting(0), NONE, toFetch = 0))
        assertEquals(Action.START, next(Failed(Reason.CANCELLED, "stopped", 0), NONE, toFetch = 0))
    }

    @Test
    fun `nothing to do once ready`() {
        assertEquals(Action.NOTHING, next(Ready, UNMETERED))
        assertEquals(Action.NOTHING, next(Ready, METERED, meteredAllowed = true))
    }

    @Test
    fun `the status says what is happening, in MB`() {
        assertEquals("Speech models ready.", Ready.describe(UNMETERED))
        assertEquals("Speech models: 465 MB to download, waiting for Wi-Fi.", waiting.describe(METERED))
        assertEquals("Speech models: 465 MB to download, waiting for a network.", waiting.describe(NONE))
        assertEquals("Speech models: 465 MB to download.", waiting.describe(UNMETERED))
        assertEquals(
            "Downloading speech models: 120 of 465 MB.",
            Running(InstallProgress(Phase.DOWNLOADING, 120_000_000, pending)).describe(UNMETERED),
        )
        assertEquals("Checking the speech models.", Running(InstallProgress(Phase.VERIFYING, 0, 1)).describe(UNMETERED))
        assertEquals(
            "Unpacking speech models: 300 of 662 MB.",
            Running(InstallProgress(Phase.UNPACKING, 300_000_000, 661_919_416)).describe(UNMETERED),
        )
        assertEquals("Preparing the speech model download.", Running(null).describe(UNMETERED))
        assertEquals(
            "Speech model download stopped: no route. It resumes on Wi-Fi.",
            Failed(Reason.NETWORK, "no route", pending).describe(METERED),
        )
        assertEquals(
            "Speech model download failed: x does not match. Tap Retry.",
            Failed(Reason.INTEGRITY, "x does not match", pending).describe(UNMETERED),
        )
    }
}
