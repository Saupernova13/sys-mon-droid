package com.raavivi.sysmon.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.SmartDisk
import com.raavivi.sysmon.core.model.SmartResponse
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Loads `GET /api/smart` for the S.M.A.R.T. sheet. [focusId] (the volume's
 * `smart_disk_id`) or, failing that, [focusKey] (its mountpoint / letter)
 * picks the disk shown first.
 */
class SmartViewModel(
    private val container: AppContainer,
    private val focusId: String?,
    private val focusKey: String?,
) : ViewModel() {

    var response by mutableStateOf<SmartResponse?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var rescanning by mutableStateOf(false)
        private set
    /** The widget is off on the server (HTTP 404). */
    var off by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set

    val isAdmin: Boolean get() = container.session.isAdmin
    val isWindows: Boolean get() = container.session.host.value?.isWindows ?: true

    /** Where the readings come from: the payload's `source`, else by host OS. */
    val sourceName: String
        get() = smartSourceName(response?.source, isWindows)

    init {
        load(rescan = false)
    }

    fun select(id: String) {
        selectedId = id
    }

    fun rescan() = load(rescan = true)

    private fun load(rescan: Boolean) {
        if (rescanning) return
        viewModelScope.launch {
            if (rescan) rescanning = true else loading = true
            error = null
            val r = safeCall {
                if (rescan) container.api.api.smartRescan() else container.api.api.smart()
            }
            when (r) {
                is ApiResult.Ok -> {
                    response = r.value
                    off = false
                    val ids = r.value.disks.map { it.id }
                    if (selectedId !in ids) {
                        selectedId = pickDisk(r.value.disks, focusId, focusKey)
                    }
                }
                is ApiResult.Err -> {
                    if (r.code == 404) off = true else error = r.message
                }
            }
            loading = false
            rescanning = false
        }
    }
}

/** Display name of the reading source. */
fun smartSourceName(source: String?, isWindows: Boolean): String = when {
    !source.isNullOrBlank() -> source
    isWindows -> "CrystalDiskInfo"
    else -> "smartctl"
}

/** Strips a trailing separator so "D:\" matches "D:" but "/" stays "/". */
internal fun normalizeVolume(s: String): String = s.trimEnd('\\', '/').ifEmpty { s }

/** The disk to show first: matched by id, else by letter / mountpoint, else the first. */
fun pickDisk(disks: List<SmartDisk>, id: String?, key: String?): String? {
    if (id != null) disks.firstOrNull { it.id == id }?.let { return it.id }
    if (!key.isNullOrBlank()) {
        val want = normalizeVolume(key)
        disks.firstOrNull { d -> d.letters.any { normalizeVolume(it).equals(want, ignoreCase = true) } }
            ?.let { return it.id }
    }
    return disks.firstOrNull()?.id
}

/** String form of a JSON value that may be a string or a number. */
internal fun JsonObject?.text(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.content.isNotEmpty() }?.content

internal fun JsonObject?.number(key: String): Double? = text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
