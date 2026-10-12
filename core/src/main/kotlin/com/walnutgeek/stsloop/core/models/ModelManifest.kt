package com.walnutgeek.stsloop.core.models

/** One model file as the recognizer loads it: its path under the model root, and its pins. */
data class ModelFile(val path: String, val size: Long, val sha256: String)

/** One thing fetched from upstream, and the [files] it yields. Its own size and hash are pinned too. */
sealed interface ModelDownload {
    val url: String

    /** The name it is saved under while downloading; unique within a manifest. */
    val name: String
    val size: Long
    val sha256: String
    val files: List<ModelFile>

    /** A file served as is. */
    data class Single(override val url: String, val file: ModelFile) : ModelDownload {
        override val name get() = file.path.substringAfterLast('/')
        override val size get() = file.size
        override val sha256 get() = file.sha256
        override val files get() = listOf(file)
    }

    /**
     * A `.tar.bz2` release asset. Only [files] are extracted; each one's
     * path is its path inside the archive (a leading `./` ignored).
     */
    data class TarBz2(
        override val url: String,
        override val name: String,
        override val size: Long,
        override val sha256: String,
        override val files: List<ModelFile>,
    ) : ModelDownload
}

data class ModelManifest(val downloads: List<ModelDownload>) {
    val files: List<ModelFile> get() = downloads.flatMap { it.files }

    init {
        require(downloads.map { it.name }.toSet().size == downloads.size) { "download names must be unique" }
        require(files.map { it.path }.toSet().size == files.size) { "file paths must be unique" }
    }
}

/**
 * The speech models the app runs, pinned. The same pins are in
 * `scripts/fetch-models.sh`, which bundles them into debug builds instead.
 * Release builds download them on first run ([ModelStore]); see ADR-0004 for
 * why this STT model.
 */
object SpeechManifest {
    /** NVIDIA's nemotron-speech-streaming-en-0.6b, int8, 560 ms chunks (NVIDIA Open Model License), exported by k2-fsa. */
    const val STT_DIR = "sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25"

    /** Silero VAD (MIT). */
    const val VAD = "silero_vad.onnx"

    private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    val STT = ModelDownload.TarBz2(
        url = "$RELEASES/$STT_DIR.tar.bz2",
        name = "$STT_DIR.tar.bz2",
        size = 463_945_051,
        // The digest GitHub reports for the release asset.
        sha256 = "78e2b79fcf7271553a74402a76b771b09ea40117a39566a79f52235b23db6358",
        files = listOf(
            ModelFile("$STT_DIR/encoder.int8.onnx", 652_916_849, "7d932213491ad355c6e5576705dc3494731a52af87d7a1b954559340147909d8"),
            ModelFile("$STT_DIR/decoder.int8.onnx", 7_257_753, "0be9702c2f427a2b6bb241d298e0d3836a558de1f5b9fd3018f1cce6e2b3fa98"),
            ModelFile("$STT_DIR/joiner.int8.onnx", 1_735_862, "a35eac38a22ebceb04d230ed7afe0d68f446ba6914a036b97f14fece95967e23"),
            ModelFile("$STT_DIR/tokens.txt", 8_952, "dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff"),
        ),
    )

    val VAD_DOWNLOAD = ModelDownload.Single(
        url = "$RELEASES/$VAD",
        // The digest GitHub reports for the release asset.
        file = ModelFile(VAD, 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
    )

    val MANIFEST = ModelManifest(listOf(STT, VAD_DOWNLOAD))
}
