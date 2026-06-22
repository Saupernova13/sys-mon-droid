package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String)

@Serializable
data class VerifyResponse(val user: String)

/** The generic `{"ok": true}` envelope several POST/DELETE endpoints return. */
@Serializable
data class OkResponse(val ok: Boolean = true)

/** The standard error envelope `{"ok": false, "error": "...", "code": null}`. */
@Serializable
data class ErrorEnvelope(
    val ok: Boolean = false,
    val error: String? = null,
    val code: String? = null,
    val detail: String? = null,
)
