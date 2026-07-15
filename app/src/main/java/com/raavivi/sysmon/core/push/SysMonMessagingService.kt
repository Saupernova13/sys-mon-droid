package com.raavivi.sysmon.core.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.raavivi.sysmon.SysMonApp
import com.raavivi.sysmon.core.auth.SessionManager
import kotlinx.coroutines.runBlocking

/**
 * Receives FCM messages from the sys-mon backend. Plug alerts arrive as
 * data-only messages (`type=plug_alert`, `event=on|update|off`) which we render
 * via [PlugAlertNotifier]. `onNewToken` re-registers a rotated token with the
 * server.
 *
 * Both callbacks run on a background thread inside a short FCM wakelock window;
 * the work here (a DataStore read + at most one HTTP call) finishes well within
 * it, so [runBlocking] keeps it from being cut off by process death.
 */
class SysMonMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "plug_alert") return
        val app = applicationContext as? SysMonApp ?: return
        val isAdmin = runBlocking {
            (app.container.settings.roleNow() ?: SessionManager.ROLE_ADMIN) != SessionManager.ROLE_VIEWER
        }
        PlugAlertNotifier.handle(this, data, isAdmin)
    }

    override fun onNewToken(token: String) {
        val app = applicationContext as? SysMonApp ?: return
        runBlocking {
            app.container.pushRegistrar.ensureApiReady()
            app.container.pushRegistrar.refreshToken(token)
        }
    }
}
