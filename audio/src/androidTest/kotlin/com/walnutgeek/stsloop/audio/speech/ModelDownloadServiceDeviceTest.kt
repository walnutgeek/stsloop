package com.walnutgeek.stsloop.audio.speech

import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.walnutgeek.stsloop.core.models.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [ModelDownloadService] (#67) in the test APK's own process: it always enters
 * the foreground, as startForegroundService() requires, and stops itself once
 * the download does not need it. The test APK bundles the models, so here
 * [ModelDownloads] is Ready and no download runs.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloadServiceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun withTheModelsInPlaceTheServiceStopsItselfAtOnce() {
        onMain {
            ModelDownloads.ensure(context)
            assertEquals(DownloadStatus.Ready, ModelDownloads.status)
            assertFalse(ModelDownloads.holdsForeground)
            ModelDownloadService.start(context)
        }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (running() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertFalse("the service is still running", running())
        // Past the platform's startForeground() deadline: a missed call would have crashed this process.
        Thread.sleep(11_000)
    }

    @Suppress("DEPRECATION") // still returns the caller's own services
    private fun running(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == ModelDownloadService::class.java.name }

    private fun onMain(block: () -> Unit) {
        val done = CountDownLatch(1)
        var error: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            } finally {
                done.countDown()
            }
        }
        check(done.await(5, TimeUnit.SECONDS)) { "main thread busy" }
        error?.let { throw it }
    }
}
