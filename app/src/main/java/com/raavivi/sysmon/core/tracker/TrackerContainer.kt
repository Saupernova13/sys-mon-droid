package com.raavivi.sysmon.core.tracker

import android.content.Context
import android.location.Location
import com.raavivi.sysmon.BuildConfig
import com.raavivi.sysmon.core.tracker.cmd.CommandProcessor
import com.raavivi.sysmon.core.tracker.data.FixQueue
import com.raavivi.sysmon.core.tracker.data.TrackerSettings
import com.raavivi.sysmon.core.tracker.model.Fix
import com.raavivi.sysmon.core.log.AppLog
import com.raavivi.sysmon.core.tracker.model.PairResponse
import com.raavivi.sysmon.core.tracker.net.DeviceApiProvider
import com.raavivi.sysmon.core.tracker.net.DeviceClient
import com.raavivi.sysmon.core.tracker.net.DeviceResult
import com.raavivi.sysmon.core.tracker.push.DevicePushRegistrar
import com.raavivi.sysmon.core.tracker.ring.RingService
import com.raavivi.sysmon.core.tracker.track.DeviceReader
import com.raavivi.sysmon.core.tracker.track.LocationSource
import com.raavivi.sysmon.core.tracker.track.ReportUploader
import com.raavivi.sysmon.core.tracker.track.TrackerService
import com.raavivi.sysmon.core.tracker.track.toFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI

/**
 * Dependencies of the built-in phone tracker, held lazily by sys-mon's `AppContainer`.
 * It has its own prefs, HTTP client and device token: sys-mon login and logout do not
 * touch it. Services and receivers reach it through the application.
 */
class TrackerContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /** Process-wide scope for work that must outlive a single service or screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = TrackerSettings(appContext)
    val queue = FixQueue(File(appContext.filesDir, "tracker_fix_queue.json"))
    val device = DeviceReader(appContext)
    val location = LocationSource(appContext)
    private val apis = DeviceApiProvider()

    val client = DeviceClient(settings, apis, BuildConfig.VERSION_NAME) { handleRemoved() }
    val push = DevicePushRegistrar(appContext, settings, client)

    val uploader = ReportUploader(
        settings = settings,
        client = client,
        queue = queue,
        device = device,
        appVersion = BuildConfig.VERSION_NAME,
        isRinging = { RingService.ringing.value },
        onCommands = { commands.process(it) },
    )

    val commands: CommandProcessor by lazy {
        CommandProcessor(
            context = appContext,
            scope = appScope,
            settings = settings,
            client = client,
            locate = ::locateNow,
            flush = { uploader.flush(force = false) },
        )
    }

    @Volatile private var serverHost: String = ""

    init {
        appScope.launch {
            settings.state.collect { state ->
                serverHost = runCatching { URI(state.server).host }.getOrNull().orEmpty()
            }
        }
    }

    /** Host of the paired server, for the notification text. */
    fun currentServerHost(): String = serverHost.ifEmpty { "sys-mon" }

    /**
     * Redeems a pairing [code] (minted by the admin API) at [server], stores the device
     * token, turns tracking on and registers the FCM token. The caller starts the service
     * once location is allowed.
     */
    suspend fun pair(server: String, code: String, name: String): DeviceResult<PairResponse> {
        val r = client.pair(server, code, name)
        if (r is DeviceResult.Ok) {
            settings.savePairing(
                server = server,
                deviceId = r.value.deviceId,
                token = r.value.token,
                intervalS = r.value.intervalS,
                sampleS = r.value.sampleS,
                deviceName = name,
                serverName = r.value.serverName,
            )
            queue.clear()
            uploader.reset()
            AppLog.i("Tracker", "paired")
            appScope.launch { push.register() }
        }
        return r
    }

    /** Converts [loc] to a fix, queues it and remembers it as the last fix. */
    suspend fun recordFix(loc: Location, source: String) {
        val fix: Fix = loc.toFix(source, device.read())
        queue.add(fix)
        settings.setLastFix((fix.ts * 1000).toLong(), fix.accuracyM?.toFloat())
    }

    /** One high-accuracy fix queued as `locate` and uploaded now; false when no fix was obtained. */
    private suspend fun locateNow(): Boolean {
        val loc = location.currentFix() ?: return false
        recordFix(loc, Fix.SOURCE_LOCATE)
        uploader.flush(force = true)
        return true
    }

    /** The server no longer knows this phone: stop everything and show the notice. */
    suspend fun handleRemoved() {
        clearLocalState("This phone was removed from sys-mon. Pair again.")
    }

    /** User-initiated unpair: tell the server, then clear local state even if it is unreachable. */
    suspend fun unpair() {
        AppLog.i("Tracker", "unpair requested")
        client.unpair()
        clearLocalState(null)
    }

    private suspend fun clearLocalState(notice: String?) {
        AppLog.i("Tracker", "pairing cleared")
        TrackerService.stop(appContext)
        RingService.stop(appContext)
        settings.clearPairing(notice)
        queue.clear()
        uploader.reset()
    }
}
