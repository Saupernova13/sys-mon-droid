package com.raavivi.sysmon.ui.modellog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raavivi.sysmon.AppContainer
import com.raavivi.sysmon.core.model.ModelLogRow
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.jsonWebSocketFlow
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

class ModelLogViewModel(private val container: AppContainer) : ViewModel() {

    var items by mutableStateOf<List<ModelLogRow>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var detail by mutableStateOf<ModelLogRow?>(null)
    var proxyRunning by mutableStateOf<Boolean?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    private var streamJob: Job? = null

    init {
        fetch()
        loadMeta()
        connect()
    }

    fun fetch() {
        loading = true
        error = null
        viewModelScope.launch {
            when (val r = safeCall { container.api.api.modelLog(limit = 200) }) {
                is ApiResult.Ok -> items = r.value.items
                is ApiResult.Err -> error = r.message
            }
            loading = false
        }
    }

    private fun loadMeta() {
        viewModelScope.launch {
            (safeCall { container.api.api.modelMeta() } as? ApiResult.Ok)?.let {
                proxyRunning = it.value.proxy?.running
            }
        }
    }

    private fun connect() {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            container.api.jsonWebSocketFlow("/ws/models", ModelLogRow.serializer())
                .retryWhen { _, _ -> delay(3000); true }
                .collect { row ->
                    items = (listOf(row) + items).distinctBy { it.id }.take(300)
                }
        }
    }

    fun clear() {
        viewModelScope.launch {
            message = when (val r = safeCall { container.api.api.modelClear() }) {
                is ApiResult.Ok -> "Cleared ${r.value.cleared} rows"
                is ApiResult.Err -> "Clear failed: ${r.message}"
            }
            fetch()
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
