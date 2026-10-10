package com.walnutgeek.stsloop.core.speech

/**
 * The recognizer implementation sherpa-onnx picks for a loaded model, as far
 * as hotwords care. sherpa-onnx decides this by inspecting the model files,
 * so it cannot be read off the config: whoever describes a model declares it.
 */
enum class ModelArchitecture {
    /** Zipformer / Zipformer2 / LSTM transducer (`OnlineRecognizerTransducerImpl`). */
    ZIPFORMER_TRANSDUCER,

    /** NeMo transducers. Some override hotwords, Parakeet-unified does not: treated as unsafe. */
    OTHER_TRANSDUCER,
    CTC,
    PARAFORMER,
}

/** What the hotwords chokepoint needs to know about a loaded online recognizer. */
data class RecognizerSpec(
    val architecture: ModelArchitecture,
    val decodingMethod: String,
    val modelingUnit: String,
    val bpeVocab: String,
)

sealed interface HotwordsDecision {
    /** Create the stream with an empty hotwords string. */
    data object NoHotwords : HotwordsDecision

    /** Create the stream with exactly these hotwords. */
    data class Pass(val hotwords: String) : HotwordsDecision

    /** Do not create the stream: passing these hotwords would kill the process. */
    data class Refuse(val reason: String) : HotwordsDecision
}

/**
 * The single decision in front of sherpa-onnx stream creation.
 *
 * A non-empty hotwords string sent to the wrong recognizer does not throw: it
 * ends the process with `_Exit(-1)` (non-transducer, or an unknown modeling
 * unit) or dereferences a null BPE encoder (bpe unit without `bpeVocab`, or
 * not under modified_beam_search, which is the only method that builds it).
 * See docs/mvp.md "`_Exit(-1)` is a safety constraint".
 */
object HotwordsGate {
    const val MODIFIED_BEAM_SEARCH = "modified_beam_search"

    /** Modeling units sherpa-onnx's EncodeHotwords accepts; anything else exits. */
    private val KNOWN_UNITS = setOf("cjkchar", "bpe", "bbpe", "cjkchar+bpe")

    fun decide(spec: RecognizerSpec, hotwords: String): HotwordsDecision {
        if (hotwords.all { it.isWhitespace() || it == '/' }) return HotwordsDecision.NoHotwords
        refusal(spec)?.let { return HotwordsDecision.Refuse(it) }
        return HotwordsDecision.Pass(hotwords)
    }

    private fun refusal(spec: RecognizerSpec): String? = when {
        spec.architecture != ModelArchitecture.ZIPFORMER_TRANSDUCER ->
            "hotwords need a zipformer transducer, got ${spec.architecture}"
        spec.decodingMethod != MODIFIED_BEAM_SEARCH ->
            "hotwords need decodingMethod=$MODIFIED_BEAM_SEARCH, got '${spec.decodingMethod}'"
        spec.modelingUnit !in KNOWN_UNITS ->
            "hotwords need modelingUnit in $KNOWN_UNITS, got '${spec.modelingUnit}'"
        spec.modelingUnit != "cjkchar" && spec.bpeVocab.isBlank() ->
            "hotwords with modelingUnit='${spec.modelingUnit}' need a bpeVocab"
        else -> null
    }
}
