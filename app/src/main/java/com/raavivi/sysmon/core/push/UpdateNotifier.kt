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
import com.raavivi.sysmon.BuildConfig
import com.raavivi.sysmon.MainActivity
import com.raavivi.sysmon.R
import com.raavivi.sysmon.core.update.DroidUpdatePush

/** "sys-mon-droid <version> is available" for a `type=droid_update` push; tap opens the update screen. */
object UpdateNotifier {

    fun handle(context: Context, data: Map<String, String>) {
        val push = DroidUpdatePush.from(data) ?: return
        // Only a newer build is worth a card.
        if (push.versionCode <= BuildConfig.VERSION_CODE) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Fires when a new sys-mon-droid build is available" },
        )
        val tap = PendingIntent.getActivity(
            context,
            REQ_OPEN,
            MainActivity.openIntent(context, MainActivity.OPEN_UPDATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("sys-mon-droid ${push.versionName} is available")
            .setContentText("Tap to download and install it.")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_UPDATE, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: drop silently.
        }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIF_UPDATE)

    const val CHANNEL = "app_updates"
    private const val NOTIF_UPDATE = 90
    private const val REQ_OPEN = 9_000
}
