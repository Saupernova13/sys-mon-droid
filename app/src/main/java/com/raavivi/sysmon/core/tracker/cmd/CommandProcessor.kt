package com.raavivi.sysmon.core.tracker.cmd

import android.content.Context
import com.raavivi.sysmon.core.tracker.data.TrackerSettings
import com.raavivi.sysmon.core.tracker.model.Command
import com.raavivi.sysmon.core.tracker.net.DeviceClient
import com.raavivi.sysmon.core.tracker.ring.RingService
import com.raavivi.sysmon.core.tracker.track.TrackerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs server commands (`locate`, `ring`, `stop_ring`). Each command id is handled
 * once; it is acknowledged `delivered` on receipt, then `done` or `failed`.
 */
class CommandProcessor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: TrackerSettings,
    private val client: DeviceClient,
    private val locate: suspend () -> Boolean,
    private val flush: suspend () -> Unit,
) {
    private val lock = Mutex()
    private var handled: HandledIds? = null

    /** Starts handling [commands] (from FCM, a report response or the poll) in the background. */
    fun process(commands: List<Command>) {
        for (cmd in commands) scope.launch { handle(cmd) }
    }

    private suspend fun isNew(id: String): Boolean = lock.withLock {
        val ids = handled ?: HandledIds(settings.handledIdsNow()).also { handled = it }
        val fresh = ids.markIfNew(id)
        if (fresh) settings.setHandledIds(ids.snapshot())
        fresh
    }

    private suspend fun handle(cmd: Command) {
        if (!settings.stateNow().paired || !isNew(cmd.id)) return
        // Start the foreground service before any network call: a push only allows a
        // background service start for a few seconds.
        when (cmd.kind) {
            Command.LOCATE -> if (settings.stateNow().tracking) TrackerService.start(context)
            Command.RING -> RingService.start(context)
        }
        client.ack(cmd.id, "delivered")
        when (cmd.kind) {
            Command.LOCATE -> doLocate(cmd)
            Command.RING -> doRing(cmd)
            Command.STOP_RING -> {
                RingService.stop(context)
                client.ack(cmd.id, "done")
                flush()
            }
            else -> client.ack(cmd.id, "failed", "Unsupported command")
        }
    }

    private suspend fun doLocate(cmd: Command) {
        if (!settings.stateNow().tracking) {
            client.ack(cmd.id, "failed", PAUSED_ERROR)
            return
        }
        if (locate()) client.ack(cmd.id, "done") else client.ack(cmd.id, "failed", "No location fix")
    }

    private suspend fun doRing(cmd: Command) {
        val started = withTimeoutOrNull(RING_START_TIMEOUT_MS) { RingService.ringing.first { it } } == true
        if (started) {
            client.ack(cmd.id, "done")
            flush() // report ringing = true
        } else {
            client.ack(cmd.id, "failed", "Could not start the alarm")
        }
    }

    companion object {
        const val PAUSED_ERROR = "Tracking is paused on the phone"
        private const val RING_START_TIMEOUT_MS = 5_000L
    }
}
