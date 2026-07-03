package com.raavivi.sysmon.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.SystemSnapshot
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.jsonWebSocketFlow
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

enum class ConnState { Connecting, Live, Paused, Error }

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    var snapshot by mutableStateOf<SystemSnapshot?>(null)
        private set
    var connection by mutableStateOf(ConnState.Connecting)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var paused by mutableStateOf(false)
        private set

    var backupMessage by mutableStateOf<String?>(null)

    // Rolling utilisation buffers for the charts (oldest -> newest).
    val cpuHistory = mutableStateListOf<Float>()
    val ramHistory = mutableStateListOf<Float>()
    val gpuHistory = mutableStateListOf<Float>()
    val diskHistory = mutableStateListOf<Float>()

    private var streamJob: Job? = null

    init {
        hydrateHistory()
        connect()
    }

    private fun hydrateHistory() {
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.historyRecent(seconds = 600) }) {
                is ApiResult.Ok -> {
                    cpuHistory.clear(); ramHistory.clear(); gpuHistory.clear(); diskHistory.clear()
                    r.value.items.takeLast(MAX_POINTS).forEach { item ->
                        cpuHistory.add((item.cpu ?: 0.0).toFloat())
                        ramHistory.add((item.ram ?: 0.0).toFloat())
                        gpuHistory.add((item.gpu ?: 0.0).toFloat())
                        diskHistory.add((item.disk ?: 0.0).toFloat())
                    }
                }
                is ApiResult.Err -> { /* charts will fill from the live stream */ }
            }
        }
    }

    private fun connect() {
        streamJob?.cancel()
        connection = ConnState.Connecting
        streamJob = viewModelScope.launch {
            container.api.jsonWebSocketFlow("/ws/stream", SystemSnapshot.serializer())
                .retryWhen { cause, _ ->
                    connection = ConnState.Error
                    error = cause.message
                    delay(2000)
                    !paused
                }
                .collect { onSnapshot(it) }
        }
    }

    private fun onSnapshot(s: SystemSnapshot) {
        snapshot = s
        connection = ConnState.Live
        error = null
        append(cpuHistory, s.cpu.overallPct.toFloat())
        append(ramHistory, s.ram.usagePct.toFloat())
        append(gpuHistory, (s.gpu.devices.firstOrNull()?.usagePct ?: 0.0).toFloat())
        append(diskHistory, (s.disk.drives.maxOfOrNull { it.usagePct } ?: 0.0).toFloat())
    }

    private fun append(buf: MutableList<Float>, v: Float) {
        buf.add(v)
        while (buf.size > MAX_POINTS) buf.removeAt(0)
    }

    fun togglePause() {
        paused = !paused
        if (paused) {
            streamJob?.cancel()
            connection = ConnState.Paused
        } else {
            connect()
        }
    }

    fun refreshOnce() {
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.snapshot() }) {
                is ApiResult.Ok -> onSnapshot(r.value)
                is ApiResult.Err -> error = r.message
            }
        }
    }

    fun backupHistory() {
        viewModelScope.launch {
            backupMessage = "Backing up…"
            backupMessage = when (val r = safeCall { container.api.api.historyBackup() }) {
                is ApiResult.Ok -> "History backup written on host"
                is ApiResult.Err -> "Backup failed: ${r.message}"
            }
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_POINTS = 180
    }
}
