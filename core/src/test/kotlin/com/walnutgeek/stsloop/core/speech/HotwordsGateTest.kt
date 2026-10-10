package com.walnutgeek.stsloop.core.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

class HotwordsGateTest {
    private val hotwordCapable = RecognizerSpec(
        architecture = ModelArchitecture.ZIPFORMER_TRANSDUCER,
        decodingMethod = "modified_beam_search",
        modelingUnit = "bpe",
        bpeVocab = "models/bpe.vocab",
    )

    @Test
    fun `hotwords pass on a zipformer transducer under modified_beam_search`() {
        assertEquals(
            HotwordsDecision.Pass("STSLOOP/BUCKET"),
            HotwordsGate.decide(hotwordCapable, "STSLOOP/BUCKET"),
        )
    }

    @Test
    fun `empty hotwords never reach the hotwords path, whatever the model`() {
        val ctc = hotwordCapable.copy(architecture = ModelArchitecture.CTC, decodingMethod = "greedy_search")
        assertEquals(HotwordsDecision.NoHotwords, HotwordsGate.decide(ctc, ""))
        assertEquals(HotwordsDecision.NoHotwords, HotwordsGate.decide(hotwordCapable, ""))
    }

    @ParameterizedTest
    @ValueSource(strings = [" ", "\n", " / ", "/", "\t\n/ "])
    fun `blank hotwords are treated as none, so native code never sees them`(blank: String) {
        val ctc = hotwordCapable.copy(architecture = ModelArchitecture.CTC)
        assertEquals(HotwordsDecision.NoHotwords, HotwordsGate.decide(ctc, blank))
    }

    @ParameterizedTest
    @EnumSource(value = ModelArchitecture::class, names = ["ZIPFORMER_TRANSDUCER"], mode = EnumSource.Mode.EXCLUDE)
    fun `hotwords are refused on any other architecture`(architecture: ModelArchitecture) {
        val decision = HotwordsGate.decide(hotwordCapable.copy(architecture = architecture), "HELLO")
        assertInstanceOf(HotwordsDecision.Refuse::class.java, decision)
    }

    @ParameterizedTest
    @ValueSource(strings = ["greedy_search", "", "MODIFIED_BEAM_SEARCH"])
    fun `hotwords are refused on a transducer not under modified_beam_search`(method: String) {
        val decision = HotwordsGate.decide(hotwordCapable.copy(decodingMethod = method), "HELLO")
        assertInstanceOf(HotwordsDecision.Refuse::class.java, decision)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "char", "BPE"])
    fun `hotwords are refused when the modeling unit would make native code exit`(unit: String) {
        val decision = HotwordsGate.decide(hotwordCapable.copy(modelingUnit = unit), "HELLO")
        assertInstanceOf(HotwordsDecision.Refuse::class.java, decision)
    }

    @ParameterizedTest
    @ValueSource(strings = ["bpe", "bbpe", "cjkchar+bpe"])
    fun `hotwords are refused when a bpe unit has no bpe vocab`(unit: String) {
        val decision = HotwordsGate.decide(hotwordCapable.copy(modelingUnit = unit, bpeVocab = ""), "HELLO")
        assertInstanceOf(HotwordsDecision.Refuse::class.java, decision)
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
}
