package com.walnutgeek.stsloop.core.settings

import com.walnutgeek.stsloop.core.corpus.Json
import com.walnutgeek.stsloop.core.corpus.JsonException
import com.walnutgeek.stsloop.core.corpus.JsonWriter
import com.walnutgeek.stsloop.core.turn.Timings

/**
 * The settings screen's two timings, over the text of `files/timings.json`
 * ([file], null when there is none): trailing silence and the focus timeout.
 * A Session reads the file at its start, so an edit applies from the next one.
 *
 * An edit changes only the keys whose value changed and keeps every other
 * entry of the file as it was, adb-written ones included, even those the
 * Session rejects. A file that is not a JSON object gives a Session all the
 * defaults, so an edit replaces it.
 */
class TimingsSettings(private val file: String?) {
    private val parsed = file?.let(Timings::parse) ?: Timings.Parsed(Timings(), emptyList())

    /** What a Session would use. */
    val timings: Timings get() = parsed.timings

    /** What the file has that a Session ignores, as `"key: why"`. */
    val rejected: List<String> get() = parsed.rejected

    /** The file with [trailingSilenceMs] and [focusTimeoutMs] set; a null or unchanged value is left as it is. */
    fun edit(trailingSilenceMs: Int? = null, focusTimeoutMs: Int? = null): Edit {
        val entries = LinkedHashMap<String, Any?>(
            file?.let {
                try {
                    Json.parseObject(it)
                } catch (e: JsonException) {
                    null
                }
            }.orEmpty(),
        )
        val changes = listOfNotNull(
            trailingSilenceMs?.takeIf { it != timings.trailingSilenceMs }?.let { TRAILING_SILENCE to it },
            focusTimeoutMs?.takeIf { it != timings.focusTimeoutMs }?.let { FOCUS_TIMEOUT to it },
        )
        for ((setting, ms) in changes) {
            if (ms !in setting.range) {
                return Edit.Refused(
                    "${setting.label} must be ${Seconds.format(setting.range.first)} to ${Seconds.format(setting.range.last)} seconds.",
                )
            }
            entries[setting.key] = ms.toLong()
        }
        val text = JsonWriter.pretty(entries)
        val reread = Timings.parse(text)
        for ((setting, _) in changes) {
            reread.rejected.firstOrNull { it.startsWith("${setting.key}:") }?.let { return Edit.Refused("${setting.label}: $it") }
        }
        return Edit.Save(Timings.FILE, text)
    }

    /** One timing the screen sets: its file [key], and the [range] the screen accepts, in milliseconds. */
    class Setting(val key: String, val label: String, val range: IntRange)

    companion object {
        /**
         * Narrower than the file's 1 ms to 10 min: below 0.3 s every breath
         * would end a Turn, and above 10 s nothing ever would. The file still
         * takes any value it allows, and the screen leaves one alone until it is edited.
         */
        val TRAILING_SILENCE = Setting("trailing_silence_ms", "Trailing silence", 300..10_000)

        /** As the file allows it. */
        val FOCUS_TIMEOUT = Setting("focus_timeout_ms", "Focus timeout", Timings.FOCUS_TIMEOUT_FLOOR_MS..Timings.FOCUS_TIMEOUT_CAP_MS)
    }
}
