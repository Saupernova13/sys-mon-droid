package com.raavivi.sysmon.core.tracker.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "tracker_settings")

/** Everything the app persists about the pairing and the tracking status. */
data class TrackerState(
    val server: String = "",
    val deviceId: String? = null,
    val token: String? = null,
    val deviceName: String = "",
    val serverName: String? = null,
    val intervalS: Int = 300,
    /** Seconds between location samples (uploads still go every [intervalS]). */
    val sampleS: Int = 60,
    val tracking: Boolean = false,
    val lastFixAtMs: Long? = null,
    val lastFixAccuracyM: Float? = null,
    val lastUploadAtMs: Long? = null,
    val lastUploadMessage: String? = null,
    val notice: String? = null,
) {
    val paired: Boolean get() = token != null
}

/** DataStore-backed persistence of the pairing (server, device id, token, interval) and status. */
class TrackerSettings(context: Context) {

    private val store = context.applicationContext.dataStore

    val state: Flow<TrackerState> = store.data.map { p ->
        TrackerState(
            server = p[KEY_SERVER].orEmpty(),
            deviceId = p[KEY_DEVICE_ID],
            token = p[KEY_TOKEN],
            deviceName = p[KEY_DEVICE_NAME].orEmpty(),
            serverName = p[KEY_SERVER_NAME],
            intervalS = p[KEY_INTERVAL] ?: 300,
            sampleS = p[KEY_SAMPLE] ?: 60,
            tracking = p[KEY_TRACKING] ?: false,
            lastFixAtMs = p[KEY_LAST_FIX_AT],
            lastFixAccuracyM = p[KEY_LAST_FIX_ACC],
            lastUploadAtMs = p[KEY_LAST_UPLOAD_AT],
            lastUploadMessage = p[KEY_LAST_UPLOAD_MSG],
            notice = p[KEY_NOTICE],
        )
    }

    suspend fun stateNow(): TrackerState = state.first()

    /** Stores a fresh pairing and turns tracking on. */
    suspend fun savePairing(
        server: String,
        deviceId: String,
        token: String,
        intervalS: Int,
        sampleS: Int = 60,
        deviceName: String,
        serverName: String?,
    ) = store.edit {
        it[KEY_SERVER] = server
        it[KEY_DEVICE_ID] = deviceId
        it[KEY_TOKEN] = token
        it[KEY_INTERVAL] = intervalS
        it[KEY_SAMPLE] = sampleS
        it[KEY_DEVICE_NAME] = deviceName
        if (serverName != null) it[KEY_SERVER_NAME] = serverName else it.remove(KEY_SERVER_NAME)
        it[KEY_TRACKING] = true
        it.remove(KEY_NOTICE)
        it.remove(KEY_LAST_FIX_AT)
        it.remove(KEY_LAST_FIX_ACC)
        it.remove(KEY_LAST_UPLOAD_AT)
        it.remove(KEY_LAST_UPLOAD_MSG)
        it.remove(KEY_HANDLED)
        it.remove(KEY_FCM_TOKEN)
    }

    /** Forgets the pairing and tracking; [notice] is shown on the pairing screen. */
    suspend fun clearPairing(notice: String? = null) = store.edit {
        it.remove(KEY_DEVICE_ID)
        it.remove(KEY_TOKEN)
        it.remove(KEY_SERVER_NAME)
        it.remove(KEY_LAST_FIX_AT)
        it.remove(KEY_LAST_FIX_ACC)
        it.remove(KEY_LAST_UPLOAD_AT)
        it.remove(KEY_LAST_UPLOAD_MSG)
        it.remove(KEY_HANDLED)
        it.remove(KEY_FCM_TOKEN)
        it[KEY_TRACKING] = false
        if (notice != null) it[KEY_NOTICE] = notice else it.remove(KEY_NOTICE)
    }

    suspend fun clearNotice() = store.edit { it.remove(KEY_NOTICE) }

    suspend fun setTracking(on: Boolean) = store.edit { it[KEY_TRACKING] = on }

    suspend fun setInterval(seconds: Int) = store.edit { it[KEY_INTERVAL] = seconds }

    suspend fun setSample(seconds: Int) = store.edit { it[KEY_SAMPLE] = seconds }

    suspend fun setLastFix(atMs: Long, accuracyM: Float?) = store.edit {
        it[KEY_LAST_FIX_AT] = atMs
        if (accuracyM != null) it[KEY_LAST_FIX_ACC] = accuracyM else it.remove(KEY_LAST_FIX_ACC)
    }

    suspend fun setUploadResult(atMs: Long, message: String) = store.edit {
        it[KEY_LAST_UPLOAD_AT] = atMs
        it[KEY_LAST_UPLOAD_MSG] = message
    }

    /** Handled command ids, comma separated (ids are alphanumeric). */
    suspend fun handledIdsNow(): List<String> =
        store.data.first()[KEY_HANDLED]?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    suspend fun setHandledIds(ids: List<String>) = store.edit { it[KEY_HANDLED] = ids.joinToString(",") }

    suspend fun fcmTokenNow(): String? = store.data.first()[KEY_FCM_TOKEN]

    suspend fun setFcmToken(token: String) = store.edit { it[KEY_FCM_TOKEN] = token }

    private companion object {
        val KEY_SERVER = stringPreferencesKey("server")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        val KEY_SERVER_NAME = stringPreferencesKey("server_name")
        val KEY_INTERVAL = intPreferencesKey("interval_s")
        val KEY_SAMPLE = intPreferencesKey("sample_s")
        val KEY_TRACKING = booleanPreferencesKey("tracking")
        val KEY_LAST_FIX_AT = longPreferencesKey("last_fix_at")
        val KEY_LAST_FIX_ACC = floatPreferencesKey("last_fix_accuracy")
        val KEY_LAST_UPLOAD_AT = longPreferencesKey("last_upload_at")
        val KEY_LAST_UPLOAD_MSG = stringPreferencesKey("last_upload_message")
        val KEY_NOTICE = stringPreferencesKey("notice")
        val KEY_HANDLED = stringPreferencesKey("handled_ids")
        val KEY_FCM_TOKEN = stringPreferencesKey("fcm_token")
    }
}
