package com.raavivi.sysmon.ui.power

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.PowerCalendarResponse
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import java.util.Calendar
import kotlinx.coroutines.launch

/**
 * Month-at-a-time power rollup for the Calendar tab, mirroring the web UI's.
 *
 * The month cursor lives here rather than in the response so paging stays
 * responsive while a fetch is in flight, and it never advances past the current
 * month — there is no future data to show.
 */
class PowerCalendarViewModel(private val container: AppContainer) : ViewModel() {

    var data by mutableStateOf<PowerCalendarResponse?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The plug the grid covers: [PowerViewModel.ALL] or a plug id. */
    private var plug: String = PowerViewModel.ALL

    var year by mutableStateOf(0)
        private set
    var month by mutableStateOf(0) // 1-based
        private set

    init {
        val now = Calendar.getInstance()
        year = now.get(Calendar.YEAR)
        month = now.get(Calendar.MONTH) + 1
    }

    val monthParam: String get() = "%04d-%02d".format(year, month)

    /** True when the cursor is on the current month — paging forward is blocked. */
    val atCurrentMonth: Boolean
        get() {
            val now = Calendar.getInstance()
            return year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH) + 1
        }

    /** Point the grid at a plug (or the aggregate) and reload if it changed. */
    fun setPlug(idOrAll: String, force: Boolean = false) {
        if (!force && idOrAll == plug && data != null) return
        plug = idOrAll
        load()
    }

    fun step(delta: Int) {
        var y = year
        var m = month + delta
        if (m < 1) {
            m = 12
            y -= 1
        }
        if (m > 12) {
            m = 1
            y += 1
        }
        val now = Calendar.getInstance()
        // Never page into a future month — the rollup would always be empty.
        if (y > now.get(Calendar.YEAR) || (y == now.get(Calendar.YEAR) && m > now.get(Calendar.MONTH) + 1)) return
        year = y
        month = m
        load()
    }

    fun load() {
        loading = true
        error = null
        val requested = monthParam
        val requestedPlug = plug
        viewModelScope.launch {
            val r = safeCall {
                container.api.api.powerCalendar(
                    month = requested,
                    plug = requestedPlug.takeIf { it != PowerViewModel.ALL },
                )
            }
            // A slow response for a month the user has already paged away from
            // must not overwrite what they're looking at now.
            if (requested != monthParam || requestedPlug != plug) return@launch
            when (r) {
                is ApiResult.Ok -> data = r.value
                is ApiResult.Err -> error = r.message
            }
            loading = false
        }
    }
}
