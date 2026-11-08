package com.raavivi.sysmon.core.push

import com.raavivi.sysmon.ui.findme.formatSince
import java.time.ZoneId
import java.util.Locale

/**
 * A Find Me reachability push (`type=findme_device`), parsed from its FCM data
 * map. The server sends one when a tracked device goes quiet for longer than its
 * threshold ([UNREACHABLE]) and again when it reports back ([REACHABLE]). FCM
 * data values are all strings; numbers that fail to parse read as absent.
 */
data class FindMeAlert(
    val event: String,
    val deviceId: String,
    val name: String,
    val lastSeen: Double?,
    val fixTs: Double?,
    val lat: Double?,
    val lon: Double?,
    val batteryPct: Int?,
    val ts: Double?,
) {
    val unreachable: Boolean get() = event == UNREACHABLE

    /** Notification title: "Pixel is unreachable" / "Pixel is back". */
    fun title(): String = if (unreachable) "$name is unreachable" else "$name is back"

    /**
     * Notification text. Unreachable: "Last seen 14:02 (battery 23%)", each part
     * only when known. Reachable: "Reporting again since 15:30".
     */
    fun text(zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val now = ts ?: lastSeen ?: 0.0
        if (!unreachable) {
            val back = lastSeen ?: ts
            return if (back != null) "Reporting again since ${formatSince(back, now, zone, locale)}" else "Reporting again"
        }
        val seen = lastSeen?.let { "Last seen ${formatSince(it, now, zone, locale)}" } ?: "Not seen recently"
        return if (batteryPct != null) "$seen (battery $batteryPct%)" else seen
    }

    companion object {
        const val TYPE = "findme_device"
        const val UNREACHABLE = "unreachable"
        const val REACHABLE = "reachable"

        /** Null unless the map is a well-formed `findme_device` push with a known event. */
        fun from(data: Map<String, String>): FindMeAlert? {
            if (data["type"] != TYPE) return null
            val event = data["event"]?.takeIf { it == UNREACHABLE || it == REACHABLE } ?: return null
            val id = data["device_id"]?.takeIf { it.isNotBlank() } ?: return null
            return FindMeAlert(
                event = event,
                deviceId = id,
                name = data["name"]?.takeIf { it.isNotBlank() } ?: "A device",
                lastSeen = data["last_seen"].num(),
                fixTs = data["fix_ts"].num(),
                lat = data["lat"].num(),
                lon = data["lon"].num(),
                batteryPct = data["battery_pct"].num()?.let { Math.round(it).toInt() },
                ts = data["ts"].num(),
            )
        }

        private fun String?.num(): Double? = this?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
