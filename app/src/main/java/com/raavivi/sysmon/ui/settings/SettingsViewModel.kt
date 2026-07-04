package com.raavivi.sysmon.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.FeaturePatch
import com.raavivi.sysmon.core.model.FeaturesResponse
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.launch

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

    init {
        refresh()
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
}
