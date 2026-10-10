package com.walnutgeek.stsloop.audio.speech

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.walnutgeek.stsloop.core.speech.HotwordsDecision
import com.walnutgeek.stsloop.core.speech.HotwordsGate
import com.walnutgeek.stsloop.core.speech.RecognizerSpec
import java.io.FileNotFoundException

/**
 * The streaming recognizer, and the only way to create a stream on it.
 *
 * The [OnlineRecognizer] is private so every stream goes through
 * [createStream], which asks [HotwordsGate] first: the wrong hotwords call
 * kills the process with `_Exit(-1)` instead of throwing. The gate judges a
 * [RecognizerSpec] snapshotted from the very config handed to native code.
 * The `checkSingleChokepoint` Gradle task fails the build if anything else
 * constructs an [OnlineRecognizer].
 */
class Stt(
    assets: AssetManager,
    config: OnlineRecognizerConfig = SpeechModels.sttConfig(),
) : AutoCloseable {
    val spec: RecognizerSpec = specOf(config)
    private val recognizer: OnlineRecognizer

    init {
        HotwordsGate.configRefusal(spec)?.let { throw IllegalArgumentException(it) }
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
    fun text(stream: OnlineStream): String = recognizer.getResult(stream).text

    override fun close() = recognizer.release()

    private companion object {
        fun specOf(c: OnlineRecognizerConfig) = with(c.modelConfig) {
            RecognizerSpec(
                transducerEncoder = transducer.encoder,
                transducerDecoder = transducer.decoder,
                transducerJoiner = transducer.joiner,
                otherModels = listOf(
                    paraformer.encoder, paraformer.decoder, zipformer2Ctc.model, neMoCtc.model, toneCtc.model,
                ),
                modelType = modelType,
                decodingMethod = c.decodingMethod,
                modelingUnit = modelingUnit,
                bpeVocab = bpeVocab,
                hotwordsFile = c.hotwordsFile,
            )
        }

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
