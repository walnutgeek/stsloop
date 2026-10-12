package com.walnutgeek.stsloop.audio.speech

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.Vad
import com.walnutgeek.stsloop.core.models.ModelStore
import com.walnutgeek.stsloop.core.speech.modelId
import java.io.FileNotFoundException

/**
 * The speech models a Session loads, from wherever they are: bundled as
 * assets (a debug build, `scripts/fetch-models.sh`) or downloaded into a
 * [ModelStore] (a release build, [ModelDownloads]).
 */
class InstalledModels private constructor(
    /** Null when the models are files under [root]. */
    private val assets: AssetManager?,
    /** "" for assets, else the store's directory ending in `/`. */
    private val root: String,
) {
    val bundled: Boolean get() = assets != null

    /** For the log: where this Session's models come from. */
    val source: String get() = if (bundled) "bundled assets" else "downloaded, ${root.removeSuffix("/")}"

    fun sttConfig(numThreads: Int = 2) = SpeechModels.sttConfig(numThreads, root)
    fun vadConfig() = SpeechModels.vadConfig(root)
    fun newVad(): Vad = SpeechModels.newVad(assets, vadConfig())

    /** `transcript.model` of the STT model, without loading it: what [loadStt]'s [SttRecognizer.model] will be. */
    val modelId: String get() = Stt.specOf(sttConfig()).modelId

    /** Loads the STT model; seconds, and ~750 MB of memory. A missing native library throws a [LinkageError]. */
    fun loadStt(): SttRecognizer = SttRecognizer(Stt(assets, sttConfig()))

    companion object {
        /**
         * Bundled assets if every model file is among them, else the store's
         * files if it [ModelStore.isReady], else null: no Session can run yet.
         */
        fun locate(assets: AssetManager?, store: ModelStore): InstalledModels? = when {
            assets != null && store.manifest.files.all { hasAsset(assets, it.path) } -> InstalledModels(assets, "")
            store.isReady -> InstalledModels(null, store.root.absolutePath + "/")
            else -> null
        }

        private fun hasAsset(assets: AssetManager, path: String): Boolean = try {
            assets.open(path).close()
            true
        } catch (_: FileNotFoundException) {
            false
        }
    }
}
