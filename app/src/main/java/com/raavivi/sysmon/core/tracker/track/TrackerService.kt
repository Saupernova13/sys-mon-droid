package com.raavivi.sysmon.core.tracker.track

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.raavivi.sysmon.MainActivity
import com.raavivi.sysmon.R
import com.raavivi.sysmon.SysMonApp
import com.raavivi.sysmon.core.log.AppLog
import com.raavivi.sysmon.core.tracker.TrackerContainer
import com.raavivi.sysmon.core.tracker.TrackerNotifications
import com.raavivi.sysmon.core.tracker.TrackerPermissions
import com.raavivi.sysmon.core.tracker.model.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service (type `location`) that keeps the persistent "Sharing location"
 * notification up while tracking. It samples location every `sample_s` (server-set,
 * default 60 s), queues every fix, and uploads the queue every report interval (a
 * heartbeat, also sent when the queue is empty). Notification actions: "Report now" and "Pause".
 */
class TrackerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: TrackerContainer
    private var updatesJob: Job? = null
    private var loopJob: Job? = null
    private var callback: LocationCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = (application as SysMonApp).container.tracker
        TrackerNotifications.ensureChannels(this)
        AppLog.i("Tracker", "service start")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = app
        if (!startForegroundSafely(container.currentServerHost())) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> {
                AppLog.i("Tracker", "paused from notification")
                scope.launch {
                    container.settings.setTracking(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_REPORT_NOW -> scope.launch { oneShot(Fix.SOURCE_MANUAL) }
            ACTION_BOOT -> scope.launch { oneShot(Fix.SOURCE_BOOT) }
        }
        scope.launch {
            val state = container.settings.stateNow()
            if (!state.paired || !state.tracking) {
                // Nothing to track (paused or unpaired): drop out. A paused phone
                // must not keep a location service alive.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else if (updatesJob == null) {
                startWork()
            }
        }
        return START_STICKY
    }

    private fun startForegroundSafely(host: String): Boolean = try {
        ServiceCompat.startForeground(
            this, TrackerNotifications.ID_TRACKING, buildNotification(host),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        true
    } catch (_: Exception) {
        false // e.g. the system refused a background start
    }

    private fun startWork() {
        val container = app
        updatesJob = scope.launch {
            container.settings.state.map { it.sampleS }.distinctUntilChanged().collect { sample ->
                callback?.let { container.location.stopUpdates(it) }
                val cb = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        scope.launch {
                            // Queued only: the heartbeat loop uploads every interval.
                            for (loc in result.locations) container.recordFix(loc, Fix.SOURCE_TRAIL)
                        }
                    }
                }
                callback = cb
                container.location.startUpdates(sample, cb)
            }
        }
        loopJob = scope.launch {
            // Heartbeat loop: upload when kicked, and at least every interval (sooner for a retry).
            while (isActive) {
                container.uploader.flush(force = false)
                val interval = container.settings.stateNow().intervalS * 1000L
                withTimeoutOrNull(container.uploader.waitMs(interval)) {
                    container.uploader.kicks.receive()
                }
            }
        }
    }

    private suspend fun oneShot(source: String) {
        val container = app
        val loc = container.location.currentFix()
        if (loc != null) container.recordFix(loc, source)
        container.uploader.flush(force = true)
    }

    private fun buildNotification(host: String) = NotificationCompat.Builder(this, TrackerNotifications.CHANNEL_TRACKING)
        .setSmallIcon(R.drawable.ic_stat_pin)
        .setContentTitle(getString(R.string.tracker_title))
        .setContentText(getString(R.string.tracker_notif_sharing, host))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, MainActivity.openIntent(this, MainActivity.OPEN_FIND_ME),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(0, getString(R.string.tracker_action_report_now), actionIntent(ACTION_REPORT_NOW, 1))
        .addAction(0, getString(R.string.tracker_action_pause), actionIntent(ACTION_PAUSE, 2))
        .build()

    private fun actionIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, TrackerService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        AppLog.i("Tracker", "service stop")
        callback?.let { app.location.stopUpdates(it) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.raavivi.sysmon.tracker.START"
        const val ACTION_REPORT_NOW = "com.raavivi.sysmon.tracker.REPORT_NOW"
        const val ACTION_PAUSE = "com.raavivi.sysmon.tracker.PAUSE"
        const val ACTION_BOOT = "com.raavivi.sysmon.tracker.BOOT"

        /** Starts (or re-attaches to) the tracker; call only with location permission granted. */
        fun start(context: Context, action: String = ACTION_START) {
            if (!TrackerPermissions.hasFineLocation(context)) return
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, TrackerService::class.java).setAction(action),
                )
            } catch (_: Exception) {
                // Background start refused; the user can start it from the app.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackerService::class.java))
        }
    }
}
