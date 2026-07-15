package com.raavivi.sysmon.core.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.raavivi.sysmon.SysMonApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restarts the heater monitor after a reboot when the user has it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? SysMonApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app.container.settings.heaterAlertsNow()) {
                    HeaterAlertService.ensureRunning(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
