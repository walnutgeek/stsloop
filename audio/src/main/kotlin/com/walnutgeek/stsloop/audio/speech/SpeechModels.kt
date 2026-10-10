package com.walnutgeek.stsloop.audio.speech

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.walnutgeek.stsloop.audio.SAMPLE_RATE_HZ
import com.walnutgeek.stsloop.core.speech.HotwordsGate

/**
 * The speech models stsloop ships, as asset paths. Fetched into
 * `audio/src/main/assets` by `scripts/fetch-models.sh`; see ADR-0003 for why
 * this model and this decoding configuration.
 */
object SpeechModels {
    const val STT_DIR = "sherpa-onnx-streaming-zipformer-en-2023-06-26"
    private const val PREFIX = "epoch-99-avg-1-chunk-16-left-128"
    const val VAD_MODEL = "silero_vad.onnx"

    fun sttConfig(numThreads: Int = 2) = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$STT_DIR/encoder-$PREFIX.int8.onnx",
                decoder = "$STT_DIR/decoder-$PREFIX.int8.onnx",
                joiner = "$STT_DIR/joiner-$PREFIX.int8.onnx",
            ),
            tokens = "$STT_DIR/tokens.txt",
            numThreads = numThreads,
            modelType = "zipformer2",
            modelingUnit = "bpe",
            bpeVocab = "$STT_DIR/bpe.vocab",
        ),
        decodingMethod = HotwordsGate.MODIFIED_BEAM_SEARCH,
        // Per-stream hotwords are unioned with config-time ones; Stt refuses
        // a non-empty file so a Bucket's Word list is the only one in play.
        hotwordsFile = "",
    )

    fun vadConfig() = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(model = VAD_MODEL),
        sampleRate = SAMPLE_RATE_HZ,
        numThreads = 1,
    )

    /** Silero VAD from assets, checked first like [Stt]. */
    fun newVad(assets: AssetManager, config: VadModelConfig = vadConfig()): Vad {
        requireAssets(assets, listOf(config.sileroVadModelConfig.model))
        return Vad(assets, config)
    }
}
