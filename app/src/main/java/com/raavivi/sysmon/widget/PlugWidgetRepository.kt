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

/** A switch that has been asked for but not yet confirmed by the server. */
@Serializable
data class PendingSwitch(val desiredOn: Boolean, val sinceMs: Long)

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
    /** In-flight switches by plug id — what the widget shows until the server
     *  catches up. See [markPending]. */
    val pending: Map<String, PendingSwitch> = emptyMap(),
) {
    fun plug(id: String): WidgetPlug? = plugs.firstOrNull { it.id == id }

    /**
     * Record that a switch was asked for, so the pill can say so before any
     * network call happens. Without this the tile is visually identical from
     * the tap until two round trips have finished — which is the whole of the
     * widgets feeling unresponsive.
     */
    fun markPending(plugId: String, desiredOn: Boolean, atMs: Long = System.currentTimeMillis()) =
        copy(pending = pending + (plugId to PendingSwitch(desiredOn, atMs)))

    /** Drop a pending switch — the request failed, or was answered. */
    fun clearPending(plugId: String) = copy(pending = pending - plugId)

    /**
     * Carry `previous`'s in-flight switches onto this fresh reading, dropping
     * the ones this reading has caught up to.
     *
     * The server answers from its poller's cache, which can still describe the
     * pre-switch state for a whole poll interval, so a mark is only retired
     * once a *reachable* plug actually reports the state that was asked for —
     * retiring it earlier would flip the pill back and then flip it again.
     * Anything older than [PENDING_TIMEOUT_MS] is retired regardless: a plug
     * that stops answering mid-switch would otherwise never agree, and the pill
     * would sit "switching" until someone removed the widget.
     */
    fun withPendingFrom(previous: PlugSnapshot, nowMs: Long = System.currentTimeMillis()) =
        copy(
            pending = previous.pending.filterNot { (id, switch) ->
                if (nowMs - switch.sinceMs > PENDING_TIMEOUT_MS) return@filterNot true
                val reading = plug(id)
                reading != null && reading.available && reading.relayOn == switch.desiredOn
            },
        )

    fun isPending(plugId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        pending[plugId]?.let { nowMs - it.sinceMs <= PENDING_TIMEOUT_MS } ?: false

    /** What the pill should read: the state asked for while a switch is in
     *  flight, otherwise the plug's own. */
    fun shownOn(plugId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        if (isPending(plugId, nowMs)) pending.getValue(plugId).desiredOn
        else plug(plugId)?.relayOn ?: false

    companion object {
        /** How long a switch may stay unconfirmed before the widget stops
         *  claiming it is happening. Comfortably past the server's own plug
         *  poll interval, and well short of leaving a pill stuck. */
        const val PENDING_TIMEOUT_MS = 30_000L
    }
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
        val previous = cached(context)
        return when (val r = safeCall { container.api.api.powerUsage() }) {
            is ApiResult.Ok ->
                // A switch stays pending until this reading agrees with it: the
                // server answers from its poller's cache, so the first reading
                // after a toggle often still describes the state we just left.
                store(context, snapshotOf(r.value).withPendingFrom(previous))
            is ApiResult.Err -> {
                Log.w(TAG, "widget refresh failed: ${r.message}")
                store(context, previous.copy(error = r.message, signedOut = false))
            }
        }
    }

    /**
     * Show a switch as in flight straight away, before any network call.
     *
     * The tap has to change something on screen immediately or the widget reads
     * as broken: a toggle is two round trips (the switch, then a fresh reading),
     * on a process the launcher may have only just started.
     */
    fun markPending(context: Context, plugId: String, desiredOn: Boolean): PlugSnapshot =
        store(context, cached(context).markPending(plugId, desiredOn).copy(error = null))

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
                // Apply the confirmed state and redraw now — the follow-up
                // reading is another round trip, and waiting for it is what
                // made a toggle feel like nothing had happened.
                val confirmed = cached(context).let { snap ->
                    snap.copy(
                        plugs = snap.plugs.map {
                            if (it.id == plugId) it.copy(relayOn = r.value.relayOn) else it
                        },
                        error = null,
                    ).clearPending(plugId)
                }
                store(context, confirmed)
                PlugWidgets.updateAll(context)
                // Then pull a full reading so the watts stop describing the
                // state we just left.
                refresh(context)
            }
            is ApiResult.Err -> {
                val message = if (r.code == 403) "Admin account required" else r.message
                Log.w(TAG, "widget relay switch failed: $message")
                // Drop the pending mark: leaving it would show the switch as
                // still happening until it timed out.
                store(context, cached(context).clearPending(plugId).copy(error = message))
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
