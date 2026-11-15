package com.raavivi.sysmon.core.push

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A site-monitoring push (`type=site_status`), parsed from its FCM data map.
 * The server sends one when a site is confirmed down ([DOWN], with `error`) and
 * one when it recovers ([UP], with `downtime_s`). All values arrive as strings.
 */
data class SiteAlert(
    val event: String,
    val id: String,
    val name: String,
    val url: String,
    /** Unix seconds of the transition, if the server sent a usable value. */
    val since: Long?,
    val error: String?,
    val downtimeS: Long?,
) {
    val down: Boolean get() = event == DOWN

    /** "Jellyfin is down" / "Jellyfin is back up". */
    fun title(): String = if (down) "$name is down" else "$name is back up"

    /** Down: "timeout - since 14:02". Up: "Down for 12 min". Each part only when known. */
    fun text(zone: ZoneId = ZoneId.systemDefault()): String =
        if (down) {
            val err = error ?: "unreachable"
            since?.let { "$err - since ${clock(it, zone)}" } ?: err
        } else {
            downtimeS?.let { "Down for ${humanSpan(it)}" } ?: "Back up"
        }

    companion object {
        const val TYPE = "site_status"
        const val DOWN = "down"
        const val UP = "up"

        /** Null unless the map is a well-formed `site_status` push with a known event and an id. */
        fun from(data: Map<String, String>): SiteAlert? {
            if (data["type"] != TYPE) return null
            val event = data["event"]?.takeIf { it == DOWN || it == UP } ?: return null
            val id = data["id"]?.takeIf { it.isNotBlank() } ?: return null
            return SiteAlert(
                event = event,
                id = id,
                name = data["name"]?.takeIf { it.isNotBlank() } ?: data["url"]?.takeIf { it.isNotBlank() } ?: "A site",
                url = data["url"].orEmpty(),
                since = data["since"].secs(),
                error = data["error"]?.takeIf { it.isNotBlank() },
                downtimeS = data["downtime_s"].secs(),
            )
        }

        private fun String?.secs(): Long? =
            this?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.toLong()

        private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")

        /** Local HH:mm of unix [seconds]. */
        fun clock(seconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            clockFmt.format(Instant.ofEpochSecond(seconds).atZone(zone))
    }
}

/**
 * Coarse human span for [seconds]: "45 s", "12 min", "3 h 20 min", "2 d 4 h".
 * Shows at most two units and drops a zero second unit.
 */
fun humanSpan(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val d = s / 86_400
    val h = (s % 86_400) / 3600
    val m = (s % 3600) / 60
    return when {
        d > 0 -> if (h > 0) "$d d $h h" else "$d d"
        h > 0 -> if (m > 0) "$h h $m min" else "$h h"
        m > 0 -> "$m min"
        else -> "$s s"
    }
}
