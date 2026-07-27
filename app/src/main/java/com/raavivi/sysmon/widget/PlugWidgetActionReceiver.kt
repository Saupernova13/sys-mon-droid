package com.raavivi.sysmon.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles taps on either widget: toggle a relay, or pull a fresh reading.
 *
 * The launcher can deliver these to a cold process, so the work runs off the
 * main thread via [goAsync] and the repository re-wires the API client from
 * persisted state before every call.
 *
 * Both widgets are redrawn after any action, since one plug can be showing in a
 * per-plug tile and in the list at the same time.
 */
class PlugWidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            PlugWidgets.ACTION_TOGGLE -> {
                val plugId = intent.getStringExtra(PlugWidgets.EXTRA_PLUG_ID)
                if (plugId.isNullOrBlank()) return
                val desiredOn = intent.getBooleanExtra(PlugWidgets.EXTRA_DESIRED_ON, true)
                run(appContext) { PlugWidgetRepository.setRelay(appContext, plugId, desiredOn) }
            }
            PlugWidgets.ACTION_REFRESH -> {
                run(appContext) { PlugWidgetRepository.refresh(appContext) }
            }
        }
    }

    private fun run(context: Context, block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                block()
            } finally {
                PlugWidgets.updateAll(context)
                pending.finish()
            }
        }
    }
}
