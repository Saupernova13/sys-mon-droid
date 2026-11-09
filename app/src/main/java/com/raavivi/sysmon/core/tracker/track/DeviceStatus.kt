package com.raavivi.sysmon.core.tracker.track

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/** Battery and network state attached to every fix and report. */
data class DeviceStatus(val batteryPct: Int?, val charging: Boolean?, val network: String?)

/** `wifi`, `ethernet`, `cellular`, `none`, or null for a connection of unknown type. */
fun networkType(connected: Boolean, wifi: Boolean, cellular: Boolean, ethernet: Boolean): String? = when {
    !connected -> "none"
    wifi -> "wifi"
    ethernet -> "ethernet"
    cellular -> "cellular"
    else -> null
}

/** Battery percent from the sticky battery extras, or null when unavailable. */
fun batteryPercent(level: Int, scale: Int): Int? =
    if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)

/** Reads battery (sticky `ACTION_BATTERY_CHANGED`) and network state. */
class DeviceReader(private val context: Context) {

    fun read(): DeviceStatus {
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = battery?.let {
            batteryPercent(
                it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                it.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            )
        }
        val charging = battery?.let {
            when (it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
                -1 -> null
                else -> false
            }
        }
        return DeviceStatus(pct, charging, readNetwork())
    }

    private fun readNetwork(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return networkType(
            connected = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
        )
    }
}
