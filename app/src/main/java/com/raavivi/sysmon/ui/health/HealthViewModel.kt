package com.raavivi.sysmon.ui.health

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.WatchdogEvent
import com.raavivi.sysmon.core.model.WatchdogPauseBody
import com.raavivi.sysmon.core.model.WatchdogStatus
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.launch

/** Loads the watchdog status and recent events; the screen re-calls [refresh] every 30 s while visible. */
class HealthViewModel(private val container: AppContainer) : ViewModel() {

    var status by mutableStateOf<WatchdogStatus?>(null)
        private set
    var events by mutableStateOf<List<WatchdogEvent>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    /** Status error, e.g. the 502 message when the watchdog is not reachable. */
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var busyId by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            load()
            loading = false
        }
    }

    private suspend fun load() {
        when (val r = safeCall { container.api.api.watchdogStatus() }) {
            is ApiResult.Ok -> { status = r.value; error = null }
            is ApiResult.Err -> error = r.message
        }
        // Events come from sys-mon's own ring, so they load even when the watchdog is down.
        when (val r = safeCall { container.api.api.watchdogEvents(EVENT_LIMIT) }) {
            is ApiResult.Ok -> events = r.value.events
            is ApiResult.Err -> if (error == null) error = r.message
        }
    }

    /** Admin: pause [id] for [minutes], then refresh. */
    fun pause(id: String, name: String, minutes: Int) = control(id) {
        safeCall { container.api.api.watchdogPause(id, WatchdogPauseBody(minutes)) }.also {
            message = if (it is ApiResult.Ok) "$name paused for $minutes min" else (it as ApiResult.Err).message
        }
    }

    /** Admin: resume [id], then refresh. */
    fun resume(id: String, name: String) = control(id) {
        safeCall { container.api.api.watchdogResume(id) }.also {
            message = if (it is ApiResult.Ok) "$name resumed" else (it as ApiResult.Err).message
        }
    }

    private fun control(id: String, call: suspend () -> Any) {
        if (busyId != null) return
        busyId = id
        viewModelScope.launch {
            call()
            load()
            busyId = null
        }
    }

    private companion object {
        const val EVENT_LIMIT = 20
    }
}
