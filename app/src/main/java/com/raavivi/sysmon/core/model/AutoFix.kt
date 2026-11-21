package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

/**
 * `GET api/autofix`, `POST api/autofix/start` and `POST api/autofix/reply` all answer with this. Every field has
 * a default so a slightly different server shape decodes instead of crashing. Times are unix seconds.
 * [state] is `idle`, `running`, `done` or `failed`.
 */
@Serializable
data class AutoFixResponse(
    val enabled: Boolean = true,
    val state: String = AutoFixStates.IDLE,
    val startedAt: Double? = null,
    val finishedAt: Double? = null,
    val turns: List<AutoFixTurn> = emptyList(),
    val followUpAllowed: Boolean = false,
    val error: String? = null,
)

/** One message of the exchange: [role] is `agent` or `user`. */
@Serializable
data class AutoFixTurn(
    val role: String = AutoFixStates.ROLE_AGENT,
    val text: String = "",
    val at: Double = 0.0,
)

/** Body of `POST api/autofix/reply`; the server rejects empty text and anything over 500 characters. */
@Serializable
data class AutoFixReplyBody(val text: String)

object AutoFixStates {
    const val IDLE = "idle"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val ROLE_AGENT = "agent"
    const val ROLE_USER = "user"
}
