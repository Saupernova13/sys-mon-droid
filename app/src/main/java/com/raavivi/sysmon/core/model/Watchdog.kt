package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

/**
 * Watchdog payloads, proxied by sys-mon (`/api/watchdog/...`). The keys are
 * snake_case, which `SysMonJson`'s naming strategy maps onto these camelCase
 * properties without annotations.
 */
@Serializable
data class WatchdogCheckResult(
    val label: String = "",
    val ok: Boolean = false,
    val detail: String = "",
    val latencyMs: Long = 0,
)

@Serializable
data class WatchdogLastCheck(
    val at: Double = 0.0,
    val ok: Boolean = false,
    val results: List<WatchdogCheckResult> = emptyList(),
)

@Serializable
data class WatchdogLastHeal(
    val at: Double = 0.0,
    val ok: Boolean = false,
    val detail: String = "",
)

/**
 * One watchdog target. [state] is `unknown`, `healthy`, `degraded`, `healing`,
 * `down`, `given_up` or `paused`; [pausedUntil] is unix seconds or null.
 */
@Serializable
data class WatchdogTarget(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val state: String = "unknown",
    val lastCheck: WatchdogLastCheck? = null,
    val consecutiveFailures: Int = 0,
    val restartsInWindow: Int = 0,
    val lastHeal: WatchdogLastHeal? = null,
    val pausedUntil: Double? = null,
)

/** `GET /api/watchdog/status`. */
@Serializable
data class WatchdogStatus(
    val targets: List<WatchdogTarget> = emptyList(),
    val queuedEvents: Int = 0,
)

/** One stored event from `GET /api/watchdog/events` (newest first). [ts] is unix seconds. */
@Serializable
data class WatchdogEvent(
    val eid: String = "",
    val event: String = "",
    val id: String = "",
    val name: String = "",
    val detail: String = "",
    val state: String = "",
    val ts: Double = 0.0,
    val receivedAt: Double = 0.0,
    val pushed: Boolean = false,
)

@Serializable
data class WatchdogEventsResponse(
    val events: List<WatchdogEvent> = emptyList(),
)

/** Body of `POST /api/watchdog/targets/{id}/pause`. */
@Serializable
data class WatchdogPauseBody(val minutes: Int)
