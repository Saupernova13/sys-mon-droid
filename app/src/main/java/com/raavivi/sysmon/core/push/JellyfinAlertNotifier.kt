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

/**
 * Turns a Jellyfin-stream FCM data message into local notifications: one per
 * active stream (tagged with its session id), bundled under a group summary once
 * more than one is playing. Each message carries the complete current set, so
 * cards for sessions no longer present are cancelled and an empty set clears
 * everything.
 *
 * Only a `start` event alerts (heads-up); `update` and `stop` re-post quietly.
 * Cards are dismissible — nothing here is ongoing.
 */
object JellyfinAlertNotifier {

    fun handle(context: Context, data: Map<String, String>) {
        val alert = JellyfinAlert.from(data) ?: return
        ensureChannel(context)
        if (alert.streams.isEmpty()) {
            cancelAll(context)
            return
        }

        for (stream in alert.streams) {
            val b = baseBuilder(context, alert.event)
                .setContentTitle(title(stream))
                .setContentText(detail(stream))
                .setGroup(GROUP_STREAMS)
            notifySafely(context, NOTIF_STREAM, b.build(), tag = stream.sessionId)
        }

        // A lone child under a summary reads as a duplicate; only group for 2+.
        if (alert.streams.size > 1) {
            val inbox = NotificationCompat.InboxStyle()
            alert.streams.forEach { inbox.addLine("${title(it)} — ${detail(it)}") }
            val summary = baseBuilder(context, alert.event)
                .setContentTitle("${alert.streams.size} people are streaming on Jellyfin")
                .setStyle(inbox)
                .setGroup(GROUP_STREAMS)
                .setGroupSummary(true)
                .build()
            notifySafely(context, NOTIF_SUMMARY, summary)
        } else {
            cancel(context, NOTIF_SUMMARY)
        }

        pruneStale(context, alert.streams.map { it.sessionId }.toSet())
    }

    /** Clear every Jellyfin card — playback ended, or the user turned alerts off. */
    fun cancelAll(context: Context) {
        cancel(context, NOTIF_SUMMARY)
        pruneStale(context, emptySet())
    }

    // ── rendering ────────────────────────────────────────────────────────────

    internal fun title(stream: JellyfinStream): String =
        "${stream.user?.takeIf { it.isNotBlank() } ?: "Someone"} is streaming on Jellyfin"

    /** "Title · 12:34 / 45:00 · Paused · Device (Client)", each part only if known. */
    internal fun detail(stream: JellyfinStream): String = buildString {
        append(stream.title.ifBlank { "something" })
        stream.positionS?.let { pos ->
            append(" · ").append(clock(pos))
            stream.runtimeS?.takeIf { it > 0 }?.let { append(" / ").append(clock(it)) }
        }
        if (stream.paused) append(" · Paused")
        val device = stream.device?.takeIf { it.isNotBlank() }
        val client = stream.client?.takeIf { it.isNotBlank() }
        when {
            device != null && client != null -> append(" · $device ($client)")
            device != null -> append(" · $device")
            client != null -> append(" · $client")
        }
    }

    /** Seconds as m:ss, or h:mm:ss from an hour up. */
    internal fun clock(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun baseBuilder(context: Context, event: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_STREAMS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // A new stream is worth a heads-up; periodic updates and stops
            // re-post the same ids and must stay quiet.
            .setOnlyAlertOnce(event == "update" || event == "stop")
            .setOngoing(false)
            .setAutoCancel(false)
            .setContentIntent(openAppIntent(context))

    /** Drop stream cards whose session is gone, read back from the system. */
    private fun pruneStale(context: Context, keep: Set<String>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val stale = runCatching {
            nm.activeNotifications
                .filter { it.id == NOTIF_STREAM && it.tag != null && it.tag !in keep }
                .map { it.tag!! }
        }.getOrDefault(emptyList())
        stale.forEach { cancel(context, NOTIF_STREAM, it) }
    }

    private fun cancel(context: Context, id: Int, tag: String? = null) {
        NotificationManagerCompat.from(context).cancel(tag, id)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STREAMS,
                "Jellyfin streams",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Fires when someone starts streaming on Jellyfin" },
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

    const val CHANNEL_STREAMS = "jellyfin_streams"
    private const val GROUP_STREAMS = "com.raavivi.sysmon.JELLYFIN_STREAMS"

    private const val NOTIF_STREAM = 60 // always tagged with the session id
    private const val NOTIF_SUMMARY = 61
}
