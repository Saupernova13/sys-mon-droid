package com.raavivi.sysmon.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.Serializable

/**
 * `GET /api/droid/update`. Unlike most of the API this payload is camelCase, so
 * every multi-word key carries [JsonNames] (the snake_case naming strategy of
 * `SysMonJson` also rewrites explicit `@SerialName`s, but not alternate names). When the feature is off or no
 * valid APK is staged only [enabled] and [available] are present.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DroidUpdateResponse(
    val enabled: Boolean = false,
    val available: Boolean = false,
    @JsonNames("versionCode") val versionCode: Long? = null,
    @JsonNames("versionName") val versionName: String? = null,
    val size: Long? = null,
    val sha256: String? = null,
    val notes: String? = null,
    @JsonNames("builtAt") val builtAt: String? = null,
    @JsonNames("apkPath") val apkPath: String? = null,
)
