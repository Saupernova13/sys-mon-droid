package com.raavivi.sysmon.core.tracker.ring

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.raavivi.sysmon.SysMonApp
import com.raavivi.sysmon.R
import com.raavivi.sysmon.core.tracker.TrackerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service (type `mediaPlayback`) that rings the phone on a Find Me `ring`
 * command: the default alarm sound looped on the alarm stream at full alarm volume
 * (restored afterwards), vibration, and a high-importance notification with a Stop
 * action and, when allowed, a full-screen [RingActivity]. It stops on Stop, on
 * `stop_ring`, or after five minutes.
 */
class RingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var audio: AudioManager? = null
    private var previousVolume = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        TrackerNotifications.ensureChannels(this)
        try {
            ServiceCompat.startForeground(
                this, TrackerNotifications.ID_RING, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (player == null) {
            startSound()
            if (player == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            startVibration()
            _ringing.value = true
            scope.launch {
                delay(MAX_RING_MS)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSound() {
        val am = getSystemService(AudioManager::class.java)
        audio = am
        previousVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@RingService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            player?.release()
            player = null
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        val pattern = longArrayOf(0, 800, 600)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            vibrator.vibrate(pattern, 0)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val stop = PendingIntent.getService(
            this, 10, Intent(this, RingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ringIntent = PendingIntent.getActivity(
            this, 11, Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, TrackerNotifications.CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setContentTitle(getString(R.string.tracker_notif_ringing))
            .setContentIntent(ringIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, getString(R.string.tracker_action_stop), stop)
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nm.canUseFullScreenIntent()) {
            builder.setFullScreenIntent(ringIntent, true)
        }
        return builder.build()
    }

    override fun onDestroy() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        audio?.let { am ->
            if (previousVolume >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
        }
        getSystemService(Vibrator::class.java)?.cancel()
        val wasRinging = _ringing.value
        _ringing.value = false
        scope.cancel()
        if (wasRinging) {
            // Tell the server the phone is quiet now.
            val container = (application as SysMonApp).container.tracker
            container.appScope.launch { container.uploader.flush(force = true) }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.raavivi.sysmon.tracker.RING_STOP"
        private const val MAX_RING_MS = 5 * 60 * 1000L

        private val _ringing = MutableStateFlow(false)

        /** True while the phone is ringing; reported to the server and watched by [RingActivity]. */
        val ringing: StateFlow<Boolean> = _ringing

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RingService::class.java))
            } catch (_: Exception) {
                // Background start refused; the command is acknowledged as failed.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RingService::class.java))
        }
    }
}
