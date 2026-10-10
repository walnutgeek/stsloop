package com.walnutgeek.stsloop.core

/**
 * Formats epoch milliseconds as `YYYY-MM-DDTHH:MM:SS.mmmZ` — always UTC, always
 * three fractional digits, so Turn directory names sort chronologically.
 * Stdlib-only (no java.time) to keep `:core` ready for the KMP lift.
 */
object UtcTimestamp {
    private const val MS_PER_DAY = 86_400_000L

    fun format(epochMs: Long): String {
        val days = epochMs.floorDiv(MS_PER_DAY)
        val msOfDay = epochMs.mod(MS_PER_DAY)
        val (y, m, d) = civilFromDays(days)
        val h = msOfDay / 3_600_000
        val min = msOfDay / 60_000 % 60
        val s = msOfDay / 1000 % 60
        val ms = msOfDay % 1000
        return "${pad(y, 4)}-${pad(m, 2)}-${pad(d, 2)}T${pad(h, 2)}:${pad(min, 2)}:${pad(s, 2)}.${pad(ms, 3)}Z"
    }

    private fun pad(v: Long, width: Int) = v.toString().padStart(width, '0')

    /** Howard Hinnant's days-from-civil inverse (proleptic Gregorian). */
    private fun civilFromDays(daysSinceEpoch: Long): Triple<Long, Long, Long> {
        val z = daysSinceEpoch + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return Triple(y, m, d)
    }
}
