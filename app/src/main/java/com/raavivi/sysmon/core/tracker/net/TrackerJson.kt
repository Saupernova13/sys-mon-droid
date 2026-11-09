package com.raavivi.sysmon.core.tracker.net

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Single JSON configuration for Retrofit, the fix queue file and the tests.
 *
 * The backend speaks snake_case; the naming strategy keeps the Kotlin models
 * camelCase. Defaults are encoded so `ringing: false` is always sent, nulls are
 * omitted, unknown keys are ignored.
 */
@OptIn(ExperimentalSerializationApi::class)
val TrackerJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}
