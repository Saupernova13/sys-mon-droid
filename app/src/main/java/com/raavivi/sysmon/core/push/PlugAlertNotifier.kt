package com.raavivi.sysmon.core.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.raavivi.sysmon.MainActivity
import com.raavivi.sysmon.R
import com.raavivi.sysmon.ui.common.formatWatts
import java.util.Locale

/**
 * Turns a plug-alert FCM data message into local notifications. The backend
 * watches every plug flagged to alert and pushes the full set that's on; this
 * renders it one of two ways, per the server's [AlertStyle]:
 *
 *  - [AlertStyle.SEPARATE]: a notification per plug, each with its own live
 *    draw, chronometer and Stop button, bundled under a group summary once more
 *    than one is on.
 *  - [AlertStyle.COMBINED]: a single notification listing every plug, with a
 *    Stop action each. Android caps a notification at three actions, so beyond
 *    three plugs the rest are listed without their own button — [AlertStyle.SEPARATE]
 *    is the style that scales.
 *
 * When the message asks for it (`ongoing=true`) notifications can't be swiped
 * away; they clear only when their plug turns off.
 */
object PlugAlertNotifier {

    /**
     * [mutedPlugs] are ids this phone has silenced locally. The server still
     * watches them and still sends them, so they're dropped here — and the
     * totals are recomputed from what's left, or a silenced plug would keep
     * inflating the figure on the card it isn't shown on.
     */
    fun handle(
        context: Context,
        data: Map<String, String>,
        isAdmin: Boolean,
        mutedPlugs: Set<String> = emptySet(),
    ) {
        val alert = PlugAlert.from(data)?.withoutMuted(mutedPlugs) ?: return
        ensureChannel(context)
        if (alert.plugs.isEmpty()) {
            cancelAll(context)
            return
        }
        when (alert.style) {
            AlertStyle.SEPARATE -> showSeparate(context, alert, isAdmin)
            AlertStyle.COMBINED -> showCombined(context, alert, isAdmin)
        }
    }

    // ── separate: one card per plug ──────────────────────────────────────────

    private fun showSeparate(context: Context, alert: PlugAlert, isAdmin: Boolean) {
        for (plug in alert.plugs) {
            val b = baseBuilder(context, alert)
                .setContentTitle("Your ${plug.display} is on")
                .setContentText(usage(plug.watts, plug.costPerHour, alert.currency))
                .setGroup(GROUP_ALERTS)
            if (plug.startedAtMs > 0) {
                b.setWhen(plug.startedAtMs).setUsesChronometer(true).setShowWhen(true)
            }
            addStop(context, b, plug, isAdmin, "Stop")
            notifySafely(context, NOTIF_PLUG, b.build(), tag = plug.id)
        }

        // A lone child under a summary just reads as a duplicate; let Android
        // show the one card on its own until there's actually a group.
        if (alert.plugs.size > 1) {
            val inbox = NotificationCompat.InboxStyle()
                .setSummaryText(usage(alert.totalWatts, alert.totalCostPerHour, alert.currency))
            alert.plugs.forEach { inbox.addLine(line(it, alert.currency)) }
            val summary = baseBuilder(context, alert)
                .setContentTitle("${alert.plugs.size} appliances on")
                .setContentText(usage(alert.totalWatts, alert.totalCostPerHour, alert.currency))
                .setStyle(inbox)
                .setGroup(GROUP_ALERTS)
                .setGroupSummary(true)
                .build()
            notifySafely(context, NOTIF_SUMMARY, summary)
        } else {
            cancel(context, NOTIF_SUMMARY)
        }

        cancel(context, NOTIF_COMBINED) // in case the style just changed
        pruneStalePlugCards(context, alert.plugs.map { it.id }.toSet())
    }

    // ── combined: one card listing every plug ────────────────────────────────

    private fun showCombined(context: Context, alert: PlugAlert, isAdmin: Boolean) {
        val many = alert.plugs.size > 1
        val b = baseBuilder(context, alert)
            .setContentTitle(
                if (many) "${alert.plugs.size} appliances on"
                else "Your ${alert.plugs.first().display} is on",
            )
            .setContentText(usage(alert.totalWatts, alert.totalCostPerHour, alert.currency))
        if (many) {
            val inbox = NotificationCompat.InboxStyle()
                .setSummaryText(usage(alert.totalWatts, alert.totalCostPerHour, alert.currency))
            alert.plugs.forEach { inbox.addLine(line(it, alert.currency)) }
            b.setStyle(inbox)
        } else {
            // One plug: the card is identical to a separate one, timer and all.
            val only = alert.plugs.first()
            if (only.startedAtMs > 0) {
                b.setWhen(only.startedAtMs).setUsesChronometer(true).setShowWhen(true)
            }
        }
        // Android drops actions past the third, so don't imply more exist.
        alert.plugs.take(MAX_ACTIONS).forEach { plug ->
            addStop(context, b, plug, isAdmin, if (many) "Stop ${plug.display}" else "Stop")
        }
        notifySafely(context, NOTIF_COMBINED, b.build())

        cancel(context, NOTIF_SUMMARY) // in case the style just changed
        pruneStalePlugCards(context, emptySet())
    }

    // ── shared ───────────────────────────────────────────────────────────────

    private fun baseBuilder(context: Context, alert: PlugAlert): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // A new plug coming on is worth a heads-up; the "update" that only
            // refreshes the watts re-posts the same id and must stay quiet.
            .setOnlyAlertOnce(alert.event != "on")
            .setOngoing(alert.ongoing)
            .setAutoCancel(false)
            .setContentIntent(openAppIntent(context))

    private fun usage(watts: Double, costPerHour: Double, currency: String): String =
        "${formatWatts(watts)} · $currency${String.format(Locale.US, "%.2f", costPerHour)}/h"

    private fun line(plug: AlertPlug, currency: String): String =
        "${plug.display} — ${usage(plug.watts, plug.costPerHour, currency)}"

    private fun addStop(
        context: Context,
        b: NotificationCompat.Builder,
        plug: AlertPlug,
        isAdmin: Boolean,
        label: String,
    ) {
        // A viewer can't switch a relay, so don't offer a button that 403s.
        if (!isAdmin || plug.id.isBlank()) return
        b.addAction(0, label, stopIntent(context, plug.id))
    }

    /**
     * Drop per-plug cards whose plug is no longer in the alert. Read back from
     * the system rather than remembered: an FCM message can arrive in a fresh
     * process, so anything we tried to keep in memory would already be gone.
     */
    private fun pruneStalePlugCards(context: Context, keep: Set<String>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val stale = runCatching {
            nm.activeNotifications
                .filter { it.id == NOTIF_PLUG && it.tag != null && it.tag !in keep }
                .map { it.tag!! }
        }.getOrDefault(emptyList())
        stale.forEach { cancel(context, NOTIF_PLUG, it) }
    }

    /** Clear every alert card — the last plug went off. */
    fun cancelAll(context: Context) {
        cancel(context, NOTIF_COMBINED)
        cancel(context, NOTIF_SUMMARY)
        pruneStalePlugCards(context, emptySet())
    }

    /** Clear one plug's card (its Stop button was pressed). */
    fun cancelPlug(context: Context, plugId: String) {
        cancel(context, NOTIF_PLUG, plugId)
    }

    private fun cancel(context: Context, id: Int, tag: String? = null) {
        NotificationManagerCompat.from(context).cancel(tag, id)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Appliance alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Fires when a watched plug turns on" },
        )
    }

    private fun notifySafely(
        context: Context,
        id: Int,
        notification: android.app.Notification,
        tag: String? = null,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post — drop silently.
        }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun stopIntent(context: Context, plugId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            plugId.hashCode(),
            Intent(context, StopPlugReceiver::class.java)
                .setAction(StopPlugReceiver.ACTION_STOP_PLUG)
                .putExtra(StopPlugReceiver.EXTRA_PLUG_ID, plugId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    const val CHANNEL_ALERTS = "plug_alerts"
    private const val GROUP_ALERTS = "com.raavivi.sysmon.PLUG_ALERTS"

    /** Android silently drops notification actions past the third. */
    private const val MAX_ACTIONS = 3

    // 42 was the single-plug alert's id. Keeping it for the combined card means
    // an app upgrade with a plug already on replaces that card instead of
    // leaving an orphan nothing will ever clear.
    private const val NOTIF_COMBINED = 42
    private const val NOTIF_SUMMARY = 43
    private const val NOTIF_PLUG = 44 // always tagged with the plug id
}
