package com.raavivi.sysmon.ui.health

import com.raavivi.sysmon.core.model.WatchdogTarget
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure text for the Health screen, kept out of the composables so it is unit-testable. */
object HealthText {
    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dayClockFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /** Local HH:mm of unix [seconds]. */
    fun clock(seconds: Double, zone: ZoneId = ZoneId.systemDefault()): String =
        clockFmt.format(Instant.ofEpochSecond(seconds.toLong()).atZone(zone))

    /** Local MM-dd HH:mm of unix [seconds] (event list, which spans days). */
    fun dayClock(seconds: Double, zone: ZoneId = ZoneId.systemDefault()): String =
        dayClockFmt.format(Instant.ofEpochSecond(seconds.toLong()).atZone(zone))

    /** Detail of the first failing check result of the last check, if any. */
    fun lastError(t: WatchdogTarget): String? =
        t.lastCheck?.results?.firstOrNull { !it.ok }?.detail?.takeIf { it.isNotBlank() }

    /**
     * "Healthy" / "Down - <last error>" / "Paused until 18:30" / "Gave up after 3 restarts" ...
     * An unknown state string is shown capitalised rather than hidden.
     */
    fun stateText(t: WatchdogTarget, zone: ZoneId = ZoneId.systemDefault()): String = when (t.state) {
        "healthy" -> "Healthy"
        "degraded" -> withError("Degraded", t)
        "healing" -> "Restarting"
        "down" -> withError("Down", t)
        "given_up" -> "Gave up after ${t.restartsInWindow} ${if (t.restartsInWindow == 1) "restart" else "restarts"}"
        "paused" -> t.pausedUntil?.let { "Paused until ${clock(it, zone)}" } ?: "Paused"
        "unknown" -> "Not checked yet"
        else -> t.state.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun withError(label: String, t: WatchdogTarget): String =
        lastError(t)?.let { "$label - $it" } ?: label

    /** "2 restarts in the window", or null when there were none. */
    fun restartsText(t: WatchdogTarget): String? = when {
        t.restartsInWindow <= 0 -> null
        t.restartsInWindow == 1 -> "1 restart in the window"
        else -> "${t.restartsInWindow} restarts in the window"
    }
}
