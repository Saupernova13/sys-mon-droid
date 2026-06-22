package com.raavivi.sysmon.ui.processes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.KillBody
import com.raavivi.sysmon.core.model.KillPrepareBody
import com.raavivi.sysmon.core.model.SystemSnapshot
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.jsonWebSocketFlow
import com.raavivi.sysmon.core.net.safeCall
import com.raavivi.sysmon.ui.common.formatBytes
import com.raavivi.sysmon.ui.common.formatPct1
import com.raavivi.sysmon.ui.common.formatRate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

enum class ProcCategory(val label: String) { Cpu("CPU"), Ram("RAM"), Disk("Disk"), Gpu("GPU") }

data class ProcRow(val pid: Int, val name: String, val metric: String, val sub: String)

data class PendingKill(val pid: Int, val name: String, val token: String, val ttlS: Int)

class ProcessesViewModel(private val container: AppContainer) : ViewModel() {

    var snapshot by mutableStateOf<SystemSnapshot?>(null)
        private set
    var category by mutableStateOf(ProcCategory.Cpu)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pendingKill by mutableStateOf<PendingKill?>(null)
        private set
    var toast by mutableStateOf<String?>(null)

    private var streamJob: Job? = null

    init { connect() }

    private fun connect() {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            container.api.jsonWebSocketFlow("/ws/stream", SystemSnapshot.serializer())
                .retryWhen { cause, _ -> error = cause.message; delay(2000); true }
                .collect { snapshot = it; error = null }
        }
    }

    fun select(c: ProcCategory) { category = c }

    fun rows(): List<ProcRow> {
        val s = snapshot ?: return emptyList()
        return when (category) {
            ProcCategory.Cpu -> s.cpu.topProcesses.map {
                ProcRow(it.pid, it.name, formatPct1(it.cpuPct), "RAM ${formatBytes(it.ramBytes)}")
            }
            ProcCategory.Ram -> s.ram.topProcesses.map {
                ProcRow(it.pid, it.name, formatBytes(it.ramBytes), "${formatPct1(it.ramPct)} of RAM")
            }
            ProcCategory.Disk -> s.disk.topProcesses.map {
                ProcRow(
                    it.pid, it.name,
                    formatRate(it.diskReadBytesSec + it.diskWriteBytesSec),
                    "R ${formatRate(it.diskReadBytesSec)} · W ${formatRate(it.diskWriteBytesSec)}",
                )
            }
            ProcCategory.Gpu -> s.gpu.devices.flatMap { it.topProcesses }.map {
                ProcRow(it.pid, it.name, "${it.gpuMemMb.toInt()} MB", "CPU ${formatPct1(it.cpuPct)}")
            }
        }
    }

    fun requestKill(pid: Int, name: String) {
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.killPrepare(KillPrepareBody(pid)) }) {
                is ApiResult.Ok -> pendingKill = PendingKill(pid, name, r.value.token, r.value.ttlS)
                is ApiResult.Err -> toast = "Could not prepare kill: ${r.message}"
            }
        }
    }

    fun confirmKill() {
        val p = pendingKill ?: return
        pendingKill = null
        viewModelScope.launch {
            toast = when (val r = safeCall { container.api.api.kill(KillBody(p.pid, p.token)) }) {
                is ApiResult.Ok -> "Killed ${p.name} (pid ${p.pid})"
                is ApiResult.Err -> "Kill failed: ${r.message}"
            }
        }
    }

    fun cancelKill() { pendingKill = null }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
