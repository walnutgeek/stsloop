package com.walnutgeek.stsloop.core.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SpeechManifestTest {
    private val m = SpeechManifest.MANIFEST

    @Test
    fun `the app's models are the STT files and the VAD`() {
        assertEquals(
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
                .map { "${SpeechManifest.STT_DIR}/$it" } + SpeechManifest.VAD,
            m.files.map { it.path },
        )
    }

    @Test
    fun `every download comes from the upstream sherpa-onnx releases over https`() {
        for (d in m.downloads) {
            assertTrue(d.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/"), d.url)
        }
    }

    @Test
    fun `every pin is a SHA-256 in lower-case hex`() {
        for (sha in m.downloads.map { it.sha256 } + m.files.map { it.sha256 }) {
            assertTrue(Regex("[0-9a-f]{64}").matches(sha), sha)
        }
    }

    /** The debug build bundles what scripts/fetch-models.sh fetches; both must pin the same bytes. */
    @Test
    fun `fetch-models pins the same files`() {
        val script = File("../scripts/fetch-models.sh").readText()
        for (d in m.downloads) {
            assertTrue(d.sha256 in script, "fetch-models.sh lacks ${d.name}'s ${d.sha256}")
            assertTrue(d.url.substringAfterLast('/').removeSuffix(".tar.bz2") in script, d.url)
        }
        for (f in SpeechManifest.STT.files) {
            assertTrue("${f.path.substringAfterLast('/')} ${f.sha256}" in script, "fetch-models.sh lacks ${f.path}'s ${f.sha256}")
        }
    }

    @Test
    fun `about 464 MB to download`() {
        assertEquals(464_588_905, m.downloads.sumOf { it.size })
    }
}
