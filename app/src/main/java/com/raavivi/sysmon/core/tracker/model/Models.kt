package com.raavivi.sysmon.core.tracker.model

import kotlinx.serialization.Serializable

/**
 * One location sample. Everything after [lon] is optional. [source] is `trail`,
 * `locate`, `boot` or `manual`; [network] is `wifi`, `cellular`, `ethernet`, `none`
 * or null. [ts] is Unix seconds.
 */
@Serializable
data class Fix(
    val ts: Double,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double? = null,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val provider: String? = null,
    val source: String = SOURCE_TRAIL,
    val batteryPct: Int? = null,
    val charging: Boolean? = null,
    val network: String? = null,
) {
    companion object {
        const val SOURCE_TRAIL = "trail"
        const val SOURCE_LOCATE = "locate"
        const val SOURCE_BOOT = "boot"
        const val SOURCE_MANUAL = "manual"
    }
}

/** `POST /api/findme/device/report` body; an empty [fixes] list is a heartbeat. */
@Serializable
data class ReportBody(
    val fixes: List<Fix>,
    val batteryPct: Int? = null,
    val charging: Boolean? = null,
    val network: String? = null,
    val appVersion: String? = null,
    val ringing: Boolean = false,
)

@Serializable
data class ReportResponse(
    val ok: Boolean = true,
    val intervalS: Int? = null,
    /** Seconds between location samples; the server's `sample_interval_s`. */
    val sampleS: Int? = null,
    val commands: List<Command> = emptyList(),
)

/** A server command. [kind] is `locate`, `ring` or `stop_ring`. */
@Serializable
data class Command(
    val id: String,
    val kind: String,
    val createdAt: Double? = null,
    val state: String? = null,
    val deliveredAt: Double? = null,
    val doneAt: Double? = null,
    val error: String? = null,
) {
    companion object {
        const val LOCATE = "locate"
        const val RING = "ring"
        const val STOP_RING = "stop_ring"
    }
}

@Serializable
data class CommandsResponse(val commands: List<Command> = emptyList())

@Serializable
data class PairBody(
    val code: String,
    val name: String,
    val model: String,
    val platform: String,
    val appVersion: String,
)

@Serializable
data class PairResponse(
    val deviceId: String,
    val token: String,
    val intervalS: Int = 300,
    val sampleS: Int = 60,
    val serverName: String? = null,
)

/** FCM token registration; an empty [token] clears it. */
@Serializable
data class PushBody(val token: String)

/** Command acknowledgement: [state] is `delivered`, `done` or `failed`. */
@Serializable
data class AckBody(val state: String, val error: String? = null)

@Serializable
data class OkResponse(val ok: Boolean = true)

/** Error body: `{"code": "device_unknown"}` and friends. */
@Serializable
data class ErrorEnvelope(
    val code: String? = null,
    val error: String? = null,
    val detail: String? = null,
)
