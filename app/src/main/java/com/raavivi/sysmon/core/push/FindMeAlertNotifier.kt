package com.raavivi.sysmon.core.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.raavivi.sysmon.MainActivity
import com.raavivi.sysmon.R
import com.raavivi.sysmon.ui.findme.streetViewLinks

/**
 * Renders Find Me reachability pushes: one card per device, tagged with its id.
 * `unreachable` posts a heads-up "<name> is unreachable" with the last-seen time
 * and battery; `reachable` replaces that card quietly with "<name> is back",
 * which then times out on its own. Tapping either opens the Find Me screen.
 */
object FindMeAlertNotifier {

    fun handle(context: Context, data: Map<String, String>) {
        val alert = FindMeAlert.from(data) ?: return
        ensureChannel(context)
        val b = NotificationCompat.Builder(context, CHANNEL_FINDME)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(alert.title())
            .setContentText(alert.text())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openFindMeIntent(context, alert.deviceId))
        if (alert.unreachable) {
            b.setPriority(NotificationCompat.PRIORITY_HIGH)
            if (alert.lat != null && alert.lon != null) {
                val url = streetViewLinks(alert.lat, alert.lon).google
                b.addAction(0, "Street View", viewIntent(context, alert.deviceId, url))
            }
        } else {
            // Replaces the unreachable card without a second buzz, then clears itself.
            b.setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setTimeoutAfter(BACK_TIMEOUT_MS)
        }
        notifySafely(context, alert.deviceId, b.build())
    }

    /** Clear every Find Me card (the user turned these alerts off). */
    fun cancelAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.activeNotifications.filter { it.id == NOTIF_DEVICE } }
            .getOrDefault(emptyList())
            .forEach { NotificationManagerCompat.from(context).cancel(it.tag, NOTIF_DEVICE) }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FINDME,
                "Find Me alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Fires when a tracked device stops reporting, and when it is back" },
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
            NotificationManagerCompat.from(context).notify(tag, NOTIF_DEVICE, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: drop silently.
        }
    }

    private fun openFindMeIntent(context: Context, deviceId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_OPEN + deviceId.hashCode(),
            MainActivity.openIntent(context, MainActivity.OPEN_FIND_ME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun viewIntent(context: Context, deviceId: String, url: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_VIEW + deviceId.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    const val CHANNEL_FINDME = "findme_alerts"
    private const val NOTIF_DEVICE = 70 // always tagged with the device id
    private const val BACK_TIMEOUT_MS = 10 * 60 * 1000L
    private const val REQ_OPEN = 7_000
    private const val REQ_VIEW = 7_500
}
