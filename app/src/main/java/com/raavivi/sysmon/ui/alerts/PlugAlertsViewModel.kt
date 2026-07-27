package com.raavivi.sysmon.ui.alerts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.PowerDevicePatch
import com.raavivi.sysmon.core.model.PowerDeviceRow
import com.raavivi.sysmon.core.model.PowerDevicesBody
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.launch

/**
 * Backs the two layers of plug-alert control.
 *
 * **Watched** ([devices] `.alert`) is server state: it decides which plugs the
 * backend watches at all, applies to every registered device, and needs admin.
 * Turning it off stops the push being sent in the first place.
 *
 * **Muted** ([muted]) is local to this phone: the server keeps watching and
 * keeps sending, but [com.raavivi.sysmon.core.push.PlugAlertNotifier] drops the
 * plug before anything is shown. Useful for silencing a plug here without
 * changing what any other device sees.
 */
class PlugAlertsViewModel(private val container: AppContainer) : ViewModel() {

    var devices by mutableStateOf<List<PowerDeviceRow>>(emptyList())
        private set
    var muted by mutableStateOf<Set<String>>(emptySet())
        private set
    var loading by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    init {
        refresh()
    }

    fun refresh() {
        loading = true
        error = null
        viewModelScope.launch {
            muted = container.settings.mutedPlugsNow()
            when (val r = safeCall { container.api.api.powerDevices() }) {
                is ApiResult.Ok -> devices = r.value.devices
                is ApiResult.Err -> error = r.message
            }
            loading = false
        }
    }

    /** Local-only: silence a plug on this phone without touching the server. */
    fun setMuted(plugId: String, isMuted: Boolean) {
        muted = if (isMuted) muted + plugId else muted - plugId
        viewModelScope.launch { container.settings.setPlugMuted(plugId, isMuted) }
    }

    /**
     * Server-side: flip one plug's `alert` flag. The endpoint replaces the whole
     * device list, so every plug is sent back — with `password` omitted (we only
     * ever hold the masked flag) and `protected` omitted so an untouched guard
     * survives the round trip.
     */
    fun setWatched(plugId: String, watched: Boolean) {
        if (busy) return
        val previous = devices
        // Optimistic: the switch shouldn't lag a network round trip.
        devices = devices.map { if (it.id == plugId) it.copy(alert = watched) else it }
        busy = true
        error = null
        val payload = devices.map { d ->
            PowerDevicePatch(
                id = d.id,
                name = d.name,
                url = d.url,
                user = d.user,
                enabled = d.enabled,
                alert = d.alert,
                alertLabel = d.alertLabel,
            )
        }
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.setPowerDevices(PowerDevicesBody(payload)) }) {
                is ApiResult.Ok -> {
                    devices = r.value.devices
                    message = if (watched) "Alerts on for this plug" else "Alerts off for this plug"
                }
                is ApiResult.Err -> {
                    devices = previous
                    error = when (r.code) {
                        403 -> "Changing which plugs alert requires an admin account."
                        else -> r.message
                    }
                }
            }
            busy = false
        }
    }

    /** Whether this phone will actually raise a notification for [device]. */
    fun willNotify(device: PowerDeviceRow): Boolean =
        device.enabled && device.alert && device.id !in muted
}
