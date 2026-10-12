package com.walnutgeek.stsloop.core.corpus

import com.walnutgeek.stsloop.core.jsonString

/**
 * JSON for plain values: the inverse of [Json] for logs and the settings
 * screen's config files, where the layout is not part of a spec (`turn.json`
 * keeps its own hand-laid writer).
 * Maps need string keys and keep their iteration order; a non-finite number
 * becomes `null`. Anything else is refused rather than written as `toString()`.
 */
object JsonWriter {
    fun write(value: Any?): String = StringBuilder().also { it.value(value) }.toString()

    /**
     * Like [write], laid out for a config file a person may also edit over adb:
     * the top-level object, and any object or array directly in it, get one
     * entry per line; anything deeper stays on its entry's line.
     */
    fun pretty(value: Any?): String = StringBuilder().also { it.pretty(value, 0) }.append('\n').toString()

    private fun StringBuilder.pretty(v: Any?, depth: Int) {
        val pad = "  ".repeat(depth + 1)
        when {
            depth > 1 -> value(v)
            v is Map<*, *> && v.isNotEmpty() -> {
                append("{\n")
                var first = true
                for ((k, item) in v) {
                    require(k is String) { "JSON object keys must be strings, was $k" }
                    if (!first) append(",\n")
                    first = false
                    append(pad).append(jsonString(k)).append(": ")
                    pretty(item, depth + 1)
                }
                append('\n').append("  ".repeat(depth)).append('}')
            }
            v is List<*> && v.isNotEmpty() -> {
                append("[\n")
                for ((i, item) in v.withIndex()) {
                    if (i > 0) append(",\n")
                    append(pad)
                    pretty(item, depth + 1)
                }
                append('\n').append("  ".repeat(depth)).append(']')
            }
            else -> value(v)
        }
    }

    private fun StringBuilder.value(v: Any?) {
        when (v) {
            null -> append("null")
            is Boolean -> append(v)
            is Int, is Long, is Short, is Byte -> append(v)
            is Double -> if (v.isFinite()) append(v) else append("null")
            is Float -> if (v.isFinite()) append(v) else append("null")
            is String -> append(jsonString(v))
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((k, item) in v) {
                    require(k is String) { "JSON object keys must be strings, was $k" }
                    if (!first) append(',')
                    first = false
                    append(jsonString(k)).append(':')
                    value(item)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                for ((i, item) in v.withIndex()) {
                    if (i > 0) append(',')
                    value(item)
                }
                append(']')
            }
            else -> throw IllegalArgumentException("cannot write ${v::class.simpleName} as JSON")
        }
    }
}
