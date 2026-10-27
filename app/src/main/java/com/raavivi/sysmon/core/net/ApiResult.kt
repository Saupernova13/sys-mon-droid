package com.raavivi.sysmon.core.net

import com.raavivi.sysmon.core.model.ErrorEnvelope
import retrofit2.HttpException
import java.io.IOException

/** Lightweight result type so call sites never deal with raw exceptions. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /**
     * [code] is the HTTP status; [apiCode] is the backend's machine-readable
     * `code` field (e.g. `jellyfin_streaming`) when the error body carried one.
     */
    data class Err(
        val message: String,
        val code: Int? = null,
        val apiCode: String? = null,
    ) : ApiResult<Nothing>
}

inline fun <T> ApiResult<T>.onOk(block: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Ok) block(value)
    return this
}

inline fun <T> ApiResult<T>.onErr(block: (ApiResult.Err) -> Unit): ApiResult<T> {
    if (this is ApiResult.Err) block(this)
    return this
}

/**
 * Runs a Retrofit suspend call and normalises every failure into [ApiResult.Err]
 * with a human-readable message. The backend's `{ok:false,error}` envelope is
 * preferred over the raw HTTP reason phrase when present.
 */
suspend fun <T> safeCall(block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Ok(block())
} catch (e: HttpException) {
    val envelope = readErrorEnvelope(e)
    ApiResult.Err(parseHttpError(e, envelope), e.code(), envelope?.code)
} catch (e: IOException) {
    ApiResult.Err("Network error: ${e.message ?: "could not reach server"}")
} catch (e: Exception) {
    ApiResult.Err(e.message ?: "Unexpected error")
}

/** Reads the error body once (it can't be re-read) and decodes the backend envelope. */
fun readErrorEnvelope(e: HttpException): ErrorEnvelope? {
    val raw = try {
        e.response()?.errorBody()?.string()
    } catch (_: Exception) {
        null
    }
    return raw?.takeIf { it.isNotBlank() }?.let {
        runCatching { SysMonJson.decodeFromString(ErrorEnvelope.serializer(), it) }.getOrNull()
    }
}

fun parseHttpError(e: HttpException, parsed: ErrorEnvelope? = readErrorEnvelope(e)): String {
    val msg = parsed?.error ?: parsed?.detail
    return when {
        e.code() == 401 -> "Not authorised — please log in again"
        e.code() == 429 -> msg ?: "Too many requests — slow down"
        !msg.isNullOrBlank() -> msg
        else -> "HTTP ${e.code()} ${e.message()}"
    }
}
