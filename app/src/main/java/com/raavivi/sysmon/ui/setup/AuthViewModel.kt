package com.raavivi.sysmon.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.net.ApiProvider
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.launch

class AuthViewModel(private val container: AppContainer) : ViewModel() {

    var serverUrl by mutableStateOf("")
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            serverUrl = container.settings.serverUrlNow().ifBlank { ApiProvider.DEFAULT_BASE_URL }
            username = container.settings.usernameNow() ?: ""
        }
    }

    fun onServerUrl(v: String) { serverUrl = v; error = null }
    fun onUsername(v: String) { username = v; error = null }
    fun onPassword(v: String) { password = v; error = null }

    fun login() {
        if (loading) return
        viewModelScope.launch {
            loading = true
            error = null
            when (val r = container.session.login(serverUrl.trim(), username.trim(), password)) {
                is ApiResult.Ok -> { /* SessionManager flips state -> LoggedIn */ }
                is ApiResult.Err -> error = r.message
            }
            loading = false
        }
    }
}
