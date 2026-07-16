package com.raavivi.sysmon.core.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.raavivi.sysmon.SysMonApp
import com.raavivi.sysmon.core.model.RelayBody
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles a notification's "Stop" action: switches that plug's relay off through
 * the admin relay API, then clears its card. Runs off the main thread via
 * [goAsync]; the FCM message that raised the notification may have cold-started
 * the process, so it re-wires the API client from persisted state first.
 *
 * Only the stopped plug's card goes — any other plug is still on and still needs
 * watching. The backend's next tick pushes the new set within a few seconds,
 * which is what corrects the combined card (and the group summary's total).
 */
class StopPlugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_PLUG) return
        val plugId = intent.getStringExtra(EXTRA_PLUG_ID)
        if (plugId.isNullOrBlank()) return
        val app = context.applicationContext as? SysMonApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = app.container
                container.pushRegistrar.ensureApiReady()
                when (val r = safeCall { container.api.api.setPlugRelay(plugId, RelayBody(false)) }) {
                    is ApiResult.Ok -> PlugAlertNotifier.cancelPlug(context, plugId)
                    is ApiResult.Err -> Log.w(TAG, "stop plug failed: ${r.message}")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP_PLUG = "com.raavivi.sysmon.action.STOP_PLUG"
        const val EXTRA_PLUG_ID = "plug_id"
        private const val TAG = "StopPlugReceiver"
    }
}
