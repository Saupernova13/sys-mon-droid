package com.raavivi.sysmon.core.tracker.track

import com.raavivi.sysmon.core.tracker.data.FixQueue
import com.raavivi.sysmon.core.tracker.data.TrackerSettings
import com.raavivi.sysmon.core.tracker.model.Command
import com.raavivi.sysmon.core.tracker.model.ReportBody
import com.raavivi.sysmon.core.tracker.net.DeviceResult
import com.raavivi.sysmon.core.tracker.net.Backoff
import com.raavivi.sysmon.core.tracker.net.DeviceClient
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends the queued fixes to the server in chunks of at most 200, oldest first.
 * Sent fixes leave the queue only after a successful response, so nothing is lost
 * while the server is down; failures back off 30 s, 1, 2, 5, 10 min.
 */
class ReportUploader(
    private val settings: TrackerSettings,
    private val client: DeviceClient,
    private val queue: FixQueue,
    private val device: DeviceReader,
    private val appVersion: String,
    private val isRinging: () -> Boolean,
    private val onCommands: (List<Command>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile private var failures = 0

    @Volatile private var nextAttemptAt = 0L

    /** Wakes the tracker loop to upload soon (conflated). */
    val kicks = Channel<Unit>(Channel.CONFLATED)

    fun kick() {
        kicks.trySend(Unit)
    }

    /** How long to wait before the next heartbeat: [intervalMs], or sooner when a retry is due. */
    fun waitMs(intervalMs: Long): Long {
        val untilRetry = nextAttemptAt - clock()
        return if (untilRetry > 0) minOf(intervalMs, untilRetry) else intervalMs
    }

    /**
     * Uploads the queue (or a heartbeat when it is empty). Unless [force], does nothing
     * while a backoff delay is running.
     */
    suspend fun flush(force: Boolean) = mutex.withLock {
        if (!force && clock() < nextAttemptAt) return@withLock
        val chunks = queue.chunks().ifEmpty { listOf(emptyList()) }
        val heartbeat = queue.size.value == 0
        for (chunk in chunks) {
            val status = device.read()
            val body = ReportBody(
                fixes = chunk,
                batteryPct = status.batteryPct,
                charging = status.charging,
                network = status.network,
                appVersion = appVersion,
                ringing = isRinging(),
            )
            when (val r = client.report(body)) {
                is DeviceResult.Ok -> {
                    queue.remove(chunk)
                    failures = 0
                    nextAttemptAt = 0
                    r.value.intervalS?.takeIf { it > 0 }?.let { settings.setInterval(it) }
                    r.value.sampleS?.takeIf { it > 0 }?.let { settings.setSample(it) }
                    settings.setUploadResult(clock(), "OK, ${chunk.size} fixes sent")
                    if (r.value.commands.isNotEmpty()) onCommands(r.value.commands)
                }
                is DeviceResult.Err -> {
                    handleFailure(r)
                    return@withLock
                }
            }
        }
        if (heartbeat) {
            val polled = client.commands()
            if (polled is DeviceResult.Ok && polled.value.commands.isNotEmpty()) {
                onCommands(polled.value.commands)
            }
        }
    }

    private suspend fun handleFailure(err: DeviceResult.Err) {
        val now = clock()
        when {
            err.isDeviceUnknown -> Unit // DeviceClient already dropped the pairing
            err.code == 404 -> {
                nextAttemptAt = now + Backoff.FEATURE_OFF_MS
                settings.setUploadResult(now, "Find Me is turned off on the server")
            }
            else -> {
                failures++
                nextAttemptAt = now + Backoff.delayMs(failures)
                settings.setUploadResult(now, "Failed: ${err.message}")
            }
        }
    }

    /** Forgets backoff state, e.g. after a new pairing. */
    fun reset() {
        failures = 0
        nextAttemptAt = 0
    }
}
