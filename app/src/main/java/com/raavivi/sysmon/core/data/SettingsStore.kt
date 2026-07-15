package com.raavivi.sysmon.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "sysmon_settings")

/** Persists the server URL, the issued JWT, and the last-used username. */
class SettingsStore(private val context: Context) {

    private val store = context.dataStore

    val serverUrl: Flow<String> = store.data.map { it[KEY_SERVER] ?: "" }
    val token: Flow<String?> = store.data.map { it[KEY_TOKEN] }
    val username: Flow<String?> = store.data.map { it[KEY_USERNAME] }

    suspend fun serverUrlNow(): String = serverUrl.first()
    suspend fun tokenNow(): String? = token.first()
    suspend fun usernameNow(): String? = username.first()
    suspend fun roleNow(): String? = store.data.map { it[KEY_ROLE] }.first()

    suspend fun setServerUrl(url: String) = store.edit { it[KEY_SERVER] = url }

    suspend fun setToken(token: String) = store.edit { it[KEY_TOKEN] = token }

    suspend fun setUsername(name: String) = store.edit { it[KEY_USERNAME] = name }

    suspend fun setRole(role: String) = store.edit { it[KEY_ROLE] = role }

    /** Drops the session credentials (token + role); server URL and username stay. */
    suspend fun clearToken() = store.edit {
        it.remove(KEY_TOKEN)
        it.remove(KEY_ROLE)
    }

    /** Last terminal PTY session id, so the app can re-attach to a live shell. */
    suspend fun terminalSessionNow(): String? = store.data.map { it[KEY_TERM_SESSION] }.first()

    suspend fun setTerminalSession(id: String) = store.edit { it[KEY_TERM_SESSION] = id }

    /** Whether the heater-alert foreground monitor should run. */
    val heaterAlerts: Flow<Boolean> = store.data.map { it[KEY_HEATER_ALERTS] ?: false }

    suspend fun heaterAlertsNow(): Boolean = heaterAlerts.first()

    suspend fun setHeaterAlerts(on: Boolean) = store.edit { it[KEY_HEATER_ALERTS] = on }

    private companion object {
        val KEY_SERVER = stringPreferencesKey("server_url")
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_ROLE = stringPreferencesKey("role")
        val KEY_TERM_SESSION = stringPreferencesKey("terminal_session")
        val KEY_HEATER_ALERTS = booleanPreferencesKey("heater_alerts")
    }
}
