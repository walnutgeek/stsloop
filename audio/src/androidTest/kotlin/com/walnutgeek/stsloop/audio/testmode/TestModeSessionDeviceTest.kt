package com.walnutgeek.stsloop.audio.testmode

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.walnutgeek.stsloop.core.testmode.AudioMode
import com.walnutgeek.stsloop.core.testmode.MicInput
import com.walnutgeek.stsloop.core.testmode.MicSource
import com.walnutgeek.stsloop.core.testmode.SESSIONS_DIR
import com.walnutgeek.stsloop.core.testmode.TestConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Test mode must never leave the phone's audio route changed: after a test
 * Session in call mode, and after a setup that fails half way, the audio mode
 * is back to what it was. Runs without recording or TTS (prepare and close only).
 */
@RunWith(AndroidJUnit4::class)
class TestModeSessionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val am = context.getSystemService(AudioManager::class.java)
    private val corpus = File(context.cacheDir, "testmode-corpus")
    private val callMode = TestConfig(
        enabled = true, label = "device-test", micSource = MicSource.VOICE_COMMUNICATION,
        micInput = MicInput.BLUETOOTH, audioMode = AudioMode.IN_COMMUNICATION,
    )
    private var modeBefore = AudioManager.MODE_NORMAL

    @Before
    fun setUp() {
        corpus.deleteRecursively()
        modeBefore = am.mode
    }

    @After
    fun tearDown() {
        am.mode = modeBefore // belt and braces: never leave the phone in call mode
        corpus.deleteRecursively()
    }

    private fun log(): String = File(corpus, SESSIONS_DIR).listFiles()!!.single().readText()

    @Test
    fun callModeIsHeldDuringTheSessionAndRestoredAtClose() {
        val s = TestModeSession(context, callMode, "dev001", corpus, 16_000)
        s.prepare()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, am.mode)
        s.close()
        assertEquals(modeBefore, am.mode)
        val log = log()
        assertTrue(log, log.contains("\"event\":\"mode_set\""))
        assertTrue(log, log.contains("\"event\":\"session_end\""))
    }

    @Test
    fun aSetupThatFailsHalfWayPutsTheModeBackBeforeThrowing() {
        val s = TestModeSession(context, callMode, "dev002", corpus, 16_000)
        s.faultAfterRouteSet = { throw IllegalStateException("injected") }
        try {
            s.prepare()
            fail("prepare should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals("injected", e.message)
        }
        assertEquals(modeBefore, am.mode)
        s.close() // still safe, and still writes the end of the log
        assertEquals(modeBefore, am.mode)
        val log = log()
        assertTrue(log, log.contains("\"event\":\"prepare_failed\""))
        assertTrue(log, log.contains("\"event\":\"session_end\""))
    }

    @Test
    fun closeIsSafeToCallTwice() {
        val s = TestModeSession(context, callMode, "dev003", corpus, 16_000)
        s.prepare()
        s.close()
        s.close()
        assertEquals(modeBefore, am.mode)
    }
}
