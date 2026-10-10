package com.walnutgeek.stsloop.core.speech

/**
 * The parts of a loaded online recognizer's config that decide whether
 * hotwords are safe. Built from the config the recognizer was created with,
 * never declared separately, so the gate judges the model actually loaded.
 */
data class RecognizerSpec(
    val transducerEncoder: String,
    val transducerDecoder: String,
    val transducerJoiner: String,
    /** Model paths of every non-transducer family (paraformer, CTC, …); all empty for a transducer. */
    val otherModels: List<String>,
    val modelType: String,
    val decodingMethod: String,
    val modelingUnit: String,
    val bpeVocab: String,
    val hotwordsFile: String,
)

sealed interface HotwordsDecision {
    /** Create the stream with an empty hotwords string. */
    data object NoHotwords : HotwordsDecision

    /** Create the stream with exactly these hotwords. */
    data class Pass(val hotwords: String) : HotwordsDecision

    /** Do not create the stream: passing these hotwords could kill the process. */
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

    /**
     * Zipformer-family types, loaded by sherpa-onnx's OnlineRecognizerTransducerImpl.
     * sherpa-onnx also loads NeMo and Parakeet-unified transducers from the same
     * transducer fields (chosen by inspecting the files); Parakeet-unified has no
     * hotwords support and would exit, so an unlisted or empty type is refused.
     */
    private val ZIPFORMER_TYPES = setOf("zipformer", "zipformer2", "lstm", "conformer")

    fun decide(spec: RecognizerSpec, hotwords: String): HotwordsDecision {
        if (hotwords.all { it.isWhitespace() || it == '/' }) return HotwordsDecision.NoHotwords
        refusal(spec)?.let { return HotwordsDecision.Refuse(it) }
        return HotwordsDecision.Pass(hotwords)
    }

    /**
     * Why this config must not be loaded, or null. Config-time hotwords are
     * unioned with every stream's, so they stay empty: a Bucket's Word list
     * is then the only one in play.
     */
    fun configRefusal(spec: RecognizerSpec): String? =
        if (spec.hotwordsFile.isNotEmpty()) "config-time hotwordsFile must be empty, got '${spec.hotwordsFile}'" else null

    private fun refusal(spec: RecognizerSpec): String? = when {
        !isTransducer(spec) ->
            "hotwords need a transducer model (encoder, decoder and joiner, nothing else)"
        spec.modelType !in ZIPFORMER_TYPES ->
            "hotwords need modelType in $ZIPFORMER_TYPES, got '${spec.modelType}'"
        spec.decodingMethod != MODIFIED_BEAM_SEARCH ->
            "hotwords need decodingMethod=$MODIFIED_BEAM_SEARCH, got '${spec.decodingMethod}'"
        spec.modelingUnit !in KNOWN_UNITS ->
            "hotwords need modelingUnit in $KNOWN_UNITS, got '${spec.modelingUnit}'"
        spec.modelingUnit != "cjkchar" && spec.bpeVocab.isBlank() ->
            "hotwords with modelingUnit='${spec.modelingUnit}' need a bpeVocab"
        else -> null
    }

    private fun isTransducer(spec: RecognizerSpec) =
        spec.transducerEncoder.isNotEmpty() && spec.transducerDecoder.isNotEmpty() &&
            spec.transducerJoiner.isNotEmpty() && spec.otherModels.all { it.isEmpty() }
}
