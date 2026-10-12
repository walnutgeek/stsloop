package com.walnutgeek.stsloop.audio.hub

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.walnutgeek.stsloop.core.hub.Descriptors
import com.walnutgeek.stsloop.core.hub.HubRequests
import com.walnutgeek.stsloop.core.hub.HubStatus
import com.walnutgeek.stsloop.core.hub.HubStore
import com.walnutgeek.stsloop.core.hub.Pairing
import com.walnutgeek.stsloop.core.hub.Protocol
import com.walnutgeek.stsloop.core.hub.Reply
import com.walnutgeek.stsloop.core.hub.Request
import com.walnutgeek.stsloop.core.hub.Send
import com.walnutgeek.stsloop.core.hub.Sent
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
        assertEquals("the hub Bucket comes first; herdr's agents, if any, follow", "hub", snapshot!!.buckets.first().name)
        // The cache survives the connection, as it must survive a restart.
        c.stop()
        connection = null
        assertEquals(snapshot, HubStore(root).descriptors())
    }

    /**
     * A Corpus of this test's own (never the app's) goes up to the hub: a backlog
     * Turn at once, a Turn published later on [HubConnection.published], each
     * recorded as uploaded once the hub has checked and stored it. The hub's
     * Corpus directory then holds both (`uv run scripts/corpus_load.py <it>`).
     */
    @Test
    fun theCorpusUploadsWhileTheHubIsOnline() {
        val p = pairing
        assumeTrue("no hubUrl and hubToken arguments", p != null)
        val corpus = File(root, "corpus")
        val stamp = System.currentTimeMillis()
        val backlog = writeTurn(corpus, stamp, 40_000) // more than one 32 KiB piece
        store.pair(p!!)
        val c = HubConnection(context, store, "device test", corpus = corpus)
        connection = c
        c.start()
        awaitUploaded(backlog)
        val later = writeTurn(corpus, stamp + 1_000, 1_000)
        c.published()
        awaitUploaded(later)
        assertEquals(setOf(backlog, later), store.uploads().uploaded())
        // The upload only read the Turns.
        assertEquals(listOf("audio.wav", "turn.json"), File(corpus, backlog).list()!!.sorted())
    }

    private fun writeTurn(corpus: File, startedAtMs: Long, audioBytes: Int): String {
        val id = "%06x".format((startedAtMs and 0xffffff))
        val name = com.walnutgeek.stsloop.core.turnDirectoryName(startedAtMs, id)
        val dir = File(corpus, name).apply { mkdirs() }
        val audio = ByteArray(audioBytes) { (it * 31).toByte() }
        File(dir, "audio.wav").writeBytes(audio)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") { "%02x".format(it) }
        File(dir, "turn.json").writeText(
            """{"schema": 1, "id": "$id", "session_id": "devtst", "audio": {"file": "audio.wav", "sha256": "$sha"}, "kind": "unclassified", "app_version": "device-test"}""" + "\n",
        )
        return name
    }

    private fun awaitUploaded(name: String) {
        val until = System.nanoTime() + 15_000_000_000L
        while (name !in store.uploads().uploaded()) {
            assertTrue("$name not uploaded within 15 s; status ${connection?.status}", System.nanoTime() < until)
            Thread.sleep(100)
        }
    }

    @Test
    fun aRequestIsAnsweredWithAReplyToSay() {
        val p = pairing
        assumeTrue("no hubUrl and hubToken arguments", p != null)
        store.pair(p!!)
        val welcomed = CountDownLatch(1)
        val answered = CountDownLatch(1)
        var reply: Reply? = null
        val c = HubConnection(
            context, store, "device test",
            onDescriptors = { welcomed.countDown() },
            onReply = { reply = it; answered.countDown() },
        )
        connection = c
        c.start()
        assertTrue("not welcomed within 10 s; status ${c.status}", welcomed.await(10, TimeUnit.SECONDS))
        // "what's blocked": answered by herdr when the hub has it, and unknown_command when it does not.
        assertTrue(c.send(Request("d1", "hub", "blocked")))
        assertTrue("no reply within ${HubRequests.REPLY_TIMEOUT_MS} ms", answered.await(HubRequests.REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertEquals("d1", reply!!.id)
        assertTrue(reply!!.say.isNotBlank())
    }

    /**
     * A Draft's send and its answer, over OkHttp, and the same key again on a
     * new connection gets the same answer. It goes to an agent no hub has, so
     * it can never reach a real one, whatever hub the test runs against; the
     * hub's tests cover a delivery (`hub/tests/test_sends.py`).
     */
    @Test
    fun aSendIsAnsweredAndTheSameKeyAgainGetsTheSameAnswer() {
        val p = pairing
        assumeTrue("no hubUrl and hubToken arguments", p != null)
        store.pair(p!!)
        val send = Send(Protocol.newKey(), "herdr:device-test-no-such-agent", "a draft for nobody")
        fun sendOnce(): Sent {
            val welcomed = CountDownLatch(1)
            val answered = CountDownLatch(1)
            var answer: Sent? = null
            val c = HubConnection(
                context, store, "device test",
                onDescriptors = { welcomed.countDown() },
                onSent = { answer = it; answered.countDown() },
            )
            connection = c
            c.start()
            assertTrue("not welcomed within 10 s; status ${c.status}", welcomed.await(10, TimeUnit.SECONDS))
            assertTrue(c.send(send))
            assertTrue("no answer within 10 s", answered.await(10, TimeUnit.SECONDS))
            c.stop()
            connection = null
            return answer!!
        }
        val first = sendOnce()
        assertEquals(send.key, first.key)
        assertEquals("unknown_bucket", first.reason)
        assertEquals(first, sendOnce())
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
