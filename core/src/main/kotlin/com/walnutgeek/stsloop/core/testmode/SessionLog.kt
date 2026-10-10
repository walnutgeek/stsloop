package com.walnutgeek.stsloop.core.testmode

import com.walnutgeek.stsloop.core.UtcTimestamp
import com.walnutgeek.stsloop.core.corpus.JsonWriter

/**
 * The directory inside the Corpus that holds one route-event log per test
 * Session, beside the Turn directories. Not a Turn: readers skip it.
 */
const val SESSIONS_DIR = "sessions"

/**
 * A test Session's route-event log: `corpus/sessions/<started_at>-<session_id>.jsonl`,
 * one JSON object per line, appended as events happen and never rewritten.
 * Each line starts with `at` (UTC wall time) and `event`; the rest are the
 * event's own fields. A reader should drop an incomplete last line (the
 * Session may have been killed mid-write).
 */
object SessionLog {
    fun fileName(sessionStartedAtMs: Long, sessionId: String): String =
        "${UtcTimestamp.format(sessionStartedAtMs)}-$sessionId.jsonl"

    /** One log line, newline included. */
    fun line(atMs: Long, event: String, fields: Map<String, Any?> = emptyMap()): String {
        require("at" !in fields && "event" !in fields) { "fields may not be named at or event" }
        val all = LinkedHashMap<String, Any?>()
        all["at"] = UtcTimestamp.format(atMs)
        all["event"] = event
        all.putAll(fields)
        return JsonWriter.write(all) + "\n"
    }
}
