package com.walnutgeek.stsloop.core.settings

/** Durations as the settings screen shows and takes them: seconds, to the millisecond. */
object Seconds {
    private val FORM = Regex("""(\d*)(?:[.,](\d{1,3}))?""")

    /** [text] as milliseconds ("1.5" → 1500; a decimal comma too), or null when it is not a duration. */
    fun parse(text: String): Int? {
        val m = FORM.matchEntire(text.trim()) ?: return null
        val (whole, frac) = m.destructured
        if (whole.isEmpty() && frac.isEmpty()) return null
        if (whole.length > 9) return null
        val ms = whole.ifEmpty { "0" }.toLong() * 1000 + frac.padEnd(3, '0').toLong()
        return if (ms <= Int.MAX_VALUE) ms.toInt() else null
    }

    /** [ms] as seconds, without trailing zeros ("1.5", "120"). */
    fun format(ms: Int): String {
        val whole = ms / 1000
        val frac = (ms % 1000).toString().padStart(3, '0').trimEnd('0')
        return if (frac.isEmpty()) "$whole" else "$whole.$frac"
    }
}
