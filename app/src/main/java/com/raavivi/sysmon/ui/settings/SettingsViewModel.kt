package com.raavivi.sysmon.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.log.AppLog
import com.raavivi.sysmon.core.model.FeaturePatch
import com.raavivi.sysmon.core.model.FeaturesResponse
import com.raavivi.sysmon.core.model.JellyfinConfig
import com.raavivi.sysmon.core.model.JellyfinConfigPatch
import com.raavivi.sysmon.core.model.JellyfinNotifyPatch
import com.raavivi.sysmon.core.model.JellyfinTestBody
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One toggleable server feature flag, as shown in the settings list. */
data class FlagRow(
    val key: String,
    val label: String,
    val enabled: Boolean,
    /** Availability hint ("wacli not found", "needs SYS_MON_POWER_URL", …). */
    val hint: String?,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    var features by mutableStateOf<FeaturesResponse?>(container.session.features.value)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    /** Jellyfin form. [jfAvailable] goes false on a 404 (older server) and hides the section. */
    var jfAvailable by mutableStateOf(true)
        private set

    /** Save is blocked until the saved config has loaded once, or it would overwrite it with defaults. */
    var jfLoaded by mutableStateOf(false)
        private set
    var jfBusy by mutableStateOf(false)
        private set
    var jfStatus by mutableStateOf<String?>(null)
        private set
    var jfApiKeySet by mutableStateOf(false)
        private set

    var jfEnabled by mutableStateOf(false)
    var jfUrl by mutableStateOf("")
    var jfKey by mutableStateOf("")
    var jfPoll by mutableStateOf("10")
    var jfWeb by mutableStateOf(true)
    var jfPush by mutableStateOf(true)
    var jfUser by mutableStateOf(true)
    var jfTitle by mutableStateOf(true)
    var jfPosition by mutableStateOf(true)
    var jfDevice by mutableStateOf(true)
    var jfGate by mutableStateOf(false)
    var jfUpdate by mutableStateOf("60")

    /** The config the form was last filled from; Save sends only what differs from it. */
    private var jfBase: JellyfinConfig? = null

    /** Logs section: switch state mirrors [SettingsStore.loggingEnabled]. */
    var loggingEnabled by mutableStateOf(AppLog.isEnabled())
        private set
    var logsBusy by mutableStateOf(false)
        private set
    val logsLocation: String get() = AppLog.locationLabel()

    init {
        refresh()
        loadJellyfin()
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

    private fun applyJellyfin(c: JellyfinConfig) {
        jfBase = c
        jfApiKeySet = c.apiKeySet
        jfEnabled = c.enabled
        jfUrl = c.url
        jfKey = ""
        jfPoll = c.pollIntervalS.toString()
        jfWeb = c.notify.web
        jfPush = c.notify.push
        jfUser = c.notify.includeUser
        jfTitle = c.notify.includeTitle
        jfPosition = c.notify.includePosition
        jfDevice = c.notify.includeDevice
        jfGate = c.notify.gatePower
        jfUpdate = c.notify.updateIntervalS.toString()
    }

    fun loadJellyfin() {
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.jellyfinConfig() }) {
                is ApiResult.Ok -> {
                    applyJellyfin(r.value)
                    jfLoaded = true
                    jfAvailable = true
                }
                is ApiResult.Err -> if (r.code == 404) jfAvailable = false else jfStatus = "Failed: ${r.message}"
            }
        }
    }

    /** Validate and send only the changed fields. */
    fun saveJellyfin() {
        val base = jfBase
        if (!jfLoaded || base == null) {
            jfStatus = "Jellyfin settings did not load, so saving would overwrite them. Refresh and try again."
            return
        }
        val poll = jfPoll.trim().toIntOrNull()
        if (poll == null || poll !in JF_POLL_RANGE) {
            jfStatus = "Poll interval must be 5-3600 seconds"
            return
        }
        val update = jfUpdate.trim().toIntOrNull()
        if (update == null || !(update == 0 || update in JF_UPDATE_RANGE)) {
            jfStatus = "Push refresh must be 0 or 15-3600 seconds"
            return
        }
        val url = jfUrl.trim()
        val n = base.notify
        val notify = JellyfinNotifyPatch(
            web = jfWeb.takeIf { it != n.web },
            push = jfPush.takeIf { it != n.push },
            includeUser = jfUser.takeIf { it != n.includeUser },
            includeTitle = jfTitle.takeIf { it != n.includeTitle },
            includePosition = jfPosition.takeIf { it != n.includePosition },
            includeDevice = jfDevice.takeIf { it != n.includeDevice },
            updateIntervalS = update.takeIf { it != n.updateIntervalS },
            gatePower = jfGate.takeIf { it != n.gatePower },
        )
        val patch = JellyfinConfigPatch(
            url = url.takeIf { it != base.url },
            apiKey = jfKey.takeIf { it.isNotEmpty() },
            enabled = jfEnabled.takeIf { it != base.enabled },
            pollIntervalS = poll.takeIf { it != base.pollIntervalS },
            notify = notify.takeUnless { it.isEmpty },
        )
        jfBusy = true
        jfStatus = "Saving..."
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.jellyfinSaveConfig(patch) }) {
                is ApiResult.Ok -> {
                    applyJellyfin(r.value)
                    jfStatus = "Saved"
                }
                is ApiResult.Err -> jfStatus = "Failed: ${r.message}"
            }
            jfBusy = false
        }
    }

    /** Test with what is typed; blank fields fall back to the saved values server-side. */
    fun testJellyfin() {
        jfBusy = true
        jfStatus = "Testing..."
        val body = JellyfinTestBody(
            url = jfUrl.trim().takeIf { it.isNotEmpty() },
            apiKey = jfKey.takeIf { it.isNotEmpty() },
        )
        viewModelScope.launch {
            jfStatus = when (val r = safeCall { container.api.api.jellyfinTest(body) }) {
                is ApiResult.Ok -> "Connected: ${r.value.serverName.ifBlank { "Jellyfin" }} ${r.value.version}".trim()
                is ApiResult.Err -> "Failed: ${r.message}"
            }
            jfBusy = false
        }
    }

    fun refresh() {
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.features() }) {
                is ApiResult.Ok -> {
                    features = r.value
                    container.session.acceptFeatures(r.value)
                }
                is ApiResult.Err -> error = r.message
            }
        }
    }

    /** The raw toggle state lives in `detail.*.enabled`; the top-level booleans
     *  are effective visibility and would misreport a flag that's on but
     *  missing its prerequisite. */
    fun rows(): List<FlagRow> {
        val d = features?.detail ?: return emptyList()
        return listOf(
            FlagRow(
                "model_log", "Model Log", d.modelLog.enabled,
                hint = null,
            ),
            FlagRow(
                "whatsapp", "WhatsApp", d.whatsapp.enabled,
                hint = if (!d.whatsapp.wacliFound) "wacli not found on host" else null,
            ),
            FlagRow(
                "ollama_proxy", "Ollama audit proxy", d.ollamaProxy.enabled,
                hint = if (d.ollamaProxy.enabled && !d.ollamaProxy.running) "not running" else null,
            ),
            FlagRow(
                "godot", "Godot editor", d.godot.enabled,
                hint = if (!d.godot.pathSet) "editor path not set on host" else null,
            ),
            FlagRow(
                "power", "Power (smart plug)", d.power.enabled,
                hint = if (!d.power.urlSet) "plug URL not set on host" else null,
            ),
            FlagRow(
                "crystaldisk", "S.M.A.R.T. disk health", d.crystaldisk.enabled,
                hint = when {
                    d.crystaldisk.installed -> d.crystaldisk.path
                    d.crystaldisk.source == "smartctl" ->
                        "smartctl not found - install smartmontools (SYS_MON_SMARTCTL)"
                    else -> "CrystalDiskInfo not found at ${d.crystaldisk.path ?: "its configured path"}"
                },
            ),
            FlagRow(
                "findme", "Find Me", d.findme.enabled,
                hint = "Locate and ring your phones (needs the Find Me tracker app)",
            ),
        )
    }

    fun toggle(key: String, value: Boolean) {
        if (busy) return
        busy = true
        error = null
        val patch = when (key) {
            "whatsapp" -> FeaturePatch(whatsapp = value)
            "model_log" -> FeaturePatch(modelLog = value)
            "ollama_proxy" -> FeaturePatch(ollamaProxy = value)
            "godot" -> FeaturePatch(godot = value)
            "power" -> FeaturePatch(power = value)
            "crystaldisk" -> FeaturePatch(crystaldisk = value)
            "findme" -> FeaturePatch(findme = value)
            else -> return
        }
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.updateSettings(patch) }) {
                is ApiResult.Ok -> {
                    features = r.value
                    container.session.acceptFeatures(r.value)
                    message = if (r.value.restartRequired.isNotEmpty()) {
                        "Saved — ${r.value.restartRequired.joinToString()} needs a server restart"
                    } else {
                        "Saved"
                    }
                }
                is ApiResult.Err -> error = r.message
            }
            busy = false
        }
    }

    private companion object {
        val JF_POLL_RANGE = 5..3600
        val JF_UPDATE_RANGE = 15..3600
    }
}
