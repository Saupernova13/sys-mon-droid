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
)

/** /api/history/recent -> bare array of samples (newest in ring order). */

// ── Process kill (two-step) ───────────────────────────────────────────────────

@Serializable
data class KillPrepareBody(val pid: Int)

@Serializable
data class KillPrepareResponse(val token: String, val ttlS: Int = 30)

@Serializable
data class KillBody(val pid: Int, val token: String)

// ── Power / scheduled actions ─────────────────────────────────────────────────

@Serializable
data class ScheduledResponse(
    val ok: Boolean = true,
    val action: String? = null,
    val scheduled: Boolean? = null,
    val message: String? = null,
)
