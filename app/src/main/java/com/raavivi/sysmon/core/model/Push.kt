package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

/** `POST /api/push/register` — hand the server this device's FCM token. */
@Serializable
data class PushRegisterBody(val token: String, val platform: String = "android")

/** `POST /api/push/register` / `/unregister` reply — how many devices remain. */
@Serializable
data class PushRegisterResponse(
    val registered: Boolean = false,
    val devices: Int = 0,
)

/** `GET /api/push/status` — whether the server can send pushes at all. */
@Serializable
data class PushStatusResponse(
    val configured: Boolean = false,
    val devices: Int = 0,
)
