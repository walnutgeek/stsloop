package com.walnutgeek.stsloop.core.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HotwordsGateTest {
    private val hotwordCapable = RecognizerSpec(
        transducerEncoder = "m/encoder.onnx",
        transducerDecoder = "m/decoder.onnx",
        transducerJoiner = "m/joiner.onnx",
        otherModels = listOf("", "", "", ""),
        modelType = "zipformer2",
        decodingMethod = "modified_beam_search",
        modelingUnit = "bpe",
        bpeVocab = "m/bpe.vocab",
        hotwordsFile = "",
    )

    private val ctc = hotwordCapable.copy(
        transducerEncoder = "",
        transducerDecoder = "",
        transducerJoiner = "",
        otherModels = listOf("m/ctc.onnx"),
        decodingMethod = "greedy_search",
    )

    private fun assertRefused(spec: RecognizerSpec, hotwords: String = "HELLO") =
        assertInstanceOf(HotwordsDecision.Refuse::class.java, HotwordsGate.decide(spec, hotwords))

    @Test
    fun `hotwords pass on a zipformer transducer under modified_beam_search`() {
        assertEquals(
            HotwordsDecision.Pass("STSLOOP/BUCKET"),
            HotwordsGate.decide(hotwordCapable, "STSLOOP/BUCKET"),
        )
    }

    @Test
    fun `empty hotwords never reach the hotwords path, whatever the model`() {
        assertEquals(HotwordsDecision.NoHotwords, HotwordsGate.decide(ctc, ""))
        assertEquals(HotwordsDecision.NoHotwords, HotwordsGate.decide(hotwordCapable, ""))
    }

    @ParameterizedTest
    @ValueSource(strings = [" ", "\n", " / ", "/", "\t\n/ "])
    fun `blank hotwords are treated as none, so native code never sees them`(blank: String) {
        assertEquals(HotwordsDecision.NoHotwords, HotwordsGate.decide(ctc, blank))
    }

    @Test
    fun `hotwords are refused on a model configured without transducer files`() {
        assertRefused(ctc)
        assertRefused(ctc.copy(decodingMethod = "modified_beam_search"))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `hotwords are refused when any transducer file is missing`(blanked: Int) {
        val spec = when (blanked) {
            0 -> hotwordCapable.copy(transducerEncoder = "")
            1 -> hotwordCapable.copy(transducerDecoder = "")
            else -> hotwordCapable.copy(transducerJoiner = "")
        }
        assertRefused(spec)
    }

    @Test
    fun `hotwords are refused when another model is configured alongside the transducer`() {
        assertRefused(hotwordCapable.copy(otherModels = listOf("", "m/paraformer-encoder.onnx")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "nemo_transducer", "ZIPFORMER2"])
    fun `hotwords are refused unless the model type is a known zipformer-family transducer`(type: String) {
        // sherpa-onnx picks NeMo / Parakeet-unified impls from the model files;
        // Parakeet-unified has no hotwords override and would _Exit.
        assertRefused(hotwordCapable.copy(modelType = type))
    }

    @ParameterizedTest
    @ValueSource(strings = ["zipformer", "zipformer2", "lstm", "conformer"])
    fun `known zipformer-family model types pass`(type: String) {
        assertInstanceOf(
            HotwordsDecision.Pass::class.java,
            HotwordsGate.decide(hotwordCapable.copy(modelType = type), "HELLO"),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["greedy_search", "", "MODIFIED_BEAM_SEARCH"])
    fun `hotwords are refused on a transducer not under modified_beam_search`(method: String) {
        assertRefused(hotwordCapable.copy(decodingMethod = method))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "char", "BPE"])
    fun `hotwords are refused when the modeling unit would make native code exit`(unit: String) {
        assertRefused(hotwordCapable.copy(modelingUnit = unit))
    }

    @ParameterizedTest
    @ValueSource(strings = ["bpe", "bbpe", "cjkchar+bpe"])
    fun `hotwords are refused when a bpe unit has no bpe vocab`(unit: String) {
        assertRefused(hotwordCapable.copy(modelingUnit = unit, bpeVocab = ""))
    }

    @Test
    fun `cjkchar needs no bpe vocab`() {
        val spec = hotwordCapable.copy(modelingUnit = "cjkchar", bpeVocab = "")
        assertEquals(HotwordsDecision.Pass("你好"), HotwordsGate.decide(spec, "你好"))
    }

    @Test
    fun `a refusal names the reason`() {
        val decision = HotwordsGate.decide(hotwordCapable.copy(decodingMethod = "greedy_search"), "HELLO")
        assertEquals(
            "hotwords need decodingMethod=modified_beam_search, got 'greedy_search'",
            (decision as HotwordsDecision.Refuse).reason,
        )
    }

    @Test
    fun `a config with no config-time hotwords is accepted`() {
        assertNull(HotwordsGate.configRefusal(hotwordCapable))
        assertNull(HotwordsGate.configRefusal(ctc))
    }

    @Test
    fun `config-time hotwords are refused, so a Bucket's Word list is the only one in play`() {
        assertEquals(
            "config-time hotwordsFile must be empty, got 'm/hotwords.txt'",
            HotwordsGate.configRefusal(hotwordCapable.copy(hotwordsFile = "m/hotwords.txt")),
        )
        assertEquals(
            "config-time hotwordsFile must be empty, got 'x'",
            HotwordsGate.configRefusal(ctc.copy(hotwordsFile = "x")),
        )
    }
}
