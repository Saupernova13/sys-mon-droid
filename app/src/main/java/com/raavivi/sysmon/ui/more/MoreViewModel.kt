package com.raavivi.sysmon.ui.more

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.PowerActionResponse
import com.raavivi.sysmon.core.model.VersionInfo
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.launch

enum class PowerKind { RESTART, SHUTDOWN }

/** A power action the server held back because of live Jellyfin streams. */
data class StreamingGate(val kind: PowerKind, val message: String)

private const val JELLYFIN_STREAMING = "jellyfin_streaming"

class MoreViewModel(private val container: AppContainer) : ViewModel() {

    var serverUrl by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var serverVersion by mutableStateOf<VersionInfo?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    init {
        serverUrl = container.api.httpBaseUrl()
        username = container.session.currentUser ?: ""
        viewModelScope.launch {
            username = container.settings.usernameNow() ?: username
        }
        viewModelScope.launch {
            (safeCall { container.api.api.version() } as? ApiResult.Ok)?.let {
                serverVersion = it.value
            }
        }
    }

    /**
     * Set when the server refused a shutdown/restart because someone is
     * streaming on Jellyfin (HTTP 409, `code=jellyfin_streaming`). The screen
     * shows a second confirm and, if accepted, retries with `force=true`.
     */
    var streamingGate by mutableStateOf<StreamingGate?>(null)

    fun restart(force: Boolean? = null) =
        powerAction("Restart", PowerKind.RESTART) { container.api.api.restart(force) }

    fun shutdown(force: Boolean? = null) =
        powerAction("Shutdown", PowerKind.SHUTDOWN) { container.api.api.shutdown(force) }

    fun remoteControl() = run("Launched remote control on host") { container.api.api.remoteControl() }
    fun backup() = run("History backup written on host") { container.api.api.historyBackup() }

    private fun powerAction(
        label: String,
        kind: PowerKind,
        block: suspend () -> PowerActionResponse,
    ) {
        viewModelScope.launch {
            message = when (val r = safeCall { block() }) {
                is ApiResult.Ok -> "$label in ${r.value.delaySeconds.toInt()}s on host"
                is ApiResult.Err -> {
                    if (r.code == 409 && r.apiCode == JELLYFIN_STREAMING) {
                        streamingGate = StreamingGate(kind, r.message)
                        null
                    } else {
                        "Failed: ${r.message}"
                    }
                }
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
