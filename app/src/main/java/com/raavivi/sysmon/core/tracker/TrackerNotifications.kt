package com.raavivi.sysmon.core.tracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Notification channel ids and creation. */
object TrackerNotifications {
    const val CHANNEL_TRACKING = "tracker_sharing"
    const val CHANNEL_RING = "tracker_ring"
    const val ID_TRACKING = 2001
    const val ID_RING = 2002

    /** Creates both channels (idempotent). */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, "Phone tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this phone shares its location"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RING, "Phone ring", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while Find Me is ringing this phone"
                setSound(null, null)
            },
        )
    }
}
