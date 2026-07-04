package com.raavivi.sysmon.core.auth

import com.raavivi.sysmon.core.data.SettingsStore
import com.raavivi.sysmon.core.model.FeaturesResponse
import com.raavivi.sysmon.core.model.LoginRequest
import com.raavivi.sysmon.core.net.ApiProvider
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AuthState { Loading, LoggedOut, LoggedIn }

/**
 * Single source of truth for authentication. Loads persisted server/token at
 * startup, verifies the token, and exposes [state] for navigation gating. Wires the
 * live token + base URL into [ApiProvider] so every request and socket is authed.
 */
class SessionManager(
    private val settings: SettingsStore,
    private val api: ApiProvider,
) {
    private val _state = MutableStateFlow(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Server-assigned role for the current session: [ROLE_ADMIN] or [ROLE_VIEWER]. */
    private val _role = MutableStateFlow(ROLE_ADMIN)
    val role: StateFlow<String> = _role.asStateFlow()
    val isAdmin: Boolean get() = _role.value == ROLE_ADMIN

    /** Server feature flags; null until the first successful fetch. */
    private val _features = MutableStateFlow<FeaturesResponse?>(null)
    val features: StateFlow<FeaturesResponse?> = _features.asStateFlow()

    @Volatile
    var currentUser: String? = null
        private set

    /** Restore persisted session on app launch and verify the token if present. */
    suspend fun bootstrap() {
        val url = settings.serverUrlNow()
        if (url.isNotBlank()) api.setBaseUrl(url)
        val token = settings.tokenNow()
        currentUser = settings.usernameNow()
        _role.value = settings.roleNow() ?: ROLE_ADMIN
        if (token.isNullOrBlank()) {
            _state.value = AuthState.LoggedOut
            return
        }
        api.token = token
        when (val r = safeCall { api.api.verify() }) {
            is ApiResult.Ok -> {
                currentUser = r.value.user
                setRole(r.value.role)
                refreshFeatures()
                _state.value = AuthState.LoggedIn
            }
            is ApiResult.Err -> {
                api.token = null
                settings.clearToken()
                _state.value = AuthState.LoggedOut
            }
        }
    }

    /** Configure the server, then exchange credentials for a JWT. */
    suspend fun login(serverUrl: String, username: String, password: String): ApiResult<Unit> {
        api.setBaseUrl(serverUrl)
        settings.setServerUrl(ApiProvider.normalizeBaseUrl(serverUrl))
        api.token = null
        return when (val r = safeCall { api.api.login(LoginRequest(username, password)) }) {
            is ApiResult.Ok -> {
                api.token = r.value.token
                settings.setToken(r.value.token)
                settings.setUsername(username)
                currentUser = username
                setRole(r.value.role)
                refreshFeatures()
                _state.value = AuthState.LoggedIn
                ApiResult.Ok(Unit)
            }
            is ApiResult.Err -> r
        }
    }

    private suspend fun setRole(role: String) {
        _role.value = role
        settings.setRole(role)
    }

    /** Fetch the server's feature flags; failures keep the previous value. */
    suspend fun refreshFeatures() {
        (safeCall { api.api.features() } as? ApiResult.Ok)?.let { _features.value = it.value }
    }

    /** Push a payload returned by `POST /api/settings` so gated UI updates live. */
    fun acceptFeatures(features: FeaturesResponse) {
        _features.value = features
    }

    suspend fun logout() {
        safeCall { api.api.logout() }
        finishLogout()
    }

    suspend fun logoutAll() {
        safeCall { api.api.logoutAll() }
        finishLogout()
    }

    private suspend fun finishLogout() {
        api.token = null
        settings.clearToken()
        currentUser = null
        _role.value = ROLE_ADMIN
        _features.value = null
        _state.value = AuthState.LoggedOut
    }

    companion object {
        const val ROLE_ADMIN = "admin"
        const val ROLE_VIEWER = "viewer"
    }
}
