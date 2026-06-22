package com.raavivi.sysmon.core.net

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Single JSON configuration shared by Retrofit and the WebSocket decoders.
 *
 * The backend speaks snake_case; the naming strategy lets the Kotlin models stay
 * idiomatic camelCase without a `@SerialName` on every field. Unknown keys are
 * ignored so backend additions don't break the client.
 */
@OptIn(ExperimentalSerializationApi::class)
val SysMonJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
    namingStrategy = JsonNamingStrategy.SnakeCase
}
