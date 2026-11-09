package com.raavivi.sysmon.core.tracker.net

import android.os.Build
import com.raavivi.sysmon.core.tracker.data.TrackerSettings
import com.raavivi.sysmon.core.tracker.model.AckBody
import com.raavivi.sysmon.core.tracker.model.CommandsResponse
import com.raavivi.sysmon.core.tracker.model.OkResponse
import com.raavivi.sysmon.core.tracker.model.PairBody
import com.raavivi.sysmon.core.tracker.model.PairResponse
import com.raavivi.sysmon.core.tracker.model.PushBody
import com.raavivi.sysmon.core.tracker.model.ReportBody
import com.raavivi.sysmon.core.tracker.model.ReportResponse

/**
 * The device-side calls to sys-mon. Authenticated calls read the stored server and
 * token; a 401 `device_unknown` answer calls [onRemoved] so the app can drop the pairing.
 */
class DeviceClient(
    private val settings: TrackerSettings,
    private val apis: DeviceApiProvider,
    private val appVersion: String,
    private val onRemoved: suspend () -> Unit,
) {
    suspend fun pair(server: String, code: String, name: String): DeviceResult<PairResponse> =
        deviceCall {
            apis.forServer(server).pair(
                PairBody(
                    code = code,
                    name = name,
                    model = Build.MODEL,
                    platform = "android",
                    appVersion = appVersion,
                ),
            )
        }

    suspend fun report(body: ReportBody): DeviceResult<ReportResponse> =
        authed { api, token -> api.report(token, body) }

    suspend fun commands(): DeviceResult<CommandsResponse> =
        authed { api, token -> api.commands(token) }

    suspend fun ack(id: String, state: String, error: String? = null): DeviceResult<OkResponse> =
        authed { api, token -> api.ack(token, id, AckBody(state, error)) }

    suspend fun push(fcmToken: String): DeviceResult<OkResponse> =
        authed { api, token -> api.push(token, PushBody(fcmToken)) }

    /** Asks the server to forget this phone. The caller clears local state either way. */
    suspend fun unpair(): DeviceResult<OkResponse> {
        val s = settings.stateNow()
        val token = s.token ?: return DeviceResult.Err("Not paired")
        return deviceCall { apis.forServer(s.server).unpair(token) }
    }

    private suspend fun <T> authed(block: suspend (DeviceApi, String) -> T): DeviceResult<T> {
        val s = settings.stateNow()
        val token = s.token ?: return DeviceResult.Err("Not paired")
        val result = deviceCall { block(apis.forServer(s.server), token) }
        if (result is DeviceResult.Err && result.isDeviceUnknown) onRemoved()
        return result
    }
}
