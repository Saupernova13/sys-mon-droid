package com.raavivi.sysmon.core.data.whatsapp

import com.raavivi.sysmon.core.model.ErrorEnvelope
import com.raavivi.sysmon.core.model.ManualCardError
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import retrofit2.Response

/**
 * The typed reason behind a failed manual-card call: the status and the envelope's `code`
 * (`docs/API.md`, "Your cards"). A 400 keeps the server's own reason, which is a fixed sentence.
 */
fun manualErrorOf(err: ApiResult.Err): ManualCardError = when (err.code) {
    400 -> ManualCardError.Refused(err.message.takeIf { it.isNotBlank() && !it.startsWith("HTTP ") } ?: "The request was refused")
    404 -> ManualCardError.Off
    409 -> when (err.apiCode) {
        "too_many" -> ManualCardError.TooMany
        "no_model" -> ManualCardError.NoModel
        "finished" -> ManualCardError.Finished
        "not_manual" -> ManualCardError.NotManual
        else -> ManualCardError.Other
    }
    503 -> ManualCardError.Unavailable
    else -> ManualCardError.Other
}

/** A 2xx empty answer (204) as Ok; else an Err carrying the status and the envelope's message and `code`. */
internal fun emptyResult(r: Response<Unit>): ApiResult<Unit> {
    if (r.isSuccessful) return ApiResult.Ok(Unit)
    val raw = runCatching { r.errorBody()?.string() }.getOrNull()?.takeIf { it.isNotBlank() }
    val envelope = raw?.let { runCatching { SysMonJson.decodeFromString(ErrorEnvelope.serializer(), it) }.getOrNull() }
    val message = (envelope?.error ?: envelope?.detail)?.takeIf { it.isNotBlank() } ?: "HTTP ${r.code()}"
    return ApiResult.Err(message, r.code(), envelope?.code)
}
