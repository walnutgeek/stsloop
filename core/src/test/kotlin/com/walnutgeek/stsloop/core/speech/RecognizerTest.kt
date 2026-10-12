package com.walnutgeek.stsloop.core.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecognizerTest {
    @Test
    fun `model id names the model directory, the encoder variant and the decoding method`() {
        val spec = RecognizerSpec(
            transducerEncoder = "sherpa-onnx-streaming-zipformer-en-2023-06-26/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            transducerDecoder = "d",
            transducerJoiner = "j",
            otherModels = emptyList(),
            modelType = "zipformer2",
            decodingMethod = "modified_beam_search",
            modelingUnit = "bpe",
            bpeVocab = "v",
            hotwordsFile = "",
        )
        assertEquals(
            "sherpa-onnx-streaming-zipformer-en-2023-06-26/epoch-99-avg-1-chunk-16-left-128.int8 modified_beam_search",
            spec.modelId,
        )
    }

    @Test
    fun `model id of a NeMo export, whose encoder is named encoder dot variant`() {
        val dir = "sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25"
        val spec = RecognizerSpec("$dir/encoder.int8.onnx", "$dir/decoder.int8.onnx", "$dir/joiner.int8.onnx", emptyList(), "", "greedy_search", "", "", "")
        assertEquals("$dir/int8 greedy_search", spec.modelId)
    }

    @Test
    fun `model id is the same for a downloaded model, loaded from an absolute path`() {
        val dir = "/data/user/0/com.walnutgeek.stsloop/no_backup/models/sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25"
        val spec = RecognizerSpec("$dir/encoder.int8.onnx", "$dir/decoder.int8.onnx", "$dir/joiner.int8.onnx", emptyList(), "", "greedy_search", "", "", "")
        assertEquals("sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25/int8 greedy_search", spec.modelId)
    }

    @Test
    fun `model id of an encoder outside a directory, without the usual prefix`() {
        val spec = RecognizerSpec("model.onnx", "d", "j", emptyList(), "zipformer", "greedy_search", "", "", "")
        assertEquals("model greedy_search", spec.modelId)
    }
}
