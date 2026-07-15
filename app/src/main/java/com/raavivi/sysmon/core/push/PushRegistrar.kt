package com.raavivi.sysmon.core.push

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.raavivi.sysmon.core.data.SettingsStore
import com.raavivi.sysmon.core.model.PushRegisterBody
import com.raavivi.sysmon.core.net.ApiProvider
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.tasks.await

/**
 * Bridges Firebase Cloud Messaging and the sys-mon backend: fetches this
 * device's FCM token and (un)registers it with the `/api/push` endpoints so the
 * server knows where to send heater alerts.
 *
 * Registration needs an authenticated session (the endpoint takes any logged-in
 * user), so [SessionManager] calls [syncRegistration] after login/bootstrap and
 * [unregister] on logout (while the token is still valid). The toggle in the
 * More screen flips the opt-in and calls the same two methods.
 */
class PushRegistrar(
    @Suppress("unused") private val appContext: Context,
    private val settings: SettingsStore,
    private val api: ApiProvider,
) {
    /** Make sure the shared API client carries the persisted server URL + token.
     *  FCM can start the process cold (no UI yet) so the token isn't wired. */
    suspend fun ensureApiReady() {
        if (!api.token.isNullOrBlank()) return
        val url = settings.serverUrlNow()
        if (url.isNotBlank()) api.setBaseUrl(url)
        val token = settings.tokenNow()
        if (!token.isNullOrBlank()) api.token = token
    }

    /** Current FCM registration token, or null if Firebase can't provide one. */
    private suspend fun currentToken(): String? =
        try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            Log.w(TAG, "could not obtain FCM token: ${e.message}")
            null
        }

    /** Register with the backend iff the user opted in and we're signed in. */
    suspend fun syncRegistration() {
        if (!settings.pushEnabledNow()) return
        if (api.token.isNullOrBlank()) return
        val token = currentToken() ?: return
        registerToken(token)
    }

    /** Enable pushes: remember the choice and register right away. */
    suspend fun enable(): Boolean {
        settings.setPushEnabled(true)
        if (api.token.isNullOrBlank()) return false
        val token = currentToken() ?: return false
        return registerToken(token)
    }

    /** Disable pushes: unregister this device and remember the choice. */
    suspend fun disable() {
        settings.setPushEnabled(false)
        unregister()
    }

    /** onNewToken: persist + re-register if the user is opted in and signed in. */
    suspend fun refreshToken(token: String) {
        settings.setFcmToken(token)
        if (settings.pushEnabledNow() && !api.token.isNullOrBlank()) {
            registerToken(token)
        }
    }

    /** Drop this device from the server (called on logout, before the token is
     *  cleared, and when the user turns pushes off). Best-effort. */
    suspend fun unregister() {
        val token = settings.fcmTokenNow() ?: currentToken() ?: return
        if (!api.token.isNullOrBlank()) {
            safeCall { api.api.pushUnregister(PushRegisterBody(token)) }
        }
        settings.clearFcmToken()
    }

    private suspend fun registerToken(token: String): Boolean {
        settings.setFcmToken(token)
        return when (val r = safeCall { api.api.pushRegister(PushRegisterBody(token)) }) {
            is ApiResult.Ok -> true
            is ApiResult.Err -> {
                Log.w(TAG, "push register failed: ${r.message}")
                false
            }
        }
    }

    private companion object {
        const val TAG = "PushRegistrar"
    }
}
