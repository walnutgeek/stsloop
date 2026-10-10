package com.walnutgeek.stsloop.core.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecognizerTest {
    @Test
    fun `transcript text is trimmed, whitespace-collapsed and lowercased`() {
        assertEquals(
            "after early nightfall the yellow lamps",
            TranscriptText.normalize("  AFTER EARLY  NIGHTFALL\tTHE YELLOW LAMPS "),
        )
    }

    @Test
    fun `apostrophes and other characters survive normalisation`() {
        assertEquals("don't stop", TranscriptText.normalize("DON'T STOP"))
    }

    @Test
    fun `no words is an empty transcript, not a missing one`() {
        assertEquals("", TranscriptText.normalize("   "))
        assertEquals("", TranscriptText.normalize(""))
    }

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
    fun `model id of an encoder outside a directory, without the usual prefix`() {
        val spec = RecognizerSpec("model.onnx", "d", "j", emptyList(), "zipformer", "greedy_search", "", "", "")
        assertEquals("model greedy_search", spec.modelId)
    }
}
