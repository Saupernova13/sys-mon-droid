package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

/** Roles: `"admin"` (full control) or `"viewer"` (read-only public account).
 *  Tokens minted before roles existed carry no role and default to admin,
 *  matching the server. */
@Serializable
data class LoginResponse(val token: String, val role: String = "admin")

@Serializable
data class VerifyResponse(val user: String, val role: String = "admin")

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
