package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.model.WaSearchRow
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where a Search-view query is: nothing asked, in flight, answered, or failed. */
enum class SearchPhase { IDLE, LOADING, DONE, ERROR }

/**
 * The Messages mode of the Search view: keyword hits over every chat with the filter chips, grouped by chat
 * and paged. A request epoch keeps a stale response from ever rendering (web `gEpoch`). The query itself
 * lives in [WhatsAppSearch]; it is private and never logged.
 */
class MessageSearchState(
    private val repo: WhatsAppRepository,
    private val scope: CoroutineScope,
) {
    var chips by mutableStateOf(ChipSel())
        private set
    var hits by mutableStateOf<List<WaSearchRow>>(emptyList())
        private set
    var nextOffset by mutableStateOf<Int?>(null)
        private set
    var phase by mutableStateOf(SearchPhase.IDLE)
        private set

    /** Why the last query failed, in words fit to show. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Chat jids whose group shows all its rows. */
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set
    var loadingMore by mutableStateOf(false)
        private set

    private var epoch = 0

    /** Forget the results (and drop any request in flight). */
    fun clear() {
        epoch++
        hits = emptyList()
        nextOffset = null
        phase = SearchPhase.IDLE
        error = null
        loadingMore = false
    }

    /** A new query: the first page replaces the results. */
    fun run(q: String) {
        clear()
        if (q.isBlank()) return
        val mine = epoch
        val f = chipFilters(chips)
        phase = SearchPhase.LOADING
        scope.launch {
            val r = repo.search(q = q, fromMe = f.fromMe, media = f.media, limit = SEARCH_PAGE)
            if (mine != epoch) return@launch
            when (r) {
                is ApiResult.Ok -> {
                    hits = r.value.results
                    nextOffset = r.value.nextOffset
                    phase = SearchPhase.DONE
                }
                is ApiResult.Err -> {
                    error = searchErrorText(r)
                    phase = SearchPhase.ERROR
                }
            }
        }
    }

    /** The next page of the same query, appended (same epoch: a page, not a new query). */
    fun more(q: String) {
        val offset = nextOffset ?: return
        if (q.isBlank() || loadingMore) return
        val mine = epoch
        val f = chipFilters(chips)
        loadingMore = true
        scope.launch {
            val r = repo.search(q = q, fromMe = f.fromMe, media = f.media, limit = SEARCH_PAGE, offset = offset)
            if (mine != epoch) return@launch
            loadingMore = false
            if (r is ApiResult.Ok) {
                hits = hits + r.value.results
                nextOffset = r.value.nextOffset
            }
        }
    }

    /** Press a chip (null is "All"); the caller re-runs the query. */
    fun press(chip: SearchChip?) {
        chips = pressChip(chips, chip)
    }

    fun expand(chat: String) {
        expanded = expanded + chat
    }
}
