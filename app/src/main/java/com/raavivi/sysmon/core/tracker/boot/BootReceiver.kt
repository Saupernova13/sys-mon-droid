package com.raavivi.sysmon.core.tracker.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.raavivi.sysmon.SysMonApp
import com.raavivi.sysmon.core.tracker.track.TrackerService
import kotlinx.coroutines.launch

/**
 * Restarts tracking after a reboot or an app update, when the phone is paired and
 * tracking was on. A reboot also takes one `boot` fix.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as SysMonApp).container.tracker
        val pending = goAsync()
        container.appScope.launch {
            try {
                val state = container.settings.stateNow()
                if (state.paired && state.tracking) {
                    TrackerService.start(
                        context.applicationContext,
                        if (action == Intent.ACTION_BOOT_COMPLETED) TrackerService.ACTION_BOOT else TrackerService.ACTION_START,
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
