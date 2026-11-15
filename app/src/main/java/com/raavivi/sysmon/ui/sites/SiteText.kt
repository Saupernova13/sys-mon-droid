package com.raavivi.sysmon.ui.sites

import com.raavivi.sysmon.core.model.SiteStatus
import com.raavivi.sysmon.core.push.humanSpan

/** Pure text for a Sites row, kept out of the composables so it is unit-testable. */
object SiteText {

    /**
     * "Up 3 d" / "Down 12 min - timeout" / "Not checked yet". [nowS] is the
     * current unix time in seconds; a missing `since` drops the duration.
     */
    fun status(site: SiteStatus, nowS: Long): String {
        val span = site.since?.let { humanSpan(nowS - it.toLong()) }
        return when (site.state) {
            "up" -> if (span != null) "Up $span" else "Up"
            "down" -> buildString {
                append("Down")
                if (span != null) append(" ").append(span)
                site.lastError?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
            }
            else -> "Not checked yet"
        }
    }

    /** "123 ms" or null when the site has not been probed. */
    fun latency(site: SiteStatus): String? = site.lastLatencyMs?.let { "$it ms" }
}
