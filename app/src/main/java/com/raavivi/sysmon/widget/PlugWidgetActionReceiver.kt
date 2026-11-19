package com.raavivi.sysmon.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.raavivi.sysmon.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles taps on either widget: toggle a relay, or pull a fresh reading.
 *
 * The launcher can deliver these to a cold process, so the work runs off the
 * main thread via [goAsync] and the repository re-wires the API client from
 * persisted state before every call.
 *
 * Both widgets are redrawn after any action, since one plug can be showing in a
 * per-plug tile and in the list at the same time.
 *
 * The async work carries a hard budget: plug alerts push a REFRESH broadcast
 * roughly every 30s, and a foreground broadcast ANR-kills the whole process if
 * the pending result hasn't finished within ~10s — so anything slower than
 * [WORK_BUDGET_MS] is dropped in favour of the cached snapshot, which the next
 * push or tap refreshes anyway.
 */
class PlugWidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            PlugWidgets.ACTION_TOGGLE -> {
                val plugId = intent.getStringExtra(PlugWidgets.EXTRA_PLUG_ID)
                if (plugId.isNullOrBlank()) return
                val desiredOn = intent.getBooleanExtra(PlugWidgets.EXTRA_DESIRED_ON, true)
                // Acknowledge the tap before going near the network: a switch is
                // two round trips on a process that may have just been started,
                // and until this the tile looked identical the whole time.
                PlugWidgetRepository.markPending(appContext, plugId, desiredOn)
                PlugWidgets.updateAll(appContext)
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
                if (withTimeoutOrNull(WORK_BUDGET_MS) { block() } == null) {
                    AppLog.w("Widget", "action overran its ${WORK_BUDGET_MS}ms budget; keeping the cached snapshot")
                }
            } finally {
                PlugWidgets.updateAll(context)
                pending.finish()
            }
        }
    }

    internal companion object {
        /** Foreground broadcasts get ~10s before the system ANR-kills the
         *  process; finish comfortably inside that. */
        const val WORK_BUDGET_MS = 8_000L
    }
}
