package com.raavivi.sysmon.ui.more

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.PowerActionResponse
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.launch

class MoreViewModel(private val container: AppContainer) : ViewModel() {

    var serverUrl by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)

    init {
        serverUrl = container.api.httpBaseUrl()
        username = container.session.currentUser ?: ""
        viewModelScope.launch {
            username = container.settings.usernameNow() ?: username
        }
    }

    fun restart() = powerAction("Restart") { container.api.api.restart() }
    fun shutdown() = powerAction("Shutdown") { container.api.api.shutdown() }
    fun remoteControl() = run("Launched remote control on host") { container.api.api.remoteControl() }
    fun backup() = run("History backup written on host") { container.api.api.historyBackup() }

    private fun powerAction(
        label: String,
        block: suspend () -> PowerActionResponse,
    ) {
        viewModelScope.launch {
            message = when (val r = safeCall { block() }) {
                is ApiResult.Ok -> "$label in ${r.value.delaySeconds.toInt()}s on host"
                is ApiResult.Err -> "Failed: ${r.message}"
            }
        }
    }

    private fun run(okMsg: String, block: suspend () -> Any) {
        viewModelScope.launch {
            message = when (val r = safeCall { block() }) {
                is ApiResult.Ok -> okMsg
                is ApiResult.Err -> "Failed: ${r.message}"
            }
        }
    }

    fun logout() {
        viewModelScope.launch { container.session.logout() }
    }

    fun logoutAll() {
        viewModelScope.launch { container.session.logoutAll() }
    }
}
