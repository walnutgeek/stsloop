package com.walnutgeek.stsloop.core

/**
 * Writes `turn.json`. Hand-rolled rather than a serialisation library: the
 * format is small, write-only on the phone, and its field order and layout are
 * part of the spec (`docs/mvp.md`, Corpus format).
 */
object TurnJson {
    fun encode(turn: Turn): String = buildString {
        val a = turn.audio
        append("{\n")
        append("  \"schema\": ").append(CORPUS_SCHEMA).append(",\n")
        append("  \"id\": ").str(turn.id).append(",\n")
        append("  \"session_id\": ").str(turn.sessionId).append(",\n")
        append("  \"started_at\": ").str(UtcTimestamp.format(turn.startedAtMs)).append(",\n")
        append("  \"ended_at\": ").str(UtcTimestamp.format(turn.endedAtMs)).append(",\n")
        append("  \"audio\": { \"file\": ").str(a.file)
        append(", \"sha256\": ").str(a.sha256)
        append(", \"sample_rate\": ").append(a.sampleRate)
        append(", \"duration_ms\": ").append(a.durationMs)
        a.silent?.let { append(", \"silent\": ").append(it) }
        append(" },\n")
        turn.vad?.let { v ->
            append("  \"vad\": { \"speech_ms\": ").append(v.speechMs)
            append(", \"trailing_silence_ms\": ").append(v.trailingSilenceMs).append(" },\n")
        }
        turn.transcript?.let { t ->
            append("  \"transcript\": {\n")
            append("    \"text\": ").str(t.text).append(",\n")
            append("    \"engine\": ").str(t.engine).append(",\n")
            append("    \"model\": ").str(t.model).append(",\n")
            append("    \"finished_at\": ").str(UtcTimestamp.format(t.finishedAtMs)).append(",\n")
            append("    \"latency_ms\": ").append(t.latencyMs).append("\n")
            append("  },\n")
        }
        turn.classification?.let { c ->
            append("  \"kind\": ").str(c.kind.json).append(",\n")
            append("  \"declaration\": ")
            val d = c.declaration
            if (d == null) {
                append("null")
            } else {
                append("{ \"bucket\": ").str(d.bucket)
                append(", \"position\": ").str(d.position.json)
                append(", \"matched\": ").str(d.matched).append(" }")
            }
            append(",\n")
            append("  \"bucket\": ").strOrNull(c.bucket).append(",\n")
            append("  \"bucket_source\": ").strOrNull(c.bucketSource?.json).append(",\n")
            append("  \"content\": ").strOrNull(c.content).append(",\n")
        }
        turn.test?.let { t ->
            val c = t.config
            append("  \"test\": {\n")
            append("    \"label\": ").str(c.label).append(",\n")
            append("    \"mic_source\": ").str(c.micSource.json).append(",\n")
            append("    \"mic_input\": ").str(c.micInput.json).append(",\n")
            append("    \"audio_mode\": ").str(c.audioMode.json).append(",\n")
            append("    \"input_devices\": ").strList(t.inputDevices).append(",\n")
            append("    \"tts_interval_ms\": ").append(c.ttsIntervalMs).append(",\n")
            append("    \"tts_usage\": ").str(c.ttsUsage.json).append(",\n")
            append("    \"tts_overlap\": ").append(t.ttsOverlap).append(",\n")
            append("    \"tts_overlap_ms\": ").append(t.ttsOverlapMs).append(",\n")
            append("    \"tts_phrases\": ").strList(t.ttsPhrases).append("\n")
            append("  },\n")
        }
        if (turn.recovered) append("  \"recovered\": true,\n")
        append("  \"app_version\": ").str(turn.appVersion).append(",\n")
        append("  \"tombstoned_by\": ").strOrNull(turn.tombstonedBy).append("\n")
        append("}\n")
    }

    private fun StringBuilder.strList(l: List<String>): StringBuilder = append(l.joinToString(", ", "[", "]", transform = ::jsonString))

    private fun StringBuilder.strOrNull(s: String?): StringBuilder = if (s == null) append("null") else str(s)

    private fun StringBuilder.str(s: String): StringBuilder = append(jsonString(s))
}

/** [s] as a JSON string literal, quotes included. Shared by every JSON file :core writes. */
internal fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
    append('"')
}
