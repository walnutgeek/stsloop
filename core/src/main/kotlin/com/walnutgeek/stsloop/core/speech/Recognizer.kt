package com.walnutgeek.stsloop.core.speech

/**
 * A loaded streaming recognizer: one per Session, never reloaded per Turn.
 * Not thread-safe; every call, and every call on its streams, comes from the
 * one recognizer thread.
 */
interface StreamingRecognizer : AutoCloseable {
    /** `transcript.engine` in `turn.json`. */
    val engine: String

    /** `transcript.model` in `turn.json`. */
    val model: String

    /** A fresh stream for one Turn. */
    fun open(): RecognitionStream
}

/** One Turn's recognition: samples in as they are captured, text out at the end. */
interface RecognitionStream {
    /** Feeds `[-1, 1)` samples at the capture rate and decodes whatever is ready. */
    fun accept(samples: FloatArray)

    /** No more samples: flushes the model and returns the final text, as the engine produced it. */
    fun finish(): String

    /** Frees the native stream. Call exactly once, after [finish] or instead of it. */
    fun release()
}

/**
 * `transcript.model`: the model directory, the encoder variant, and the
 * decoding method, e.g. `sherpa-onnx-streaming-zipformer-en-2023-06-26/epoch-99-avg-1-chunk-16-left-128.int8 modified_beam_search`.
 * Derived from the loaded config, so the Corpus records what actually ran.
 */
val RecognizerSpec.modelId: String
    get() {
        val dir = transducerEncoder.substringBeforeLast('/', "")
        val variant = transducerEncoder.substringAfterLast('/').removePrefix("encoder-").removeSuffix(".onnx")
        return (if (dir.isEmpty()) variant else "$dir/$variant") + " " + decodingMethod
    }
