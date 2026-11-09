package com.raavivi.sysmon.ui.findme

import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.FindMeCommandResponse
import com.raavivi.sysmon.core.model.FindMeDevice
import com.raavivi.sysmon.core.model.FindMeFix
import com.raavivi.sysmon.core.model.FindMePairBody
import com.raavivi.sysmon.core.model.FindMePairResponse
import com.raavivi.sysmon.core.model.FindMePatchBody
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.auth.SessionManager
import com.raavivi.sysmon.core.net.safeCall
import com.raavivi.sysmon.core.tracker.TrackerPermissionState
import com.raavivi.sysmon.core.tracker.TrackerPermissions
import com.raavivi.sysmon.core.tracker.data.TrackerState
import com.raavivi.sysmon.core.tracker.net.DeviceResult
import com.raavivi.sysmon.core.tracker.net.normalizeCode
import com.raavivi.sysmon.core.tracker.net.normalizeServer
import com.raavivi.sysmon.core.tracker.net.pairErrorMessage
import com.raavivi.sysmon.core.tracker.track.TrackerService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Pairing dialog state: loading until the code arrives, or [error]. */
data class PairState(
    val loading: Boolean = true,
    val code: FindMePairResponse? = null,
    val error: String? = null,
)

/**
 * Find Me screen state. The screen drives [refresh] from a lifecycle-scoped loop
 * (see [awaitNextPoll]); actions wake that loop so the new command shows at once.
 */
class FindMeViewModel(private val container: AppContainer) : ViewModel() {

    var devices by mutableStateOf<List<FindMeDevice>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    /** The server answered 404: Find Me is off there. */
    var disabled by mutableStateOf(false)
        private set

    /** Last refresh error; the previous [devices] stay on screen. */
    var error by mutableStateOf<String?>(null)
        private set
    var pushConfigured by mutableStateOf(true)
        private set
    var message by mutableStateOf<String?>(null)

    var selectedId by mutableStateOf<String?>(null)
        private set
    var range by mutableStateOf(TrailRange.Day)
        private set
    var trail by mutableStateOf<List<FindMeFix>>(emptyList())
        private set
    var address by mutableStateOf<String?>(null)
        private set

    var pair by mutableStateOf<PairState?>(null)
        private set

    /** Buttons with a request in flight, as "deviceId:kind". */
    var sending by mutableStateOf<Set<String>>(emptySet())
        private set

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val geocodeCache = HashMap<String, String?>()
    private var pairKnownIds: Set<String> = emptySet()
    private var trailKey: Triple<String?, TrailRange, Double?>? = null
    private var addressKey: String? = null

    // This phone (built-in tracker) --------------------------------------------

    private val tracker = container.tracker
    private val context = container.appContext

    /** Tracker pairing and status; null until the first read. */
    val phone: StateFlow<TrackerState?> = tracker.settings.state
        .map<TrackerState, TrackerState?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val queued: StateFlow<Int> = tracker.queue.size
    val pushAvailable: Boolean get() = tracker.push.available

    var perms by mutableStateOf<TrackerPermissionState>(TrackerPermissions.read(context))
        private set

    /** Pairing needs an admin; viewers never see the switch. */
    var phoneIsAdmin by mutableStateOf(true)
        private set

    /** Set after a successful pairing from this screen; the screen then walks the permission checklist. */
    var walkRequested by mutableStateOf(false)
    var phoneBusy by mutableStateOf(false)
        private set
    var phoneError by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            phoneIsAdmin = (container.settings.roleNow() ?: SessionManager.ROLE_ADMIN) != SessionManager.ROLE_VIEWER
        }
    }

    fun refreshPermissions() {
        perms = TrackerPermissions.read(context)
    }

    /** Switch handler: pairs this phone first when needed, else pauses or resumes tracking. */
    fun trackThisPhone(on: Boolean) {
        val state = phone.value ?: return
        phoneError = null
        if (state.paired) {
            viewModelScope.launch {
                tracker.settings.setTracking(on)
                if (on) TrackerService.start(context) else TrackerService.stop(context)
            }
        } else if (on && phoneIsAdmin && !phoneBusy) {
            pairThisPhone()
        }
    }

    /** Mints a code with the admin API, then redeems it as this phone through the device API. */
    private fun pairThisPhone() {
        phoneBusy = true
        viewModelScope.launch {
            val server = normalizeServer(container.api.httpBaseUrl())
            val name = defaultPhoneName()
            if (server == null) {
                phoneError = "No server address"
            } else {
                when (val minted = safeCall { container.api.api.findMePair(FindMePairBody(name = name, server = server)) }) {
                    is ApiResult.Err ->
                        phoneError = if (minted.code == 404) "Find Me is off on the server" else minted.message
                    is ApiResult.Ok -> {
                        val code = normalizeCode(minted.value.code)
                        if (code == null) {
                            phoneError = "The server sent an unusable pairing code"
                        } else {
                            when (val r = tracker.pair(server, code, name)) {
                                is DeviceResult.Ok -> {
                                    message = "This phone is now tracked"
                                    walkRequested = true
                                }
                                is DeviceResult.Err -> phoneError = pairErrorMessage(r)
                            }
                        }
                    }
                }
            }
            phoneBusy = false
            wake.trySend(Unit)
        }
    }

    /** Starts the tracker when it should be running and location is allowed. */
    fun ensureTracking() {
        TrackerService.start(context)
    }

    fun reportNow() {
        TrackerService.start(context, TrackerService.ACTION_REPORT_NOW)
    }

    fun unpairThisPhone() {
        tracker.appScope.launch {
            tracker.unpair()
            wake.trySend(Unit)
        }
    }

    fun dismissPhoneNotice() {
        viewModelScope.launch { tracker.settings.clearNotice() }
    }

    private fun defaultPhoneName(): String =
        Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    val selected: FindMeDevice? get() = devices.firstOrNull { it.id == selectedId }

    /** Fetches the device list; on failure keeps the last data and sets [error]. */
    suspend fun refresh() {
        when (val r = safeCall { container.api.api.findMeDevices() }) {
            is ApiResult.Ok -> {
                disabled = false
                error = null
                devices = r.value.devices
                pushConfigured = r.value.pushConfigured
                loaded = true
                if (devices.none { it.id == selectedId }) {
                    selectedId = devices.firstOrNull { it.lastFix != null }?.id ?: devices.firstOrNull()?.id
                }
                checkPaired()
                syncSelection()
            }
            is ApiResult.Err -> {
                loaded = true
                if (r.code == 404) {
                    disabled = true
                    error = "Find Me is off on the server"
                } else {
                    error = r.message
                }
            }
        }
    }

    /** Waits the poll interval for the current state, or until an action wakes the loop. */
    suspend fun awaitNextPoll() {
        withTimeoutOrNull(pollDelayMs(anyPending(devices), pair != null)) { wake.receive() }
    }

    fun select(id: String) {
        if (id == selectedId) return
        selectedId = id
        syncSelection()
    }

    fun selectRange(value: TrailRange) {
        if (value == range) return
        range = value
        syncSelection()
    }

    /** Reloads the trail and address when the selection, range or newest fix changed. */
    private fun syncSelection() {
        val dev = selected
        val key = Triple(dev?.id, range, dev?.lastFix?.ts)
        if (key != trailKey) {
            trailKey = key
            if (dev == null) {
                trail = emptyList()
            } else {
                loadTrail(dev.id, range)
            }
        }
        val fix = dev?.lastFix
        if (fix == null) {
            address = null
            addressKey = null
            return
        }
        val gk = geocodeKey(fix.lat, fix.lon)
        if (gk == addressKey) return
        addressKey = gk
        if (geocodeCache.containsKey(gk)) {
            address = geocodeCache[gk]
            return
        }
        address = null
        viewModelScope.launch {
            val r = safeCall { container.api.api.findMeGeocode(fix.lat, fix.lon) }
            if (r is ApiResult.Ok) {
                geocodeCache[gk] = r.value.address
                if (addressKey == gk) address = r.value.address
            }
        }
    }

    private fun loadTrail(id: String, range: TrailRange) {
        viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000.0
            val r = safeCall { container.api.api.findMeHistory(id, start = now - range.secs, end = now) }
            if (r is ApiResult.Ok && selectedId == id && this@FindMeViewModel.range == range) {
                trail = r.value.fixes
            }
        }
    }

    // Commands ---------------------------------------------------------------

    fun locate(id: String) = command(id, "locate", "Locate sent") { container.api.api.findMeLocate(id) }

    fun ring(id: String) = command(id, "ring", "Ring sent") { container.api.api.findMeRing(id) }

    fun stopRing(id: String) = command(id, "stop_ring", "Stop sent") { container.api.api.findMeRingStop(id) }

    private fun command(id: String, kind: String, ok: String, call: suspend () -> FindMeCommandResponse) {
        val key = "$id:$kind"
        if (key in sending) return
        sending = sending + key
        viewModelScope.launch {
            when (val r = safeCall(call)) {
                is ApiResult.Ok -> message = if (r.value.pushed) ok else "$ok (no push, delivered at next report)"
                is ApiResult.Err -> message = "Failed: ${r.message}"
            }
            sending = sending - key
            wake.trySend(Unit)
        }
    }

    // Device edits -----------------------------------------------------------

    fun rename(id: String, name: String) = patch(id, FindMePatchBody(name = name.trim()))

    fun setInterval(id: String, intervalS: Int) = patch(id, FindMePatchBody(intervalS = intervalS))

    private fun patch(id: String, body: FindMePatchBody) {
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.findMePatch(id, body) }) {
                is ApiResult.Ok -> devices = devices.map { if (it.id == id) r.value else it }
                is ApiResult.Err -> message = "Update failed: ${r.message}"
            }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.findMeRemove(id) }) {
                is ApiResult.Ok -> {
                    message = "Device removed"
                    devices = devices.filter { it.id != id }
                    if (selectedId == id) {
                        selectedId = devices.firstOrNull()?.id
                        syncSelection()
                    }
                }
                is ApiResult.Err -> message = "Remove failed: ${r.message}"
            }
        }
    }

    // Pairing ----------------------------------------------------------------

    fun startPair() {
        pairKnownIds = devices.map { it.id }.toSet()
        pair = PairState()
        wake.trySend(Unit)
        viewModelScope.launch {
            val body = FindMePairBody(server = container.api.httpBaseUrl())
            pair = when (val r = safeCall { container.api.api.findMePair(body) }) {
                is ApiResult.Ok -> PairState(loading = false, code = r.value)
                is ApiResult.Err -> PairState(loading = false, error = r.message)
            }
        }
    }

    fun closePair() {
        pair = null
        wake.trySend(Unit)
    }

    /** Closes the pair dialog when a device not seen at its start has appeared. */
    private fun checkPaired() {
        if (pair == null) return
        val fresh = newDevices(pairKnownIds, devices).firstOrNull() ?: return
        pair = null
        message = "Paired: ${fresh.name}"
        selectedId = fresh.id
        syncSelection()
    }
}
