package com.walnutgeek.stsloop.core.models

import com.walnutgeek.stsloop.core.models.ModelInstallException.Reason
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

class ModelStoreTest {
    @TempDir
    lateinit var root: File

    private val server = TestServer()

    @AfterEach
    fun stop() = server.close()

    // A small stand-in for the speech models: one plain file (the VAD) and a
    // tar.bz2 holding a model directory with a stray file we do not want.
    private val vad = Random(1).nextBytes(50_000)
    private val encoder = Random(2).nextBytes(300_000)
    private val tokens = "a 0\nb 1\n".toByteArray()
    private val readme = "not a model".toByteArray()
    private val tarball = tarBz2(
        "./stt-model/" to null,
        "./stt-model/encoder.onnx" to encoder,
        "./stt-model/README.md" to readme,
        "./stt-model/tokens.txt" to tokens,
    )

    private val vadFile = ModelFile("vad.onnx", vad.size.toLong(), sha(vad))
    private val encoderFile = ModelFile("stt-model/encoder.onnx", encoder.size.toLong(), sha(encoder))
    private val tokensFile = ModelFile("stt-model/tokens.txt", tokens.size.toLong(), sha(tokens))

    private fun manifest(
        tarSha: String = sha(tarball),
        members: List<ModelFile> = listOf(encoderFile, tokensFile),
        vadSha: String = vadFile.sha256,
    ) = ModelManifest(
        listOf(
            ModelDownload.TarBz2(server.url("/stt-model.tar.bz2"), "stt-model.tar.bz2", tarball.size.toLong(), tarSha, members),
            ModelDownload.Single(server.url("/vad.onnx"), vadFile.copy(sha256 = vadSha)),
        ),
    )

    private fun store(m: ModelManifest = manifest(), free: Long = Long.MAX_VALUE) =
        ModelStore(root, m, UrlConnectionHttp(), freeBytes = { free })

    init {
        server.serve("/stt-model.tar.bz2", tarball)
        server.serve("/vad.onnx", vad)
    }

    @Test
    fun `a fresh store is not ready and misses every file`() {
        val s = store()
        assertFalse(s.isReady)
        assertEquals(listOf(encoderFile, tokensFile, vadFile), s.missing())
        assertEquals((tarball.size + vad.size).toLong(), s.pendingDownloadBytes())
    }

    @Test
    fun `install fetches, verifies and unpacks every file, then cleans up`() {
        val s = store()
        s.install()
        assertTrue(s.isReady)
        assertArrayEquals(vad, File(root, "vad.onnx").readBytes())
        assertArrayEquals(encoder, File(root, "stt-model/encoder.onnx").readBytes())
        assertArrayEquals(tokens, File(root, "stt-model/tokens.txt").readBytes())
        assertFalse(File(root, "stt-model/README.md").exists(), "only pinned members are extracted")
        assertEquals(setOf("stt-model", "vad.onnx"), root.list()!!.toSet(), "no download or staging left behind")
        assertEquals(0, s.pendingDownloadBytes())
    }

    @Test
    fun `installed models survive a restart and are not fetched again`() {
        store().install()
        server.requests.clear()
        val again = store()
        assertTrue(again.isReady)
        again.install()
        assertEquals(emptyList<TestServer.Request>(), server.requests)
    }

    @Test
    fun `an interrupted download resumes from where it stopped`() {
        server.cutAfter = 100_000
        val e = assertThrows<ModelInstallException> { store().install() }
        assertEquals(Reason.NETWORK, e.reason)
        assertFalse(store().isReady)

        server.requests.clear()
        store().install()
        assertTrue(store().isReady)
        assertEquals("bytes=100000-", server.requests.first { it.path == "/stt-model.tar.bz2" }.range)
        assertArrayEquals(encoder, File(root, "stt-model/encoder.onnx").readBytes())
    }

    @Test
    fun `a server that ignores the range restarts the download cleanly`() {
        server.cutAfter = 100_000
        assertThrows<ModelInstallException> { store().install() }
        server.ignoreRange = true
        store().install()
        assertTrue(store().isReady)
        assertArrayEquals(encoder, File(root, "stt-model/encoder.onnx").readBytes())
    }

    @Test
    fun `a partial download longer than the pinned size is discarded`() {
        val part = File(root, ".downloads/stt-model.tar.bz2.part")
        part.parentFile.mkdirs()
        part.writeBytes(ByteArray(tarball.size + 10))
        store().install()
        assertTrue(store().isReady)
        assertEquals(null, server.requests.first { it.path == "/stt-model.tar.bz2" }.range)
    }

    @Test
    fun `a download that fails its pinned hash is deleted and nothing is installed`() {
        val e = assertThrows<ModelInstallException> { store(manifest(vadSha = "0".repeat(64))).install() }
        assertEquals(Reason.INTEGRITY, e.reason)
        assertFalse(File(root, "vad.onnx").exists())
        assertFalse(File(root, ".downloads/vad.onnx.part").exists())
        assertFalse(File(root, ".downloads/vad.onnx").exists())
        // The good pin then installs cleanly from scratch.
        store().install()
        assertTrue(store().isReady)
    }

    @Test
    fun `a tarball that fails its hash is not unpacked`() {
        val e = assertThrows<ModelInstallException> { store(manifest(tarSha = "1".repeat(64))).install() }
        assertEquals(Reason.INTEGRITY, e.reason)
        assertFalse(File(root, "stt-model/encoder.onnx").exists())
    }

    @Test
    fun `a member that fails its pinned hash is not installed`() {
        val bad = encoderFile.copy(sha256 = "2".repeat(64))
        val e = assertThrows<ModelInstallException> { store(manifest(members = listOf(bad, tokensFile))).install() }
        assertEquals(Reason.INTEGRITY, e.reason)
        assertFalse(File(root, "stt-model/encoder.onnx").exists())
    }

    @Test
    fun `a member missing from the tarball is an integrity failure`() {
        val ghost = ModelFile("stt-model/joiner.onnx", 5, "3".repeat(64))
        val e = assertThrows<ModelInstallException> {
            store(manifest(members = listOf(encoderFile, tokensFile, ghost))).install()
        }
        assertEquals(Reason.INTEGRITY, e.reason)
        assertTrue(e.message!!.contains("stt-model/joiner.onnx"))
    }

    @Test
    fun `a file lost after install is fetched and unpacked again, alone`() {
        store().install()
        File(root, "stt-model/tokens.txt").delete()
        val s = store()
        assertEquals(listOf(tokensFile), s.missing())
        server.requests.clear()
        s.install()
        assertTrue(s.isReady)
        assertEquals(listOf("/stt-model.tar.bz2"), server.requests.map { it.path })
    }

    @Test
    fun `a file of the wrong size counts as missing and is replaced`() {
        store().install()
        File(root, "vad.onnx").writeBytes(vad.copyOf(10))
        val s = store()
        assertEquals(listOf(vadFile), s.missing())
        s.install()
        assertArrayEquals(vad, File(root, "vad.onnx").readBytes())
    }

    @Test
    fun `cancelling keeps the partial download for a later resume`() {
        var seen = 0L
        val e = assertThrows<ModelInstallException> {
            store().install(onProgress = { seen = it.done }, cancelled = { seen > 0 })
        }
        assertEquals(Reason.CANCELLED, e.reason)
        assertTrue(File(root, ".downloads/stt-model.tar.bz2.part").length() > 0)
        store().install()
        assertTrue(store().isReady)
    }

    @Test
    fun `too little free space fails before anything is fetched`() {
        val e = assertThrows<ModelInstallException> { store(free = 1000).install() }
        assertEquals(Reason.STORAGE, e.reason)
        assertTrue(e.message!!.contains("free"), e.message)
        assertEquals(emptyList<TestServer.Request>(), server.requests)
    }

    @Test
    fun `the space needed counts the download and the unpacked files`() {
        val s = store()
        assertEquals(
            (tarball.size + vad.size).toLong() + encoder.size + tokens.size,
            s.bytesNeeded(),
        )
    }

    @Test
    fun `files from another manifest are pruned at install`() {
        File(root, "old-model").mkdirs()
        File(root, "old-model/encoder.onnx").writeText("stale")
        File(root, ".downloads").mkdirs()
        File(root, ".downloads/old.tar.bz2.part").writeText("stale")
        store().install()
        assertFalse(File(root, "old-model").exists())
        assertFalse(File(root, ".downloads/old.tar.bz2.part").exists())
    }

    @Test
    fun `a download left behind once its files are installed is pruned`() {
        store().install()
        val left = File(root, ".downloads/stt-model.tar.bz2")
        left.parentFile.mkdirs()
        left.writeBytes(tarball) // as if the process died between the last move and the delete
        store().prune()
        assertFalse(left.exists())
    }

    @Test
    fun `a 206 without a Content-Range restarts the download from zero`() {
        server.cutAfter = 100_000
        assertThrows<ModelInstallException> { store().install() }
        server.omitContentRange = true
        store().install()
        assertTrue(store().isReady)
        assertArrayEquals(encoder, File(root, "stt-model/encoder.onnx").readBytes())
    }

    @Test
    fun `redirects are followed, as GitHub sends release assets to another host`() {
        server.redirect("/vad.onnx", "/real/vad.onnx")
        server.serve("/real/vad.onnx", vad)
        store().install()
        assertArrayEquals(vad, File(root, "vad.onnx").readBytes())
    }

    @Test
    fun `an HTTP error is a network failure naming the status`() {
        val missing = ModelManifest(listOf(ModelDownload.Single(server.url("/nope.onnx"), ModelFile("nope.onnx", 3, sha(byteArrayOf(1, 2, 3))))))
        val e = assertThrows<ModelInstallException> { store(missing).install() }
        assertEquals(Reason.NETWORK, e.reason)
        assertTrue(e.message!!.contains("404"), e.message)
    }

    @Test
    fun `progress counts downloaded bytes up to the total`() {
        val seen = mutableListOf<InstallProgress>()
        store().install(onProgress = { seen += it })
        val downloads = seen.filter { it.phase == InstallProgress.Phase.DOWNLOADING }
        assertEquals((tarball.size + vad.size).toLong(), downloads.last().total)
        assertEquals(downloads.last().total, downloads.last().done)
        assertTrue(seen.any { it.phase == InstallProgress.Phase.UNPACKING })
    }

    private fun sha(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun tarBz2(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            for ((name, body) in entries) {
                val entry = TarArchiveEntry(name)
                if (body != null) entry.size = body.size.toLong()
                tar.putArchiveEntry(entry)
                body?.let(tar::write)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }
}
