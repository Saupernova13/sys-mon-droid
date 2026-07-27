package com.raavivi.sysmon.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

    /** Whether this device wants heater push notifications (opt-in). Reuses the
     *  legacy `heater_alerts` key so an upgrade keeps the user's choice. */
    val pushEnabled: Flow<Boolean> = store.data.map { it[KEY_PUSH_ENABLED] ?: false }

    suspend fun pushEnabledNow(): Boolean = pushEnabled.first()

    suspend fun setPushEnabled(on: Boolean) = store.edit { it[KEY_PUSH_ENABLED] = on }

    /** The FCM token last registered with the server, so we can unregister it. */
    suspend fun fcmTokenNow(): String? = store.data.map { it[KEY_FCM_TOKEN] }.first()

    suspend fun setFcmToken(token: String) = store.edit { it[KEY_FCM_TOKEN] = token }

    suspend fun clearFcmToken() = store.edit { it.remove(KEY_FCM_TOKEN) }

    /**
     * Plug ids this phone will not raise a notification for, even though the
     * server is still watching them.
     *
     * This is the per-device half of the alert controls: the server's per-plug
     * `alert` flag decides what is watched at all (and applies to every
     * registered device), while this list lets one phone stay quiet about a plug
     * without changing anyone else's alerts. Stored as an id set rather than an
     * allow-list so a newly added plug alerts by default, matching the server.
     */
    val mutedPlugs: Flow<Set<String>> = store.data.map { it[KEY_MUTED_PLUGS] ?: emptySet() }

    suspend fun mutedPlugsNow(): Set<String> = mutedPlugs.first()

    suspend fun setPlugMuted(plugId: String, muted: Boolean) = store.edit { prefs ->
        val current = prefs[KEY_MUTED_PLUGS] ?: emptySet()
        prefs[KEY_MUTED_PLUGS] = if (muted) current + plugId else current - plugId
    }

    private companion object {
        val KEY_SERVER = stringPreferencesKey("server_url")
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_ROLE = stringPreferencesKey("role")
        val KEY_TERM_SESSION = stringPreferencesKey("terminal_session")
        val KEY_PUSH_ENABLED = booleanPreferencesKey("heater_alerts")
        val KEY_FCM_TOKEN = stringPreferencesKey("fcm_token")
        val KEY_MUTED_PLUGS = stringSetPreferencesKey("muted_plugs")
    }
}
