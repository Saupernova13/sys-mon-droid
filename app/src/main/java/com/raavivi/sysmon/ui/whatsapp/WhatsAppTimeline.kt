package com.raavivi.sysmon.ui.whatsapp

import com.raavivi.sysmon.core.model.WaTimelineDay
import kotlin.math.sqrt

/** One day's slice of the scrubber track, `start`..`end` as fractions of its height. */
data class TimelineSegment(val day: String, val firstTs: Long, val start: Double, val end: Double)

/** Where a year's first month begins on the track (for the year labels). */
data class TimelineYear(val year: String, val pos: Double)

/** Scrubber geometry: contiguous day segments covering 0..1 plus year label positions. */
data class WaTimeline(val segments: List<TimelineSegment>, val years: List<TimelineYear>) {
    val first: TimelineSegment get() = segments.first()
    val last: TimelineSegment get() = segments.last()
}

private class MonthAcc(val key: String) {
    val days = mutableListOf<WaTimelineDay>()
    var count = 0
}

/**
 * Lay the days out on a 0..1 track. A month's height is the square root of its message count, so a
 * huge month does not squash the quiet ones; days split their month's height by count. Mirrors the
 * web scrubber (whatsapp-timeline.js). Null with fewer than two days: nothing to scrub.
 */
fun buildTimeline(days: List<WaTimelineDay>): WaTimeline? {
    if (days.size < 2) return null
    val months = mutableListOf<MonthAcc>()
    for (d in days) {
        val key = d.day.take(7)
        val last = months.lastOrNull()
        val m = if (last != null && last.key == key) last else MonthAcc(key).also { months += it }
        m.days += d
        m.count += d.count
    }
    val total = months.sumOf { sqrt(it.count.toDouble()) }
    val segments = mutableListOf<TimelineSegment>()
    val years = mutableListOf<TimelineYear>()
    var pos = 0.0
    for (m in months) {
        val span = if (total > 0.0) sqrt(m.count.toDouble()) / total else 1.0 / months.size
        val year = m.key.take(4)
        if (years.isEmpty() || years.last().year != year) years += TimelineYear(year, pos)
        var at = pos
        for (d in m.days) {
            // A zero-count month would divide by zero; share its span evenly instead.
            val share = if (m.count > 0) d.count.toDouble() / m.count else 1.0 / m.days.size
            val end = at + span * share
            segments += TimelineSegment(d.day, d.firstTs, at, end)
            at = end
        }
        pos += span
    }
    segments[0] = segments[0].copy(start = 0.0)
    segments[segments.lastIndex] = segments.last().copy(end = 1.0)
    return WaTimeline(segments, years)
}

/** The segment under track fraction [frac] (clamped to 0..1, non-finite counts as 0). */
fun dayAt(tl: WaTimeline, frac: Double): TimelineSegment {
    val f = if (frac.isFinite()) frac.coerceIn(0.0, 1.0) else 0.0
    val segs = tl.segments
    var lo = 0
    var hi = segs.lastIndex
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (segs[mid].start <= f) lo = mid else hi = mid - 1
    }
    return segs[lo]
}

/** Track position of a message time: start of the last day whose first message is at or before [ts], else 0. */
fun posOfTs(tl: WaTimeline, ts: Long): Double =
    tl.segments.lastOrNull { it.firstTs <= ts }?.start ?: 0.0

/** First day at or after [ymd] (`YYYY-MM-DD`, lexicographic compare), or null when [ymd] is past the last day. */
fun firstDayOnOrAfter(tl: WaTimeline, ymd: String): TimelineSegment? =
    tl.segments.firstOrNull { it.day >= ymd }

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** `2022-02-27` -> `27 Feb 2022`, parsed from the string so no time zone can shift the day. */
fun fmtDay(day: String): String {
    val parts = day.split('-')
    if (parts.size != 3) return day
    val month = parts[1].toIntOrNull()?.let { MONTHS.getOrNull(it - 1) } ?: return day
    val dom = parts[2].toIntOrNull() ?: return day
    return "$dom $month ${parts[0]}"
}
