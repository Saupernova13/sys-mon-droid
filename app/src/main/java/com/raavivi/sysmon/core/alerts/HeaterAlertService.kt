package com.raavivi.sysmon.core.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.MainActivity
import com.raavivi.sysmon.R
import com.raavivi.sysmon.SysMonApp
import com.raavivi.sysmon.core.auth.SessionManager
import com.raavivi.sysmon.core.model.PowerReading
import com.raavivi.sysmon.core.model.RelayBody
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import com.raavivi.sysmon.ui.common.formatWatts
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground monitor that turns the heater plug's state into notifications.
 *
 * The server has no push channel (self-hosted, no FCM), so "push" is a
 * persistent local watcher: poll `/api/power-usage`, and whenever the heater's
 * relay is on — no matter who switched it (physically, web UI, this app, or
 * automation) — raise a high-priority notification with the live draw and a
 * Stop action that switches the relay off through the admin API. The alert is
 * cancelled the moment the relay reads off.
 */
class HeaterAlertService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastOn: Boolean? = null
    private var onSinceMs: Long = 0

    @Volatile
    private var heaterId: String? = null

    @Volatile
    private var heaterName: String = "Heater"

    @Volatile
    private var isAdmin: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startInForeground("Watching for the heater…")
        scope.launch { watchLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_HEATER) {
            scope.launch { stopHeater() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── polling ─────────────────────────────────────────────────────────────

    private suspend fun watchLoop() {
        val container = (application as SysMonApp).container
        while (true) {
            delay(tick(container))
        }
    }

    /** One poll; returns how long to sleep before the next. */
    private suspend fun tick(container: AppContainer): Long {
        // The service can outlive (or precede, on boot) the UI, so make sure
        // the shared client carries the persisted server URL + token.
        if (container.api.token == null) {
            val url = container.settings.serverUrlNow()
            if (url.isNotBlank()) container.api.setBaseUrl(url)
            val token = container.settings.tokenNow()
            if (token.isNullOrBlank()) {
                updateMonitor("Signed out — open the app to log in")
                return SIGNED_OUT_RETRY_MS
            }
            container.api.token = token
        }
        isAdmin = container.settings.roleNow() != SessionManager.ROLE_VIEWER
        return when (val r = safeCall { container.api.api.powerUsage() }) {
            is ApiResult.Ok -> {
                onReading(r.value)
                POLL_MS
            }
            is ApiResult.Err -> {
                updateMonitor("Server unreachable — retrying")
                ERROR_RETRY_MS
            }
        }
    }

    private fun onReading(env: PowerReading) {
        val heater = findHeater(env)
        if (heater == null) {
            heaterId = null
            lastOn = null
            updateMonitor("No heater plug configured on the server")
            return
        }
        heaterId = heater.id
        heaterName = heater.deviceName.ifBlank { heater.id }
        val on = heater.available && heater.relayOn
        if (on) {
            if (lastOn != true) onSinceMs = System.currentTimeMillis()
            showHeaterOn(heater, env.currency)
            updateMonitor("$heaterName is ON — ${formatWatts(heater.watts)}")
        } else {
            if (lastOn != false) NotificationManagerCompat.from(this).cancel(NOTIF_ALERT)
            updateMonitor(
                if (!heater.available) "$heaterName is offline" else "$heaterName is off",
            )
        }
        lastOn = on
    }

    /** The watched plug: id "heater" wins, else any id/name containing it. */
    private fun findHeater(env: PowerReading): PowerReading? =
        env.devices.firstOrNull { it.id.equals("heater", ignoreCase = true) }
            ?: env.devices.firstOrNull {
                it.id.contains("heater", ignoreCase = true) ||
                    it.deviceName.contains("heater", ignoreCase = true)
            }

    // ── the alert ───────────────────────────────────────────────────────────

    private fun showHeaterOn(d: PowerReading, currency: String) {
        val perHour = String.format(Locale.US, "%.2f", d.cost.perHour)
        val builder = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Your heater is on")
            .setContentText("${formatWatts(d.watts)} · $currency$perHour/h right now")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Alert (sound/heads-up) only on the ON transition; the per-poll
            // watt updates re-post the same notification silently.
            .setOnlyAlertOnce(true)
            .setWhen(onSinceMs)
            .setUsesChronometer(true)
            .setContentIntent(openAppIntent())
        if (isAdmin) {
            builder.addAction(0, "Stop heater", stopHeaterIntent())
        }
        notifySafely(NOTIF_ALERT, builder.build())
    }

    private suspend fun stopHeater() {
        val id = heaterId ?: return
        val container = (application as SysMonApp).container
        when (val r = safeCall { container.api.api.setPlugRelay(id, RelayBody(false)) }) {
            is ApiResult.Ok -> {
                lastOn = false
                NotificationManagerCompat.from(this).cancel(NOTIF_ALERT)
                updateMonitor("$heaterName stopped from the notification")
            }
            is ApiResult.Err -> {
                val builder = NotificationCompat.Builder(this, CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Couldn't stop the heater")
                    .setContentText(r.message)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOnlyAlertOnce(false)
                    .setContentIntent(openAppIntent())
                    .addAction(0, "Retry", stopHeaterIntent())
                notifySafely(NOTIF_ALERT, builder.build())
            }
        }
    }

    // ── notification plumbing ───────────────────────────────────────────────

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITOR,
                "Heater monitor",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = "Silent status of the heater watcher" },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Heater alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Fires when the heater turns on" },
        )
    }

    private fun startInForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIF_MONITOR,
            monitorNotification(text),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun monitorNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Heater monitor")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun updateMonitor(text: String) {
        notifySafely(NOTIF_MONITOR, monitorNotification(text))
    }

    /** Notify only when allowed; on 33+ the permission is user-revocable. */
    private fun notifySafely(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked mid-flight — the FGS keeps running silently.
        }
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun stopHeaterIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, HeaterAlertService::class.java).setAction(ACTION_STOP_HEATER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val ACTION_STOP_HEATER = "com.raavivi.sysmon.action.STOP_HEATER"
        private const val CHANNEL_MONITOR = "heater_monitor"
        private const val CHANNEL_ALERTS = "heater_alerts"
        private const val NOTIF_MONITOR = 41
        private const val NOTIF_ALERT = 42
        private const val POLL_MS = 15_000L
        private const val ERROR_RETRY_MS = 45_000L
        private const val SIGNED_OUT_RETRY_MS = 60_000L

        /** Idempotent start; swallows background-start restrictions (the next
         *  foreground entry or boot broadcast will get it running). */
        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, HeaterAlertService::class.java),
                )
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HeaterAlertService::class.java))
        }
    }
}
