package com.walnutgeek.stsloop.core.corpus

import com.walnutgeek.stsloop.core.testmode.SESSIONS_DIR

/**
 * One Turn as the transcript list shows it, read leniently from its `turn.json`.
 *
 * Deliberately independent of the writer's [com.walnutgeek.stsloop.core.Turn]:
 * the Corpus holds Turns from every schema the app has ever written (before
 * VAD, before STT, before Declaration), and the list must show all of them.
 * Every field is optional; a field that is absent or of the wrong type is null.
 *
 * [transcript] is `transcript.text` exactly as the engine produced it; null
 * means the Turn has no transcript, `""` that the recognizer heard nothing.
 * [kind] is shown as written, so a value from a newer app is not lost.
 * [problem] says why `turn.json` could not be read; the rest is then null.
 */
data class ListedTurn(
    val directoryName: String,
    val id: String? = null,
    val startedAt: String? = null,
    val durationMs: Long? = null,
    val transcript: String? = null,
    val bucket: String? = null,
    val kind: String? = null,
    val tombstonedBy: String? = null,
    val problem: String? = null,
) {
    val tombstoned: Boolean get() = tombstonedBy != null
}

/** Builds the transcript list from a read-only view of the Corpus. */
object TranscriptList {
    /**
     * Whether a Corpus entry named [name] is listed as a Turn. Hidden entries
     * and the test-mode Session logs ([SESSIONS_DIR]) are not; anything else
     * is, so a stray directory shows up as a problem instead of vanishing.
     */
    fun isTurnEntry(name: String): Boolean = !name.startsWith(".") && name != SESSIONS_DIR

    /** Reads one Turn directory's `turn.json` text, or null when the file is missing. */
    fun read(directoryName: String, turnJson: String?): ListedTurn {
        if (turnJson == null) return ListedTurn(directoryName, problem = "no turn.json")
        val root = try {
            Json.parse(turnJson)
        } catch (e: JsonException) {
            return ListedTurn(directoryName, problem = "unreadable turn.json: ${e.message}")
        }
        if (root !is Map<*, *>) return ListedTurn(directoryName, problem = "turn.json is not an object")
        return ListedTurn(
            directoryName = directoryName,
            id = root.string("id"),
            startedAt = root.string("started_at"),
            durationMs = root.obj("audio")?.long("duration_ms"),
            transcript = root.obj("transcript")?.string("text"),
            bucket = root.string("bucket"),
            kind = root.string("kind"),
            tombstonedBy = root.string("tombstoned_by"),
        )
    }

    /**
     * Orders [turns] newest first and marks tombstoned Turns.
     *
     * A Turn directory is immutable, so a Turn tombstoned after it was
     * published can never carry the mark in its own `turn.json`: tombstoning
     * is derived on read. [tombstonedBy] maps a tombstoned Turn's directory
     * name to the directory name of the Turn that tombstoned it. Directory
     * names, not ids: they are unique, 6-hex ids are not. Until #13 defines
     * the tombstone record and builds this map, it is empty. A Turn's own
     * non-null `tombstoned_by` still wins. Directory names are
     * `<started_at>-<id>`, so they sort chronologically.
     */
    fun of(turns: List<ListedTurn>, tombstonedBy: Map<String, String> = emptyMap()): List<ListedTurn> =
        turns
            .sortedByDescending { it.directoryName }
            .map { t ->
                val by = tombstonedBy[t.directoryName]
                if (t.tombstonedBy == null && by != null) t.copy(tombstonedBy = by) else t
            }

    private fun Map<*, *>.string(key: String): String? = this[key] as? String
    private fun Map<*, *>.long(key: String): Long? = this[key] as? Long
    private fun Map<*, *>.obj(key: String): Map<*, *>? = this[key] as? Map<*, *>
}
