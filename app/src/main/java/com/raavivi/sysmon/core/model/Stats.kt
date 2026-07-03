package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

/** A single history sample: `{ts, cpu, ram, gpu, disk}` (percentages). */
@Serializable
data class HistoryItem(
    val ts: Double = 0.0,
    val cpu: Double? = null,
    val ram: Double? = null,
    val gpu: Double? = null,
    val disk: Double? = null,
)

/** Range mode: `?start&end` -> oldest-first items + totals. */
@Serializable
data class HistoryRangeResponse(
    val items: List<HistoryItem> = emptyList(),
    val oldestTs: Double? = null,
    val totalCount: Int = 0,
    val bucketSeconds: Long = 0,
    val nextBefore: Double? = null,
)

/** /api/history/recent -> `{"items": [...]}` (ring buffer, oldest -> newest). */
@Serializable
data class HistoryRecentResponse(val items: List<HistoryItem> = emptyList())

// ── Process kill (two-step) ───────────────────────────────────────────────────

@Serializable
data class KillPrepareBody(val pid: Int)

@Serializable
data class KillPrepareResponse(val token: String, val ttlS: Int = 30)

@Serializable
data class KillBody(val pid: Int, val token: String)

// ── Power / scheduled actions ─────────────────────────────────────────────────

/** /api/power/restart|shutdown -> `{"status": "restart"|"shutdown", "delay_seconds": n}`. */
@Serializable
data class PowerActionResponse(
    val status: String = "",
    val delaySeconds: Double = 0.0,
)

/** Endpoints that answer with a bare `{"status": "..."}` (e.g. remote-control). */
@Serializable
data class StatusResponse(val status: String = "")
