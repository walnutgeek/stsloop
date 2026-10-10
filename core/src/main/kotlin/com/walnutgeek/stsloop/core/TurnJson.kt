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
        append(", \"duration_ms\": ").append(a.durationMs).append(" },\n")
        turn.vad?.let { v ->
            append("  \"vad\": { \"speech_ms\": ").append(v.speechMs)
            append(", \"trailing_silence_ms\": ").append(v.trailingSilenceMs).append(" },\n")
        }
        turn.transcript?.let { t ->
            append("  \"transcript\": {\n")
            append("    \"text\": ").str(t.text).append(",\n")
            append("    \"engine\": ").str(t.engine).append(",\n")
            append("    \"model\": ").str(t.model).append(",\n")
            append("    \"finished_at\": ").str(UtcTimestamp.format(t.finishedAtMs)).append("\n")
            append("  },\n")
        }
        turn.kind?.let { append("  \"kind\": ").str(it.json).append(",\n") }
        append("  \"app_version\": ").str(turn.appVersion).append(",\n")
        append("  \"tombstoned_by\": ").strOrNull(turn.tombstonedBy).append("\n")
        append("}\n")
    }

    private fun StringBuilder.strOrNull(s: String?): StringBuilder = if (s == null) append("null") else str(s)

    private fun StringBuilder.str(s: String): StringBuilder {
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
        return append('"')
    }
}
