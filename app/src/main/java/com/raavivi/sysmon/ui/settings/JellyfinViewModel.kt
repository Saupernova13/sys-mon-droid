package com.raavivi.sysmon.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.JellyfinConfig
import com.raavivi.sysmon.core.model.JellyfinConfigPatch
import com.raavivi.sysmon.core.model.JellyfinNotifyPatch
import com.raavivi.sysmon.core.model.JellyfinTestBody
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.launch

class JellyfinViewModel(private val container: AppContainer) : ViewModel() {

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

    init {
        loadJellyfin()
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

    private companion object {
        val JF_POLL_RANGE = 5..3600
        val JF_UPDATE_RANGE = 15..3600
    }
}
