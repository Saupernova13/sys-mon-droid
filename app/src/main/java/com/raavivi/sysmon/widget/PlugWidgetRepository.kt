package com.raavivi.sysmon.widget

import android.content.Context
import android.util.Log
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.SysMonApp
import com.raavivi.sysmon.core.model.PowerReading
import com.raavivi.sysmon.core.model.RelayBody
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.serialization.Serializable

/** One plug as a widget needs it — no charts, no tariff tiers, just state. */
@Serializable
data class WidgetPlug(
    val id: String,
    val name: String,
    val available: Boolean,
    val relayOn: Boolean,
    val watts: Double,
    val costPerHour: Double,
)

/** The last known plug states, with the moment they were read. */
@Serializable
data class PlugSnapshot(
    val plugs: List<WidgetPlug> = emptyList(),
    val totalWatts: Double = 0.0,
    val currency: String = "",
    val takenAtMs: Long = 0,
    /** Set when the last attempt failed, so the widget can say why. */
    val error: String? = null,
    /** No stored session — the widget offers a sign-in tap instead of data. */
    val signedOut: Boolean = false,
) {
    fun plug(id: String): WidgetPlug? = plugs.firstOrNull { it.id == id }
}

/**
 * Everything the home-screen widgets need from the server, and the only place
 * that talks to it on their behalf.
 *
 * Widgets run with the app closed — often in a process FCM or the launcher just
 * started — so nothing here may assume the UI ever ran. Each entry point wires
 * the API client from persisted state first, then renews the JWT if it is close
 * to expiring, which is what lets a widget still work days after the last visit
 * to the app.
 *
 * The last successful read is cached so a widget can paint immediately on boot
 * or resize instead of showing an empty box until the network answers.
 */
object PlugWidgetRepository {

    private const val TAG = "PlugWidgetRepo"
    private const val PREFS = "sysmon_widget_cache"
    private const val KEY_SNAPSHOT = "snapshot"

    /** Last stored snapshot, or an empty one. Synchronous: the widget list
     *  factory needs data the moment `onDataSetChanged` returns. */
    fun cached(context: Context): PlugSnapshot {
        val raw = prefs(context).getString(KEY_SNAPSHOT, null) ?: return PlugSnapshot()
        return runCatching {
            SysMonJson.decodeFromString(PlugSnapshot.serializer(), raw)
        }.getOrDefault(PlugSnapshot())
    }

    /**
     * Fetch live plug state and cache it. On failure the previous plugs are kept
     * and stamped with an error — a widget that has gone briefly offline should
     * show its last reading greyed out rather than forget every plug it knew.
     */
    suspend fun refresh(context: Context): PlugSnapshot {
        val container = container(context) ?: return cached(context)
        if (!ensureSession(container)) {
            return store(context, PlugSnapshot(signedOut = true))
        }
        return when (val r = safeCall { container.api.api.powerUsage() }) {
            is ApiResult.Ok -> store(context, snapshotOf(r.value))
            is ApiResult.Err -> {
                Log.w(TAG, "widget refresh failed: ${r.message}")
                store(context, cached(context).copy(error = r.message, signedOut = false))
            }
        }
    }

    /**
     * Switch a plug and refresh from the response. Returns the new snapshot; the
     * relay state comes from the plug's own reply, so a switch the plug refused
     * is never shown as success.
     */
    suspend fun setRelay(context: Context, plugId: String, on: Boolean): PlugSnapshot {
        val container = container(context) ?: return cached(context)
        if (!ensureSession(container)) {
            return store(context, PlugSnapshot(signedOut = true))
        }
        return when (val r = safeCall { container.api.api.setPlugRelay(plugId, RelayBody(on)) }) {
            is ApiResult.Ok -> {
                // Apply the confirmed state right away, then pull a full reading
                // so the watts stop describing the state we just left.
                val confirmed = cached(context).let { snap ->
                    snap.copy(
                        plugs = snap.plugs.map {
                            if (it.id == plugId) it.copy(relayOn = r.value.relayOn) else it
                        },
                        error = null,
                    )
                }
                store(context, confirmed)
                refresh(context)
            }
            is ApiResult.Err -> {
                val message = if (r.code == 403) "Admin account required" else r.message
                Log.w(TAG, "widget relay switch failed: $message")
                store(context, cached(context).copy(error = message))
            }
        }
    }

    /**
     * Point the shared API client at the stored server and token, then renew the
     * token if it is nearly expired. False means there is no session to use.
     */
    private suspend fun ensureSession(container: AppContainer): Boolean {
        val url = container.settings.serverUrlNow()
        if (url.isNotBlank()) container.api.setBaseUrl(url)
        val token = container.settings.tokenNow()
        if (token.isNullOrBlank()) return false
        container.api.token = token
        container.tokenRenewer.renewIfDue()
        return true
    }

    private fun snapshotOf(reading: PowerReading): PlugSnapshot = PlugSnapshot(
        plugs = reading.devices.map { d ->
            WidgetPlug(
                id = d.id,
                name = d.deviceName.ifBlank { d.id },
                available = d.available,
                relayOn = d.relayOn,
                watts = d.watts,
                costPerHour = d.cost.perHour,
            )
        },
        totalWatts = reading.headline.watts,
        currency = reading.currency,
        takenAtMs = System.currentTimeMillis(),
    )

    private fun store(context: Context, snapshot: PlugSnapshot): PlugSnapshot {
        prefs(context).edit()
            .putString(KEY_SNAPSHOT, SysMonJson.encodeToString(PlugSnapshot.serializer(), snapshot))
            .apply()
        return snapshot
    }

    private fun container(context: Context): AppContainer? =
        (context.applicationContext as? SysMonApp)?.container

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
