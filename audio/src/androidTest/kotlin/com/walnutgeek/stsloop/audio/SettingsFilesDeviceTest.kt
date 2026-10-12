package com.walnutgeek.stsloop.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.hub.HubDescriptor
import com.walnutgeek.stsloop.core.settings.BucketSettings
import com.walnutgeek.stsloop.core.settings.Edit
import com.walnutgeek.stsloop.core.settings.TimingsSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the settings screen saves (#48) is what a Session loads at its start
 * ([TurnCapture]'s loaders, as `SessionService` calls them). Uses the test's
 * own directory, never the app's `files/`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsFilesDeviceTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(target.cacheDir, "settings-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun save(edit: Edit) {
        assertTrue("expected a save, got $edit", edit is Edit.Save)
        val s = edit as Edit.Save
        File(dir, s.file).apply { parentFile!!.mkdirs() }.writeText(s.text)
    }

    private fun text(name: String) = File(dir, name).takeIf { it.exists() }?.readText()

    @Test
    fun aSessionLoadsWhatTheScreenSaved() {
        save(TimingsSettings(null).edit(trailingSilenceMs = 1200, focusTimeoutMs = 60_000))
        val timings = TurnCapture.loadTimings(dir)
        assertEquals(1200, timings.trailingSilenceMs)
        assertEquals(60_000, timings.focusTimeoutMs)

        val hub = Descriptors(listOf(HubDescriptor("herdr:x", "x agent", listOf("x"), "agent", emptyList(), emptyList())))
        val before = BucketSettings(null, null, hub)
        save(before.edit(before.rows.single { it.name == "errands" }, listOf("shops")))
        val mid = BucketSettings(text("buckets.json"), null, hub)
        save(mid.edit(mid.rows.single { it.name == "x agent" }, listOf("ex")))

        val registry = TurnCapture.registry(TurnCapture.loadBuckets(dir), hub, TurnCapture.loadHubAliases(dir))
        assertEquals(listOf("shops"), registry.named("errands")!!.aliases)
        assertEquals(listOf("ex"), registry.named("x agent")!!.aliases)
        assertEquals(mapOf("herdr:x" to listOf("ex")), TurnCapture.loadHubAliases(dir).byId)
    }
}
