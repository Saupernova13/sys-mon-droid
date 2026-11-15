package com.raavivi.sysmon.ui.sites

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.SitesResponse
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.launch

/** Loads `GET /api/sites`. The screen re-calls [refresh] every 30 s while visible. */
class SitesViewModel(private val container: AppContainer) : ViewModel() {

    var data by mutableStateOf<SitesResponse?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            when (val r = safeCall { container.api.api.sites() }) {
                is ApiResult.Ok -> {
                    data = r.value
                    error = null
                }
                is ApiResult.Err -> error = r.message
            }
            loading = false
        }
    }
}
