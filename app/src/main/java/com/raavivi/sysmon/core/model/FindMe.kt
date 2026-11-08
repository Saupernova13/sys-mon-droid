package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

/** One location fix (`Fix` in API.md "Find Me"); everything after [lon] is optional. */
@Serializable
data class FindMeFix(
    val ts: Double = 0.0,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val accuracyM: Double? = null,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val provider: String? = null,
    /** `trail`, `locate`, `boot` or `manual`. */
    val source: String? = null,
    val batteryPct: Double? = null,
    val charging: Boolean? = null,
    /** `wifi`, `cellular`, `ethernet`, `none` or null. */
    val network: String? = null,
)

/** A queued command. [kind]: locate / ring / stop_ring. [state]: pending, delivered, done, failed, expired. */
@Serializable
data class FindMeCommand(
    val id: String = "",
    val kind: String = "",
    val createdAt: Double = 0.0,
    val state: String = "",
    val deliveredAt: Double? = null,
    val doneAt: Double? = null,
    val error: String? = null,
)

/** Admin view of a paired device. [commands] holds open commands plus the last few finished, newest first. */
@Serializable
data class FindMeDevice(
    val id: String = "",
    val name: String = "",
    val platform: String? = null,
    val model: String? = null,
    val appVersion: String? = null,
    val createdAt: Double = 0.0,
    val lastSeen: Double? = null,
    val intervalS: Int = 300,
    val push: Boolean = false,
    val ringing: Boolean = false,
    val lastFix: FindMeFix? = null,
    val batteryPct: Double? = null,
    val charging: Boolean? = null,
    val network: String? = null,
    val commands: List<FindMeCommand> = emptyList(),
    /** Live status: `moving`, `stationary`, `unreachable` or `unknown` (older servers omit it). */
    val status: String? = null,
    /** When the current [status] began (Unix seconds). */
    val statusSince: Double? = null,
    /** Where a stationary device sits. */
    val stationaryAt: FindMeStationaryAt? = null,
)

@Serializable
data class FindMeStationaryAt(val lat: Double = 0.0, val lon: Double = 0.0, val radiusM: Double = 0.0)

@Serializable
data class FindMeDevicesResponse(
    val devices: List<FindMeDevice> = emptyList(),
    val retentionDays: Int = 30,
    val pushConfigured: Boolean = false,
)

/** `POST /api/findme/pair`: [server] is the origin this client uses (the server cannot know its public URL). */
@Serializable
data class FindMePairBody(val name: String? = null, val server: String)

@Serializable
data class FindMePairResponse(
    val code: String = "",
    val expiresAt: Double = 0.0,
    val link: String = "",
    val qrSvg: String = "",
)

/** `PATCH /api/findme/devices/{id}`: unset fields are left unchanged. */
@Serializable
data class FindMePatchBody(val name: String? = null, val intervalS: Int? = null)

@Serializable
data class FindMeHistoryResponse(val fixes: List<FindMeFix> = emptyList())

/** Reply to locate / ring / ring-stop; [pushed] says whether an FCM push went out. */
@Serializable
data class FindMeCommandResponse(
    val command: FindMeCommand = FindMeCommand(),
    val pushed: Boolean = false,
)

/** `GET /api/findme/geocode`; [address] is null when the upstream lookup failed. */
@Serializable
data class FindMeGeocodeResponse(val address: String? = null, val cached: Boolean = false)
