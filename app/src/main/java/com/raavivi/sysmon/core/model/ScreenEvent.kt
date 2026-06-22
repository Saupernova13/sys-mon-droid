package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

/**
 * Input event sent to `/ws/screen` (mirrors `routers/screen.py:ScreenEvent`).
 * Coordinates are normalised 0..1 over the captured monitor; the server scales
 * them to the real resolution. Null fields are omitted by the JSON config.
 */
@Serializable
data class ScreenEvent(
    val type: String,
    val x: Float? = null,
    val y: Float? = null,
    val button: String? = null,
    val dy: Float? = null,
    val key: String? = null,
)
