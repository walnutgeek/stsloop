package com.walnutgeek.stsloop.audio.speech

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.walnutgeek.stsloop.core.speech.HotwordsDecision
import com.walnutgeek.stsloop.core.speech.HotwordsGate
import com.walnutgeek.stsloop.core.speech.ModelArchitecture
import java.io.FileNotFoundException

/**
 * The streaming recognizer, and the only way to create a stream on it.
 *
 * The [OnlineRecognizer] is private so every stream goes through
 * [createStream], which asks [HotwordsGate] first: the wrong hotwords call
 * kills the process with `_Exit(-1)` instead of throwing.
 */
class Stt(
    assets: AssetManager,
    val config: OnlineRecognizerConfig = SpeechModels.sttConfig(),
    architecture: ModelArchitecture = SpeechModels.sttArchitecture,
) : AutoCloseable {
    val spec = SpeechModels.specOf(config, architecture)
    private val recognizer: OnlineRecognizer

    init {
        requireAssets(assets, assetPaths(config))
        recognizer = OnlineRecognizer(assets, config)
    }

    /**
     * Create a stream, with [hotwords] (`/`-separated phrases) if the loaded
     * model can take them. Throws [IllegalStateException] instead of passing
     * hotwords the native code would die on.
     */
    fun createStream(hotwords: String = ""): OnlineStream =
        when (val decision = HotwordsGate.decide(spec, hotwords)) {
            HotwordsDecision.NoHotwords -> recognizer.createStream("")
            is HotwordsDecision.Pass -> recognizer.createStream(decision.hotwords)
            is HotwordsDecision.Refuse -> throw IllegalStateException(decision.reason)
        }

    fun isReady(stream: OnlineStream) = recognizer.isReady(stream)
    fun decode(stream: OnlineStream) = recognizer.decode(stream)
    fun isEndpoint(stream: OnlineStream) = recognizer.isEndpoint(stream)
    fun reset(stream: OnlineStream) = recognizer.reset(stream)
    fun text(stream: OnlineStream): String = recognizer.getResult(stream).text

    override fun close() = recognizer.release()

    private companion object {
        fun assetPaths(c: OnlineRecognizerConfig) = with(c.modelConfig) {
            listOf(transducer.encoder, transducer.decoder, transducer.joiner, tokens, bpeVocab)
        }.filter { it.isNotEmpty() }
    }
}

/**
 * Throw if any asset is missing. sherpa-onnx's asset loader calls `_Exit(-1)`
 * on a missing file, and its `newFromAsset` constructors skip config
 * validation, so this is the only catchable check.
 */
internal fun requireAssets(assets: AssetManager, paths: List<String>) {
    for (path in paths) {
        try {
            assets.open(path).close()
        } catch (e: FileNotFoundException) {
            throw IllegalStateException("missing asset '$path'; run scripts/fetch-models.sh", e)
        }
    }
}
