package com.walnutgeek.stsloop.audio.hub

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.hub.HubStatus
import com.walnutgeek.stsloop.core.hub.HubStore
import com.walnutgeek.stsloop.core.hub.Pairing
import com.walnutgeek.stsloop.core.hub.Protocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The hub connection on the phone, against a real `stsloop-hub serve`: OkHttp,
 * the Bearer token, the handshake, and the Descriptor cache. Its files live in
 * this test APK's own storage, never the app's.
 *
 * Needs a hub the phone can reach, given as its pairing's url and token:
 *
 *     STSLOOP_HUB_HOME=/tmp/hub stsloop-hub init --host 127.0.0.1
 *     STSLOOP_HUB_HOME=/tmp/hub stsloop-hub serve
 *     adb reverse tcp:7717 tcp:7717
 *     ./gradlew :audio:connectedAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.walnutgeek.stsloop.audio.hub.HubConnectionDeviceTest \
 *       -Pandroid.testInstrumentationRunnerArguments.hubUrl=ws://127.0.0.1:7717/ \
 *       -Pandroid.testInstrumentationRunnerArguments.hubToken=<the token in /tmp/hub/hub.json>
 *
 * Without them the tests that need a hub are skipped.
 */
@RunWith(AndroidJUnit4::class)
class HubConnectionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: HubStore
    private var connection: HubConnection? = null

    private val pairing: Pairing? by lazy {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("hubUrl")
        val token = args.getString("hubToken")
        if (url == null || token == null) null else Pairing(Protocol.VERSION, url, token)
    }

    @Before
    fun setUp() {
        root = File(context.cacheDir, "hub-test-${System.nanoTime()}").apply { mkdirs() }
        store = HubStore(root)
    }

    @After
    fun tearDown() {
        connection?.stop()
        root.deleteRecursively()
    }

    private fun connect(p: Pairing, until: (HubStatus) -> Boolean): Pair<HubConnection, CountDownLatch> {
        store.pair(p)
        val reached = CountDownLatch(1)
        val c = HubConnection(context, store, "device test", onStatus = { if (until(it)) reached.countDown() })
        connection = c
        c.start()
        return c to reached
    }

    @Test
    fun aPairedPhoneIsWelcomedAndCachesTheHubsDescriptors() {
        val p = pairing
        assumeTrue("no hubUrl and hubToken arguments", p != null)
        val got = CountDownLatch(1)
        var snapshot: Descriptors? = null
        store.pair(p!!)
        val c = HubConnection(context, store, "device test", onDescriptors = { snapshot = it; got.countDown() })
        connection = c
        c.start()
        assertTrue("no Descriptors within 10 s; status ${c.status}", got.await(10, TimeUnit.SECONDS))
        assertTrue(c.online)
        assertEquals(listOf("hub"), snapshot!!.buckets.map { it.name })
        // The cache survives the connection, as it must survive a restart.
        c.stop()
        connection = null
        assertEquals(snapshot, HubStore(root).descriptors())
    }

    @Test
    fun aWrongTokenIsUnauthorisedAndNotRetried() {
        val p = pairing
        assumeTrue("no hubUrl and hubToken arguments", p != null)
        val (c, reached) = connect(p!!.copy(token = "w".repeat(43))) { it == HubStatus.Unauthorised }
        assertTrue("status ${c.status}", reached.await(10, TimeUnit.SECONDS))
        Thread.sleep(1_500)
        assertEquals(HubStatus.Unauthorised, c.status)
    }

    @Test
    fun anUnreachableHubIsOfflineAndStartingNeverWaitsForIt() {
        store.pair(Pairing(Protocol.VERSION, "ws://127.0.0.1:9/", "n".repeat(43)))
        val reached = CountDownLatch(1)
        val c = HubConnection(context, store, "device test", onStatus = { if (it is HubStatus.Offline) reached.countDown() })
        connection = c
        val t0 = System.nanoTime()
        c.start()
        val startMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("start took $startMs ms", startMs < 200)
        assertTrue("status ${c.status}", reached.await(10, TimeUnit.SECONDS))
        assertTrue(!c.online)
    }
}
