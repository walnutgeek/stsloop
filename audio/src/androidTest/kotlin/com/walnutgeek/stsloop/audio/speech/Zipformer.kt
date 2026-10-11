package com.walnutgeek.stsloop.audio.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.walnutgeek.stsloop.audio.SAMPLE_RATE_HZ
import com.walnutgeek.stsloop.core.speech.HotwordsGate

/**
 * The model shipped before #30, now in the test APK only
 * (`scripts/fetch-models.sh` puts it in `audio/src/androidTest/assets`): the
 * baseline for [SttBenchmarkDeviceTest], and the one hotwords-capable model,
 * so the device tests can show hotwords reaching native code through [Stt].
 */
object Zipformer {
    const val DIR = "sherpa-onnx-streaming-zipformer-en-2023-06-26"
    private const val PREFIX = "epoch-99-avg-1-chunk-16-left-128"

    fun config(numThreads: Int = 2) = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$DIR/encoder-$PREFIX.int8.onnx",
                decoder = "$DIR/decoder-$PREFIX.int8.onnx",
                joiner = "$DIR/joiner-$PREFIX.int8.onnx",
            ),
            tokens = "$DIR/tokens.txt",
            numThreads = numThreads,
            modelType = "zipformer2",
            modelingUnit = "bpe",
            bpeVocab = "$DIR/bpe.vocab",
        ),
        decodingMethod = HotwordsGate.MODIFIED_BEAM_SEARCH,
        hotwordsFile = "",
    )
}
