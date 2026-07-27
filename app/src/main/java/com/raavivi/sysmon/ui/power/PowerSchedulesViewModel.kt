package com.raavivi.sysmon.ui.power

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.PowerSchedule
import com.raavivi.sysmon.core.model.PowerSchedulesBody
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.launch

/**
 * Editor state for one plug's on/off windows.
 *
 * [rows] is a local draft: edits stay here until [save] posts them, because the
 * server endpoint replaces a plug's whole schedule set in one call rather than
 * patching individual windows. [dirty] tracks whether the draft has diverged
 * from what the server last returned.
 */
class PowerSchedulesViewModel(private val container: AppContainer) : ViewModel() {

    var rows by mutableStateOf<List<PowerSchedule>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    private var plug: String = ""
    private var saved: List<PowerSchedule> = emptyList()

    val dirty: Boolean get() = rows != saved

    /** Load the windows for [idOrBlank]; blank means "nothing editable here". */
    fun setPlug(idOrBlank: String) {
        if (idOrBlank == plug) return
        plug = idOrBlank
        rows = emptyList()
        saved = emptyList()
        error = null
        message = null
        if (idOrBlank.isNotEmpty()) load()
    }

    fun load() {
        val target = plug
        if (target.isEmpty()) return
        loading = true
        error = null
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.powerSchedules() }) {
                is ApiResult.Ok -> {
                    if (target != plug) return@launch
                    // The endpoint returns every plug's windows; keep ours.
                    val mine = r.value.schedules.filter { it.plug == target }
                    rows = mine
                    saved = mine
                }
                is ApiResult.Err -> if (target == plug) error = r.message
            }
            loading = false
        }
    }

    fun addRow() {
        rows = rows + PowerSchedule(plug = plug)
    }

    fun removeRow(index: Int) {
        rows = rows.filterIndexed { i, _ -> i != index }
    }

    fun updateRow(index: Int, transform: (PowerSchedule) -> PowerSchedule) {
        rows = rows.mapIndexed { i, row -> if (i == index) transform(row) else row }
    }

    /** Flip one weekday on a row; the backend takes an explicit index list. */
    fun toggleDay(index: Int, day: Int) = updateRow(index) { row ->
        val days = if (day in row.days) row.days - day else (row.days + day).sorted()
        row.copy(days = days)
    }

    /**
     * Reject what the server would silently drop, so a window can't disappear
     * on save: it rejects rows whose times don't parse, and a zero-length
     * window would never fire.
     */
    private fun validate(): String? {
        rows.forEach { row ->
            if (!TIME.matches(row.on) || !TIME.matches(row.off)) {
                return "Each window needs an on and an off time in HH:MM."
            }
            if (row.on == row.off) return "A window's on and off times must differ."
        }
        return null
    }

    fun save() {
        if (plug.isEmpty() || saving) return
        validate()?.let {
            error = it
            return
        }
        saving = true
        error = null
        val target = plug
        val payload = rows.map { it.copy(plug = target) }
        viewModelScope.launch {
            when (val r = safeCall {
                container.api.api.setPowerSchedules(PowerSchedulesBody(target, payload))
            }) {
                is ApiResult.Ok -> {
                    val mine = r.value.schedules.filter { it.plug == target }
                    rows = mine
                    saved = mine
                    message = "Schedule saved"
                }
                is ApiResult.Err -> error = when (r.code) {
                    403 -> "Switching schedules requires an admin account."
                    else -> r.message
                }
            }
            saving = false
        }
    }

    private companion object {
        val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    }
}
