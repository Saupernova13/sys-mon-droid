package com.raavivi.sysmon.core.tracker.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.raavivi.sysmon.core.tracker.data.TrackerSettings
import com.raavivi.sysmon.core.tracker.net.DeviceResult
import com.raavivi.sysmon.core.tracker.net.DeviceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Registers the FCM token with the server. Does nothing when the build has no Firebase config. */
class DevicePushRegistrar(
    private val context: Context,
    private val settings: TrackerSettings,
    private val client: DeviceClient,
) {
    /** True when google-services.json was present at build time (FCM can work). */
    val available: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    /** Fetches the current FCM token and sends it to the server. */
    suspend fun register() {
        if (!available) return
        val token = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        send(token)
    }

    /** Sends a specific [token] (from `onNewToken`). */
    suspend fun send(token: String) {
        if (token.isBlank() || !settings.stateNow().paired) return
        if (client.push(token) is DeviceResult.Ok) settings.setFcmToken(token)
    }
}
