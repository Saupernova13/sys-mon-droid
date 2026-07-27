package com.raavivi.sysmon.core.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.raavivi.sysmon.SysMonApp
import com.raavivi.sysmon.core.auth.SessionManager
import kotlinx.coroutines.runBlocking

/**
 * Receives FCM messages from the sys-mon backend. Plug alerts arrive as
 * data-only messages (`type=plug_alert`, `event=on|update|off`) carrying every
 * plug that's currently on, which we render via [PlugAlertNotifier]. `onNewToken`
 * re-registers a rotated token with the server.
 *
 * `heater` is accepted as a legacy alias for `plug_alert`: the server and app
 * update independently, so a newer app must still handle an older server (which
 * sends `type=heater`) until prod is redeployed. [PlugAlert] tolerates the older
 * payloads too — no `plugs` array (it rebuilds one from the single-plug keys),
 * no `label`/`ongoing`/`style` (it falls back to the device name and defaults).
 *
 * Both callbacks run on a background thread inside a short FCM wakelock window;
 * the work here (a DataStore read + at most one HTTP call) finishes well within
 * it, so [runBlocking] keeps it from being cut off by process death.
 */
class SysMonMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "plug_alert" && data["type"] != "heater") return
        val app = applicationContext as? SysMonApp ?: return
        val settings = app.container.settings
        val (isAdmin, muted) = runBlocking {
            val role = settings.roleNow() ?: SessionManager.ROLE_ADMIN
            (role != SessionManager.ROLE_VIEWER) to settings.mutedPlugsNow()
        }
        PlugAlertNotifier.handle(this, data, isAdmin, muted)
    }

    override fun onNewToken(token: String) {
        val app = applicationContext as? SysMonApp ?: return
        runBlocking {
            app.container.pushRegistrar.ensureApiReady()
            app.container.pushRegistrar.refreshToken(token)
        }
    }
}
