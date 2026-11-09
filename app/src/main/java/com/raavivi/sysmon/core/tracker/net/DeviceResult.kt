package com.raavivi.sysmon.core.tracker.net

import com.raavivi.sysmon.core.tracker.model.ErrorEnvelope
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/** Lightweight result type so call sites never deal with raw exceptions. */
sealed interface DeviceResult<out T> {
    data class Ok<T>(val value: T) : DeviceResult<T>

    /** [code] is the HTTP status; [apiCode] is the backend's `code` field, if any. */
    data class Err(
        val message: String,
        val code: Int? = null,
        val apiCode: String? = null,
    ) : DeviceResult<Nothing> {
        /** True for the 401 `device_unknown` answer: this phone was removed. */
        val isDeviceUnknown: Boolean get() = code == 401 && apiCode == "device_unknown"
    }
}

/** Runs a Retrofit suspend call and turns every failure into [DeviceResult.Err]. */
suspend fun <T> deviceCall(block: suspend () -> T): DeviceResult<T> = try {
    DeviceResult.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    val envelope = readErrorEnvelope(e)
    DeviceResult.Err(httpErrorMessage(e.code(), envelope, e.message()), e.code(), envelope?.code)
} catch (e: IOException) {
    DeviceResult.Err("Network error: ${e.message ?: "could not reach server"}")
} catch (e: Exception) {
    DeviceResult.Err(e.message ?: "Unexpected error")
}

private fun readErrorEnvelope(e: HttpException): ErrorEnvelope? {
    val raw = try {
        e.response()?.errorBody()?.string()
    } catch (_: Exception) {
        null
    }
    return raw?.takeIf { it.isNotBlank() }?.let {
        runCatching { TrackerJson.decodeFromString(ErrorEnvelope.serializer(), it) }.getOrNull()
    }
}

/** Human-readable text for an HTTP failure. */
internal fun httpErrorMessage(status: Int, envelope: ErrorEnvelope?, reason: String): String {
    val msg = envelope?.error ?: envelope?.detail
    return when {
        status == 401 -> "Not authorised"
        status == 404 -> "Find Me is turned off on the server"
        status == 429 -> "Too many requests, wait a minute"
        !msg.isNullOrBlank() -> msg
        else -> "HTTP $status $reason"
    }
}

/** Pairing error text for the form, per the Find Me contract. */
fun pairErrorMessage(err: DeviceResult.Err): String = when {
    err.code == 403 && err.apiCode == "pair_code_invalid" ->
        "Code expired or already used - make a new one in sys-mon"
    err.code == 429 -> "Too many tries, wait a minute"
    err.code == 404 -> "Find Me is turned off on the server"
    else -> err.message
}
