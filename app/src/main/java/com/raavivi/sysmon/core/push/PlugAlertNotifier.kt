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
 * Turns a plug-alert FCM data message into the local notification. The backend
 * watches a configured plug's relay and pushes `event=on|update|off`; this
 * renders (or clears) a single high-priority notification showing the live draw,
 * the running cost, a chronometer counting from when the plug came on, and — for
 * admins — a Stop button that switches the plug off through [StopPlugReceiver].
 *
 * When the message asks for it (`ongoing=true`) the notification is ongoing:
 * the user can't swipe it away, it only clears when the plug turns off.
 */
object PlugAlertNotifier {

    fun handle(context: Context, data: Map<String, String>, isAdmin: Boolean) {
        ensureChannel(context)
        when (data["event"]) {
            "off" -> cancel(context)
            else -> show(context, data, isAdmin) // "on" / "update"
        }
    }

    private fun show(context: Context, data: Map<String, String>, isAdmin: Boolean) {
        val label = data["label"].orEmpty().ifBlank { data["device_name"].orEmpty() }.ifBlank { "appliance" }
        val watts = data["watts"]?.toDoubleOrNull() ?: 0.0
        val perHour = data["cost_per_hour"]?.toDoubleOrNull() ?: 0.0
        val currency = data["currency"].orEmpty()
        val startedAtMs = ((data["started_at"]?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        val plugId = data["plug_id"].orEmpty()
        val ongoing = data["ongoing"] != "false" // default true

        val costStr = String.format(Locale.US, "%.2f", perHour)
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Your $label is on")
            .setContentText("${formatWatts(watts)} · $currency$costStr/h right now")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // "on" alerts (heads-up/sound); each "update" re-posts the same id
            // silently to refresh the watts/cost without buzzing again.
            .setOnlyAlertOnce(true)
            // Non-dismissable while on: it only goes away on the "off" push.
            .setOngoing(ongoing)
            .setAutoCancel(false)
            .setContentIntent(openAppIntent(context))
        if (startedAtMs > 0) {
            builder.setWhen(startedAtMs).setUsesChronometer(true).setShowWhen(true)
        }
        if (isAdmin && plugId.isNotBlank()) {
            builder.addAction(0, "Stop", stopIntent(context, plugId))
        }
        notifySafely(context, builder.build())
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ALERT)
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

    private fun notifySafely(context: Context, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ALERT, notification)
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
    private const val NOTIF_ALERT = 42
}
