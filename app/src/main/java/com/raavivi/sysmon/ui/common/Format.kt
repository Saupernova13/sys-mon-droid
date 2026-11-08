package com.raavivi.sysmon.ui.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Human-readable byte size, e.g. 1536 -> "1.5 KB". */
fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exp.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[exp - 1])
}

/** Bytes-per-second rate, e.g. "2.4 MB/s". */
fun formatRate(bytesPerSec: Double?): String =
    if (bytesPerSec == null) "—" else formatBytes(bytesPerSec.toLong()) + "/s"

fun formatPct(pct: Double?): String =
    if (pct == null) "—" else String.format(Locale.US, "%.0f%%", pct)

fun formatPct1(pct: Double?): String =
    if (pct == null) "—" else String.format(Locale.US, "%.1f%%", pct)

fun formatMhz(mhz: Double?): String =
    if (mhz == null || mhz <= 0) "—" else String.format(Locale.US, "%.2f GHz", mhz / 1000.0)

fun formatTemp(celsius: Double?): String =
    if (celsius == null) "—" else String.format(Locale.US, "%.0f°C", celsius)

/** Watts with adaptive precision: "3.2 W", "87 W", "1.24 kW". */
fun formatWatts(watts: Double): String = when {
    watts >= 1000 -> String.format(Locale.US, "%.2f kW", watts / 1000.0)
    watts >= 10 -> String.format(Locale.US, "%.0f W", watts)
    else -> String.format(Locale.US, "%.1f W", watts)
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
private val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

/** Epoch seconds (the backend uses float seconds) -> "HH:mm:ss". */
fun formatTime(epochSeconds: Double?): String =
    if (epochSeconds == null) "—" else timeFmt.format(Date((epochSeconds * 1000).toLong()))

fun formatDateTime(epochSeconds: Double?): String =
    if (epochSeconds == null) "—" else dateTimeFmt.format(Date((epochSeconds * 1000).toLong()))

fun formatDuration(ms: Double?): String {
    if (ms == null) return "—"
    return when {
        ms < 1000 -> String.format(Locale.US, "%.0f ms", ms)
        ms < 60_000 -> String.format(Locale.US, "%.1f s", ms / 1000.0)
        else -> String.format(Locale.US, "%.1f min", ms / 60_000.0)
    }
}

fun relativeTime(epochSeconds: Double?): String {
    if (epochSeconds == null) return "—"
    val deltaMs = System.currentTimeMillis() - (epochSeconds * 1000).toLong()
    val s = abs(deltaMs) / 1000
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

/** A span longer than this labels chart axes with dates instead of clock times. */
const val AXIS_DATE_SPAN_S = 2 * 86_400.0

/** Axis/tooltip label for [epochSeconds] on a chart spanning [spanSeconds]: "HH:mm", or "yyyy-MM-dd" for long spans. */
fun formatAxisTime(epochSeconds: Double, spanSeconds: Double): String {
    val fmt = SimpleDateFormat(if (spanSeconds > AXIS_DATE_SPAN_S) "yyyy-MM-dd" else "HH:mm", Locale.US)
    return fmt.format(Date((epochSeconds * 1000).toLong()))
}

/** Playback clock: "0:59", "59:59", "1:00:00" (hours only past the first hour). */
fun formatClock(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, ss)
    else String.format(Locale.US, "%d:%02d", m, ss)
}

/** "12:34 / 45:00", "12:34", or "" when the position is not reported. */
fun formatStreamPosition(positionS: Double?, runtimeS: Double?): String = when {
    positionS == null -> ""
    runtimeS == null -> formatClock(positionS)
    else -> "${formatClock(positionS)} / ${formatClock(runtimeS)}"
}
