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
 * Renders site-monitoring pushes: one card per site, tagged with its id, so a
 * "back up" card replaces the "down" card for the same site. "Down" is a
 * heads-up on the high-importance channel; "back up" posts quietly on the
 * default-importance channel (the same tag/id replaces the old card whichever
 * channel it was on). Tapping either opens the Sites screen.
 */
object SiteAlertNotifier {

    fun handle(context: Context, data: Map<String, String>) {
        val alert = SiteAlert.from(data) ?: return
        ensureChannels(context)
        val b = NotificationCompat.Builder(context, if (alert.down) CHANNEL_DOWN else CHANNEL_UP)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(alert.title())
            .setContentText(alert.text())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openSitesIntent(context, alert.id))
        if (alert.down) {
            b.setPriority(NotificationCompat.PRIORITY_HIGH)
        } else {
            b.setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setTimeoutAfter(BACK_TIMEOUT_MS)
        }
        notifySafely(context, alert.id, b.build())
    }

    /** Clear every site card (the user turned these alerts off). */
    fun cancelAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.activeNotifications.filter { it.id == NOTIF_SITE } }
            .getOrDefault(emptyList())
            .forEach { NotificationManagerCompat.from(context).cancel(it.tag, NOTIF_SITE) }
    }

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWN, "Site alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Fires when a monitored site goes down" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UP, "Site recovered", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Fires when a monitored site is back up" },
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
            NotificationManagerCompat.from(context).notify(tag, NOTIF_SITE, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: drop silently.
        }
    }

    private fun openSitesIntent(context: Context, siteId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_OPEN + siteId.hashCode(),
            MainActivity.openIntent(context, MainActivity.OPEN_SITES),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    const val CHANNEL_DOWN = "site_alerts"
    const val CHANNEL_UP = "site_recovered"
    private const val NOTIF_SITE = 80 // always tagged with the site id
    private const val BACK_TIMEOUT_MS = 30 * 60 * 1000L
    private const val REQ_OPEN = 8_000
}
