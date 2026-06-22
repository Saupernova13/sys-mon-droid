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
fun formatRate(bytesPerSec: Double): String =
    formatBytes(bytesPerSec.toLong()) + "/s"

fun formatPct(pct: Double?): String =
    if (pct == null) "—" else String.format(Locale.US, "%.0f%%", pct)

fun formatPct1(pct: Double?): String =
    if (pct == null) "—" else String.format(Locale.US, "%.1f%%", pct)

fun formatMhz(mhz: Double): String =
    if (mhz <= 0) "—" else String.format(Locale.US, "%.2f GHz", mhz / 1000.0)

fun formatTemp(celsius: Double?): String =
    if (celsius == null) "—" else String.format(Locale.US, "%.0f°C", celsius)

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
