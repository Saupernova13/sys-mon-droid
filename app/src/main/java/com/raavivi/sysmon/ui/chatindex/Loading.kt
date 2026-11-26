package com.raavivi.sysmon.ui.chatindex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.chatindex.isChatSearchOff
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where one read is: not asked for, being read, read, failed, or the row is not there (a 404 that is not "off"). */
enum class LoadPhase { IDLE, LOADING, READY, ERROR, MISSING }

/**
 * One read shown on screen. A reply for a read that was since restarted or cancelled is dropped. A 404 "chat
 * search is off" is handed to [onOff] (the whole screen then says so); any other failure keeps its text.
 */
class Loadable<T>(
    private val scope: CoroutineScope,
    private val onOff: () -> Unit,
    private val onLoaded: (T) -> Unit = {},
    private val fetch: suspend () -> ApiResult<T>,
) {
    var value by mutableStateOf<T?>(null)
        private set
    var phase by mutableStateOf(LoadPhase.IDLE)
        private set
    var error by mutableStateOf("")
        private set
    private var epoch = 0

    fun load() {
        val mine = ++epoch
        phase = LoadPhase.LOADING
        error = ""
        scope.launch {
            val r = fetch()
            if (mine != epoch) return@launch
            when (r) {
                is ApiResult.Ok -> {
                    value = r.value
                    phase = LoadPhase.READY
                    onLoaded(r.value)
                }
                is ApiResult.Err -> {
                    if (isChatSearchOff(r)) onOff()
                    error = loadErrorText(r)
                    phase = if (r.code == 404 && !isChatSearchOff(r)) LoadPhase.MISSING else LoadPhase.ERROR
                }
            }
        }
    }

    /** Show [v] as what was read (a saved edit answers with the card itself); a read in flight is dropped. */
    fun set(v: T) {
        epoch++
        value = v
        phase = LoadPhase.READY
        error = ""
    }

    /** Drop whatever is in flight and forget what was read. */
    fun reset() {
        epoch++
        value = null
        phase = LoadPhase.IDLE
        error = ""
    }
}

/**
 * A list read page by page. [reload] reads the first page; [loadMore] reads the page after the last one
 * (`next`). Rows merge by [key] so a repeated row never shows twice. A failed "more" keeps the rows and sets
 * [moreFailed] so the button can offer a retry.
 */
class PagedList<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> Any,
    private val onOff: () -> Unit,
    private val fetch: suspend (offset: Int) -> ApiResult<PageOf<T>>,
) {
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var next by mutableStateOf<Int?>(null)
        private set
    var phase by mutableStateOf(LoadPhase.IDLE)
        private set
    var error by mutableStateOf("")
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var moreFailed by mutableStateOf(false)
        private set
    private var epoch = 0

    fun reload() {
        val mine = ++epoch
        items = emptyList()
        next = null
        phase = LoadPhase.LOADING
        error = ""
        loadingMore = false
        moreFailed = false
        scope.launch {
            val r = fetch(0)
            if (mine != epoch) return@launch
            when (r) {
                is ApiResult.Ok -> {
                    val merged = mergePage(items, r.value, 0, key)
                    items = merged.items
                    next = merged.next
                    phase = LoadPhase.READY
                }
                is ApiResult.Err -> {
                    if (isChatSearchOff(r)) onOff()
                    error = loadErrorText(r)
                    phase = if (r.code == 404 && !isChatSearchOff(r)) LoadPhase.MISSING else LoadPhase.ERROR
                }
            }
        }
    }

    fun loadMore() {
        val offset = next ?: return
        if (loadingMore || phase != LoadPhase.READY) return
        val mine = epoch
        loadingMore = true
        moreFailed = false
        scope.launch {
            val r = fetch(offset)
            if (mine != epoch) return@launch
            loadingMore = false
            when (r) {
                is ApiResult.Ok -> {
                    val merged = mergePage(items, r.value, offset, key)
                    items = merged.items
                    next = merged.next
                }
                is ApiResult.Err -> {
                    if (isChatSearchOff(r)) onOff()
                    moreFailed = true
                }
            }
        }
    }

    /** Change the rows already read (a saved edit moved a title), keeping their place and the paging. */
    fun update(f: (T) -> T) {
        items = items.map(f)
    }

    /** Drop whatever is in flight. */
    fun cancel() {
        epoch++
        loadingMore = false
    }
}
