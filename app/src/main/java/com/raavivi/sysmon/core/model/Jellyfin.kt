package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

/** `notify` block of `GET /api/jellyfin/config`. */
@Serializable
data class JellyfinNotify(
    val web: Boolean = true,
    val push: Boolean = true,
    val includeUser: Boolean = true,
    val includeTitle: Boolean = true,
    val includePosition: Boolean = true,
    val includeDevice: Boolean = true,
    val updateIntervalS: Int = 60,
    val gatePower: Boolean = false,
)

/** `GET /api/jellyfin/config`. The API key is write-only: only [apiKeySet] comes back. */
@Serializable
data class JellyfinConfig(
    val url: String = "",
    val apiKeySet: Boolean = false,
    val enabled: Boolean = false,
    val pollIntervalS: Int = 10,
    val notify: JellyfinNotify = JellyfinNotify(),
)

/** Partial `POST /api/jellyfin/config`: null fields are omitted, so the server keeps them. */
@Serializable
data class JellyfinNotifyPatch(
    val web: Boolean? = null,
    val push: Boolean? = null,
    val includeUser: Boolean? = null,
    val includeTitle: Boolean? = null,
    val includePosition: Boolean? = null,
    val includeDevice: Boolean? = null,
    val updateIntervalS: Int? = null,
    val gatePower: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = web == null && push == null && includeUser == null && includeTitle == null &&
            includePosition == null && includeDevice == null && updateIntervalS == null && gatePower == null
}

@Serializable
data class JellyfinConfigPatch(
    val url: String? = null,
    val apiKey: String? = null,
    val enabled: Boolean? = null,
    val pollIntervalS: Int? = null,
    val notify: JellyfinNotifyPatch? = null,
)

/** `POST /api/jellyfin/test`; blank fields fall back to the saved values server-side. */
@Serializable
data class JellyfinTestBody(val url: String? = null, val apiKey: String? = null)

@Serializable
data class JellyfinTestResponse(
    val ok: Boolean = false,
    val serverName: String = "",
    val version: String = "",
)

/** One session in `GET /api/jellyfin/streams`; optional fields vanish with the matching notify option. */
@Serializable
data class JellyfinStreamRow(
    val sessionId: String = "",
    val title: String = "",
    val itemType: String = "",
    val paused: Boolean = false,
    val user: String? = null,
    val positionS: Double? = null,
    val runtimeS: Double? = null,
    val device: String? = null,
    val client: String? = null,
    val playMethod: String? = null,
)

/** `GET /api/jellyfin/streams`: the server poller's cached view. */
@Serializable
data class JellyfinStreamsResponse(
    val linked: Boolean = false,
    val enabled: Boolean = false,
    val ok: Boolean = true,
    val error: String? = null,
    val updatedAt: Double? = null,
    val web: Boolean = true,
    val pollIntervalS: Int = 10,
    val streams: List<JellyfinStreamRow> = emptyList(),
)
