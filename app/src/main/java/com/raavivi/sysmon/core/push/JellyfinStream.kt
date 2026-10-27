package com.raavivi.sysmon.core.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One active Jellyfin playback session. Only [sessionId], [title] and [paused]
 * are guaranteed; the rest are omitted by the server when the matching
 * "include_*" notify option is off, so each defaults to absent.
 */
@Serializable
data class JellyfinStream(
    @SerialName("session_id") val sessionId: String = "",
    val title: String = "",
    @SerialName("item_type") val itemType: String = "",
    val paused: Boolean = false,
    val user: String? = null,
    @SerialName("position_s") val positionS: Long? = null,
    @SerialName("runtime_s") val runtimeS: Long? = null,
    val device: String? = null,
    val client: String? = null,
    @SerialName("play_method") val playMethod: String? = null,
)

/**
 * A Jellyfin-stream push, parsed from its FCM data map. Like [PlugAlert], each
 * message carries the *complete* current set of streams, so an empty [streams]
 * means nothing is playing: clear everything.
 */
data class JellyfinAlert(
    val event: String,
    val streams: List<JellyfinStream>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parse a `type=jellyfin_stream` data map. Returns null only when there
         * is no `event`; a missing or malformed `streams` array reads as empty.
         */
        fun from(data: Map<String, String>): JellyfinAlert? {
            val event = data["event"] ?: return null
            val streams = data["streams"]?.let { raw ->
                runCatching { json.decodeFromString<List<JellyfinStream>>(raw) }
                    .getOrDefault(emptyList())
                    .filter { it.sessionId.isNotBlank() }
            } ?: emptyList()
            return JellyfinAlert(event, streams)
        }
    }
}
