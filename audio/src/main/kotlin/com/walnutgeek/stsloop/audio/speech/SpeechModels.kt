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

/**
 * The speech models stsloop ships, as asset paths. Fetched into
 * `audio/src/main/assets` by `scripts/fetch-models.sh`; see ADR-0004 for why
 * this model and this decoding configuration.
 */
object SpeechModels {
    /** NVIDIA's nemotron-speech-streaming-en-0.6b, int8, 560 ms chunks: a streaming NeMo transducer. */
    const val STT_DIR = "sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25"
    const val VAD_MODEL = "silero_vad.onnx"

    /**
     * The model type is left empty: sherpa-onnx picks the NeMo implementation
     * from the files. In the pinned v1.13.8 that implementation takes only
     * greedy_search (anything else exits at load) and no hotwords, which
     * [Stt]'s gate refuses.
     */
    fun sttConfig(numThreads: Int = 2) = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 128),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$STT_DIR/encoder.int8.onnx",
                decoder = "$STT_DIR/decoder.int8.onnx",
                joiner = "$STT_DIR/joiner.int8.onnx",
            ),
            tokens = "$STT_DIR/tokens.txt",
            numThreads = numThreads,
        ),
        decodingMethod = "greedy_search",
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
