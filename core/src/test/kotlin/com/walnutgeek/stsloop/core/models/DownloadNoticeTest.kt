package com.walnutgeek.stsloop.core.models

import com.walnutgeek.stsloop.core.models.DownloadStatus.Failed
import com.walnutgeek.stsloop.core.models.DownloadStatus.Running
import com.walnutgeek.stsloop.core.models.InstallProgress.Phase
import com.walnutgeek.stsloop.core.models.ModelInstallException.Reason
import com.walnutgeek.stsloop.core.models.NetworkKind.UNMETERED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadNoticeTest {
    private val total = 464_588_905L

    @Test
    fun `downloading shows the screen's sentence and a percentage`() {
        val n = DownloadNotice.of(Running(InstallProgress(Phase.DOWNLOADING, 120_000_000, total)), UNMETERED)
        assertEquals(DownloadNotice("Downloading speech models: 120 of 465 MB.", DownloadNotice.Bar.Percent(25)), n)
    }

    @Test
    fun `unpacking counts its own percentage`() {
        val n = DownloadNotice.of(Running(InstallProgress(Phase.UNPACKING, 331_000_000, 662_000_000)), UNMETERED)
        assertEquals(DownloadNotice.Bar.Percent(50), n.bar)
    }

    @Test
    fun `preparing and checking have no measure yet`() {
        assertEquals(DownloadNotice.Bar.Busy, DownloadNotice.of(Running(null), UNMETERED).bar)
        assertEquals(DownloadNotice.Bar.Busy, DownloadNotice.of(Running(InstallProgress(Phase.VERIFYING, 0, 1)), UNMETERED).bar)
        assertEquals(DownloadNotice.Bar.Busy, DownloadNotice.of(Running(InstallProgress(Phase.DOWNLOADING, 0, 0)), UNMETERED).bar)
    }

    @Test
    fun `a dropped connection waiting for its retry shows no bar`() {
        val n = DownloadNotice.of(Failed(Reason.NETWORK, "connection reset", total), UNMETERED)
        assertEquals("Speech model download stopped: connection reset. It resumes on Wi-Fi.", n.text)
        assertEquals(DownloadNotice.Bar.None, n.bar)
    }

    @Test
    fun `posts at once when the kind of bar changes, otherwise at most once a second`() {
        val a = DownloadNotice("Downloading speech models: 1 of 465 MB.", DownloadNotice.Bar.Percent(0))
        val b = DownloadNotice("Downloading speech models: 2 of 465 MB.", DownloadNotice.Bar.Percent(0))
        val checking = DownloadNotice("Checking the speech models.", DownloadNotice.Bar.Busy)
        assertTrue(DownloadNotice.due(null, a, 0))
        assertFalse(DownloadNotice.due(a, a, 5_000), "nothing changed")
        assertFalse(DownloadNotice.due(a, b, 999))
        assertTrue(DownloadNotice.due(a, b, 1_000))
        assertTrue(DownloadNotice.due(b, checking, 10))
    }
}
