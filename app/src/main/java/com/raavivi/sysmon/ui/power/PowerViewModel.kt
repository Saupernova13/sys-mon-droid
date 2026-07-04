package com.raavivi.sysmon.ui.power

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.PowerHistoryResponse
import com.raavivi.sysmon.core.model.PowerReading
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.launch

enum class PowerRange(val label: String, val seconds: Long) {
    Hour("1h", 3_600),
    Day("24h", 86_400),
    Week("7d", 604_800),
}

class PowerViewModel(private val container: AppContainer) : ViewModel() {

    var reading by mutableStateOf<PowerReading?>(null)
        private set
    var history by mutableStateOf<PowerHistoryResponse?>(null)
        private set
    var range by mutableStateOf(PowerRange.Day)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        loading = true
        error = null
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.powerUsage() }) {
                is ApiResult.Ok -> reading = r.value
                is ApiResult.Err -> error = r.message
            }
            loadHistory()
            loading = false
        }
    }

    fun select(newRange: PowerRange) {
        if (newRange == range) return
        range = newRange
        viewModelScope.launch { loadHistory() }
    }

    private suspend fun loadHistory() {
        val now = System.currentTimeMillis() / 1000.0
        val r = safeCall {
            container.api.api.powerHistory(start = now - range.seconds, end = now)
        }
        (r as? ApiResult.Ok)?.let { history = it.value }
    }
}
