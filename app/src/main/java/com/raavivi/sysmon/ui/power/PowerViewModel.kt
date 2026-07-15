package com.raavivi.sysmon.ui.power

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.PowerHistoryResponse
import com.raavivi.sysmon.core.model.PowerReading
import com.raavivi.sysmon.core.model.RelayBody
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class PowerRange(val label: String, val seconds: Long) {
    Hour("1h", 3_600),
    Day("24h", 86_400),
    Week("7d", 604_800),
}

class PowerViewModel(private val container: AppContainer) : ViewModel() {

    /** The `/api/power-usage` envelope: aggregate + one reading per plug. */
    var envelope by mutableStateOf<PowerReading?>(null)
        private set
    var history by mutableStateOf<PowerHistoryResponse?>(null)
        private set
    var range by mutableStateOf(PowerRange.Day)
        private set

    /** [ALL] for the aggregate view, or a plug id for its detail. */
    var selected by mutableStateOf(ALL)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Plug id -> desired state, held while a switch is in flight so the UI
     *  doesn't snap back before the next poll confirms it. */
    var relayPending by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set
    var relayError by mutableStateOf<String?>(null)

    init {
        refresh()
        // Keep readings live while the screen is open (matches the web card).
        viewModelScope.launch {
            while (isActive) {
                delay(POLL_MS)
                poll()
            }
        }
    }

    fun refresh() {
        loading = true
        error = null
        viewModelScope.launch {
            poll(surfaceError = true)
            loadHistory()
            loading = false
        }
    }

    fun select(idOrAll: String) {
        if (idOrAll == selected) return
        selected = idOrAll
        viewModelScope.launch { loadHistory() }
    }

    fun selectRange(newRange: PowerRange) {
        if (newRange == range) return
        range = newRange
        viewModelScope.launch { loadHistory() }
    }

    /** The reading the detail sections render: a device, or the aggregate. */
    fun detail(): PowerReading? {
        val env = envelope ?: return null
        if (selected == ALL) return env.headline
        return env.deviceById(selected) ?: env.headline
    }

    fun clearRelayError() {
        relayError = null
    }

    /**
     * Send an explicit desired state (never a blind flip): the backend reads the
     * plug's real state and only switches when it differs. Optimistic — the
     * switch holds the desired state until a poll confirms it, and reverts on
     * failure.
     */
    fun toggleRelay(id: String, on: Boolean) {
        relayPending = relayPending + (id to on)
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.setPlugRelay(id, RelayBody(on)) }) {
                is ApiResult.Ok -> {
                    relayPending = relayPending + (id to r.value.relayOn)
                    // Safety: if the reading never catches up (plug dropped
                    // offline mid-switch), stop holding the switch.
                    launch {
                        delay(8_000)
                        relayPending = relayPending - id
                    }
                }
                is ApiResult.Err -> {
                    relayPending = relayPending - id
                    relayError = r.message
                }
            }
        }
    }

    private suspend fun poll(surfaceError: Boolean = false) {
        when (val r = safeCall { container.api.api.powerUsage() }) {
            is ApiResult.Ok -> {
                envelope = r.value
                // Release pending holds once the plug's real state caught up.
                if (relayPending.isNotEmpty()) {
                    relayPending = relayPending.filterNot { (id, want) ->
                        r.value.deviceById(id)?.relayOn == want
                    }
                }
            }
            is ApiResult.Err -> if (surfaceError) error = r.message
        }
    }

    private suspend fun loadHistory() {
        val now = System.currentTimeMillis() / 1000.0
        val r = safeCall {
            container.api.api.powerHistory(
                start = now - range.seconds,
                end = now,
                plug = selected.takeIf { it != ALL },
            )
        }
        (r as? ApiResult.Ok)?.let { history = it.value }
    }

    companion object {
        const val ALL = "all"
        private const val POLL_MS = 5_000L
    }
}
