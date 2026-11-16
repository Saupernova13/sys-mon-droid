package com.raavivi.sysmon.ui.logs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LogsViewModel(private val container: AppContainer) : ViewModel() {

    /** Logs section: switch state mirrors SettingsStore.loggingEnabled. */
    var loggingEnabled by mutableStateOf(AppLog.isEnabled())
        private set
    var logsBusy by mutableStateOf(false)
        private set
    val logsLocation: String get() = AppLog.locationLabel()

    var message by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            container.settings.loggingEnabled.collect { loggingEnabled = it }
        }
    }

    fun setLogging(on: Boolean) {
        loggingEnabled = on
        viewModelScope.launch { container.settings.setLoggingEnabled(on) }
    }

    /** Zips the logs into the cache off the main thread, then hands the file to [onReady]. */
    fun shareLogs(onReady: (File) -> Unit) {
        if (logsBusy) return
        logsBusy = true
        viewModelScope.launch {
            val zip = File(container.appContext.cacheDir, "logs-share/sysmon-logs.zip")
            val ok = withContext(Dispatchers.IO) { runCatching { AppLog.exportZip(zip) }.getOrDefault(false) }
            logsBusy = false
            if (ok) onReady(zip) else message = "No logs to share"
        }
    }

    fun clearLogs() {
        if (logsBusy) return
        logsBusy = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AppLog.clear() }
            logsBusy = false
            message = "Logs cleared"
        }
    }
}
