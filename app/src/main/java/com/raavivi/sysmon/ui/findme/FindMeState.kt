package com.raavivi.sysmon.ui.findme

import com.raavivi.sysmon.core.model.FindMeCommand
import com.raavivi.sysmon.core.model.FindMeDevice
import com.raavivi.sysmon.ui.common.Severity
import com.raavivi.sysmon.ui.common.worst
import java.util.Locale

/** Pure Find Me display logic, ported from the web window's `findme-state.js`. */

const val POLL_FAST_MS = 5_000L
const val POLL_SLOW_MS = 30_000L

/** A finished command's result stays on its button this long. */
private const val RESULT_TTL_S = 600.0
private const val FIX_STALE_DAY_S = 86_400.0
private const val BATTERY_CAUTION = 20.0
private const val BATTERY_WARNING = 10.0

/** History window selectable on the map; [secs] is the look-back from now. */
enum class TrailRange(val label: String, val secs: Double) {
    Day("24 h", 86_400.0),
    Week("7 d", 7 * 86_400.0),
    Month("30 d", 30 * 86_400.0),
}

/** "just now", "45 s ago", "2 min ago", "3 h ago", "2 d ago"; "never" when unknown. */
fun formatAge(ageS: Double?): String {
    if (ageS == null || !ageS.isFinite()) return "never"
    val s = maxOf(0.0, ageS).toLong()
    return when {
        s < 2 -> "just now"
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

/** "+-12 m" / "+-1.2 km" with the plus-minus sign; empty when unknown. */
fun formatAccuracy(m: Double?): String = when {
    m == null || !m.isFinite() -> ""
    m >= 1000 -> String.format(Locale.US, "±%.1f km", m / 1000.0)
    else -> "±${Math.round(m)} m"
}

/** Client-side geocode cache key; matches the server's 4-decimal rounding. */
fun geocodeKey(lat: Double, lon: Double): String = String.format(Locale.US, "%.4f,%.4f", lat, lon)

/** Seconds since the device's last fix; null when it has none. */
fun fixAge(device: FindMeDevice, nowS: Double): Double? = device.lastFix?.let { nowS - it.ts }

/** Fix freshness: offline with no fix, warning after a day, caution after two intervals. */
fun fixAgeSeverity(ageS: Double?, intervalS: Int): Severity = when {
    ageS == null || !ageS.isFinite() -> Severity.Offline
    ageS >= FIX_STALE_DAY_S -> Severity.Warning
    intervalS > 0 && ageS >= 2 * intervalS -> Severity.Caution
    else -> Severity.Nominal
}

/** Battery percent; a charging phone is never alarming. */
fun batterySeverity(pct: Double?, charging: Boolean?): Severity = when {
    pct == null || !pct.isFinite() || charging == true -> Severity.Nominal
    pct <= BATTERY_WARNING -> Severity.Warning
    pct <= BATTERY_CAUTION -> Severity.Caution
    else -> Severity.Nominal
}

/** Row severity: no fix is offline whatever the battery says, else the worse of age and battery. */
fun deviceSeverity(device: FindMeDevice, nowS: Double): Severity {
    val age = fixAgeSeverity(fixAge(device, nowS), device.intervalS)
    if (age == Severity.Offline) return Severity.Offline
    return worst(age, batterySeverity(device.batteryPct, device.charging))
}

fun isOpen(cmd: FindMeCommand?): Boolean = cmd != null && (cmd.state == "pending" || cmd.state == "delivered")

/** True when any device has an open command (drives the fast poll). */
fun anyPending(devices: List<FindMeDevice>): Boolean = devices.any { d -> d.commands.any { isOpen(it) } }

fun pollDelayMs(pending: Boolean, pairOpen: Boolean): Long = if (pending || pairOpen) POLL_FAST_MS else POLL_SLOW_MS

/** Phase of a command button; drives its label and ink. */
enum class CommandPhase { Pending, Done, Failed, Expired, Ringing, Stopping }

/** Status line under a button, or null when there is nothing to show. */
data class CommandStatus(val phase: CommandPhase, val text: String)

private fun latest(device: FindMeDevice, kinds: Set<String>): FindMeCommand? =
    device.commands.firstOrNull { it.kind in kinds }

private fun finishedAt(cmd: FindMeCommand): Double = cmd.doneAt ?: cmd.deliveredAt ?: cmd.createdAt

/** Locate button status from the device's command list (newest first). */
fun locateStatus(device: FindMeDevice, nowS: Double): CommandStatus? {
    val cmd = latest(device, setOf("locate")) ?: return null
    if (isOpen(cmd)) return CommandStatus(CommandPhase.Pending, "Locating...")
    val at = finishedAt(cmd)
    if (nowS - at > RESULT_TTL_S) return null
    return when (cmd.state) {
        "done" -> CommandStatus(CommandPhase.Done, "Located ${formatAge(nowS - at)}")
        "failed" -> CommandStatus(
            CommandPhase.Failed,
            cmd.error?.takeIf { it.isNotBlank() }?.let { "Locate failed: $it" } ?: "Locate failed",
        )
        else -> CommandStatus(CommandPhase.Expired, "No answer - phone offline?")
    }
}

/**
 * Ring button status. A stop in flight wins over ringing so the button does not
 * offer Stop twice; ringing wins over history.
 */
fun ringStatus(device: FindMeDevice, nowS: Double): CommandStatus? {
    val cmd = latest(device, setOf("ring", "stop_ring"))
    if (cmd != null && cmd.kind == "stop_ring" && isOpen(cmd)) {
        return CommandStatus(CommandPhase.Stopping, "Stopping...")
    }
    if (device.ringing) return CommandStatus(CommandPhase.Ringing, "Ringing")
    if (cmd == null) return null
    if (cmd.kind == "ring" && isOpen(cmd)) return CommandStatus(CommandPhase.Pending, "Ring sent...")
    if (nowS - finishedAt(cmd) > RESULT_TTL_S) return null
    if (cmd.kind == "ring" && cmd.state == "expired") {
        return CommandStatus(CommandPhase.Expired, "No answer - phone offline?")
    }
    if (cmd.kind == "ring" && cmd.state == "failed") {
        return CommandStatus(
            CommandPhase.Failed,
            cmd.error?.takeIf { it.isNotBlank() }?.let { "Ring failed: $it" } ?: "Ring failed",
        )
    }
    return null
}

/** Devices in [devices] whose id is not in [known]. */
fun newDevices(known: Set<String>, devices: List<FindMeDevice>): List<FindMeDevice> =
    devices.filter { it.id !in known }

/** "ABCD-EFGH" from an 8-character code (idempotent). */
fun formatCode(code: String): String {
    val c = code.replace("-", "").uppercase()
    return if (c.length == 8) "${c.take(4)}-${c.drop(4)}" else c
}

/** "m:ss" until [expiresAtS], floored at 0:00. */
fun countdown(expiresAtS: Double, nowS: Double): String {
    val s = maxOf(0.0, Math.ceil(expiresAtS - nowS)).toLong()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** The Interval prompt's value (server range 60..3600), or null if invalid. */
fun parseInterval(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 60..3600 }

/** "5 min" style label for a report interval. */
fun formatInterval(intervalS: Int): String = when {
    intervalS < 60 -> "$intervalS s"
    intervalS % 60 == 0 -> "${intervalS / 60} min"
    else -> String.format(Locale.US, "%.1f min", intervalS / 60.0)
}
