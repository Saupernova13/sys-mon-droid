package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

/** One probe result in a site's sparkline history (oldest first). */
@Serializable
data class SiteSample(
    val ok: Boolean = false,
    val latencyMs: Long = 0,
)

/**
 * One monitored site as served by `GET /api/sites` (`states_payload` in the
 * server's `sites.rs`). [state] is `up`, `down` or `unknown`; [since] is the
 * unix-seconds time of the last transition and null while unknown.
 */
@Serializable
data class SiteStatus(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val state: String = "unknown",
    val since: Double? = null,
    val consecutiveFailures: Int = 0,
    val lastStatus: Int? = null,
    val lastLatencyMs: Long? = null,
    val lastError: String? = null,
    val lastChecked: Double? = null,
    val history: List<SiteSample> = emptyList(),
)

/** `GET /api/sites`. The server names the list `sites`. */
@Serializable
data class SitesResponse(
    val enabled: Boolean = false,
    val intervalS: Int? = null,
    val failThreshold: Int? = null,
    val sites: List<SiteStatus> = emptyList(),
)
