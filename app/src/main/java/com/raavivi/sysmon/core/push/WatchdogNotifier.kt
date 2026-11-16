package com.raavivi.sysmon.core.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.raavivi.sysmon.MainActivity
import com.raavivi.sysmon.R

/**
 * Renders watchdog pushes: one card per target (tag = target id), so a later
 * event for the same target replaces the earlier one ("back" replaces "down").
 * Problems are heads-up on "Health alerts"; good news and housekeeping post
 * quietly on "Health updates" and clear themselves. Pause/resume are not
 * notified. Tapping a card opens the Health screen.
 */
object WatchdogNotifier {

    fun handle(context: Context, data: Map<String, String>) {
        val alert = WatchdogAlert.from(data) ?: return
        val level = alert.level ?: return
        ensureChannels(context)
        val loud = level == WatchdogLevel.ALERT
        val b = NotificationCompat.Builder(context, if (loud) CHANNEL_ALERTS else CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(alert.title())
            .setContentText(alert.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.detail))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openHealthIntent(context, alert.id))
        if (loud) {
            b.setPriority(NotificationCompat.PRIORITY_HIGH)
        } else {
            b.setPriority(NotificationCompat.PRIORITY_DEFAULT).setTimeoutAfter(UPDATE_TIMEOUT_MS)
        }
        notifySafely(context, alert.id, b.build())
    }

    /** Clear every health card (the user turned these alerts off). */
    fun cancelAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.activeNotifications.filter { it.id == NOTIF_TARGET } }
            .getOrDefault(emptyList())
            .forEach { NotificationManagerCompat.from(context).cancel(it.tag, NOTIF_TARGET) }
    }

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Health alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "A service is down, a restart failed, or the watchdog gave up" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "Health updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A service was restarted or is back, and housekeeping the watchdog did" },
        )
    }

    private fun notifySafely(context: Context, tag: String, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(tag, NOTIF_TARGET, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: drop silently.
        }
    }

    private fun openHealthIntent(context: Context, targetId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_OPEN + targetId.hashCode(),
            MainActivity.openIntent(context, MainActivity.OPEN_HEALTH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    const val CHANNEL_ALERTS = "health_alerts"
    const val CHANNEL_UPDATES = "health_updates"
    private const val NOTIF_TARGET = 100 // always tagged with the target id
    private const val UPDATE_TIMEOUT_MS = 30 * 60 * 1000L
    private const val REQ_OPEN = 10_000
}
