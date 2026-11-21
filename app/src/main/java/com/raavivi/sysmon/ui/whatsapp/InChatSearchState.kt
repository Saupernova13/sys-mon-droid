package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.model.WaSearchRow
import com.raavivi.sysmon.core.model.WaSearchTerm
import com.raavivi.sysmon.core.model.WaTimelineDay
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The message you see when a search fails for a reason that is not the user's input. */
const val SEARCH_FAILED = "Search failed"

/** The in-chat counter when the query matched nothing. */
const val NO_MATCHES = "No matches"

/** A failed search as the text to show: the server's own words for a 400 (they are about the input), else a plain failure. */
/** Whether a shown search error is worth a Retry: a rejected query (the server's own words) is not. */
fun isRetryableSearchError(text: String?): Boolean = text == SEARCH_FAILED

fun searchErrorText(e: ApiResult.Err): String = if (e.code == 400) e.message else SEARCH_FAILED

/**
 * The in-chat search bar: this chat only, older / newer stepping with a hit counter. It owns the query, the
 * debounce, the request epoch (a stale response never renders), the loaded hits and the selection; showing a
 * hit is the caller's job ([showHit]). While it has results it also exposes the server's [terms] (to highlight
 * in message text) and the [days] that have hits (to mark on the scrubber). Port of the web
 * whatsapp-search-chat.js. The query is private and never logged.
 */
class InChatSearchState(
    private val repo: WhatsAppRepository,
    private val scope: CoroutineScope,
    private val currentChat: () -> String?,
    private val showHit: (WaSearchRow) -> Unit,
) {
    var open by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var hits by mutableStateOf<List<WaSearchRow>>(emptyList())
        private set
    var total by mutableIntStateOf(0)
        private set
    var capped by mutableStateOf(false)
        private set
    var next by mutableStateOf<Int?>(null)
        private set

    /** -1: results loaded, nothing selected yet. Hits run newest first, so a higher index is older. */
    var sel by mutableIntStateOf(-1)
        private set
    var terms by mutableStateOf<List<WaSearchTerm>>(emptyList())
        private set

    /** Days of this chat with hits, for the scrubber; null until that second read lands (the bar works without it). */
    var days by mutableStateOf<List<WaTimelineDay>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The current query has been answered (so zero hits can say "No matches"). */
    var searched by mutableStateOf(false)
        private set

    private var epoch = 0
    private var stepping = false
    private var debounce: Job? = null

    /** The counter text, or the failure that replaced it. */
    val countLabel: String
        get() = error ?: if (searched && hits.isEmpty()) NO_MATCHES else countText(total, capped, hits.size, sel)
    val buttons: StepButtons get() = stepButtons(sel, hits.size, next != null)

    fun toggle() { if (open) close() else openBar() }

    fun openBar() { if (currentChat() != null) open = true }

    /** Close the bar and drop everything it loaded (also called when the open chat changes). */
    fun close() {
        epoch++
        debounce?.cancel()
        open = false
        query = ""
        reset()
    }

    fun onQueryChange(text: String) {
        query = text
        debounce?.cancel()
        debounce = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            run(null)
        }
    }

    /** Carry on in the bar with a query from elsewhere, selecting hit [seekId] once the results are in. */
    fun continueWith(q: String, seekId: String?) {
        openBar()
        query = q
        debounce?.cancel()
        scope.launch { run(seekId) }
    }

    /** One step at a time: a tap while a page is still loading must not fire again from a stale index. */
    fun step(d: Int) {
        if (stepping) return
        stepping = true
        scope.launch {
            try {
                val target = stepTarget(sel, d, hits.size, next != null) ?: return@launch
                if (!ensurePage(target)) return@launch
                sel = minOf(hits.size - 1, target)
                showHit(hits[sel])
            } finally {
                stepping = false
            }
        }
    }

    private fun reset() {
        sel = -1
        hits = emptyList()
        total = 0
        capped = false
        next = null
        terms = emptyList()
        days = null
        error = null
        searched = false
    }

    private suspend fun run(seekId: String?) {
        val q = query.trim()
        val chat = currentChat()
        val mine = ++epoch
        reset()
        if (q.isEmpty() || chat == null) return
        when (val r = repo.search(q = q, chat = chat, limit = SEARCH_PAGE)) {
            is ApiResult.Err -> if (mine == epoch) error = searchErrorText(r)
            is ApiResult.Ok -> {
                if (mine != epoch || chat != currentChat()) return
                val page = r.value
                searched = true
                hits = page.results
                total = if (page.total > 0) page.total else hits.size
                capped = page.totalCapped
                next = page.nextOffset
                terms = page.terms
                // The scrubber marks are a second read; the bar works without them.
                scope.launch {
                    val d = repo.searchDays(q, chat)
                    if (d is ApiResult.Ok && mine == epoch && chat == currentChat()) days = d.value
                }
                if (seekId != null) seekHit(seekId)
            }
        }
    }

    /** Fetch the pages needed to make index [i] reachable; false when the query or chat changed meanwhile or a page failed. */
    private suspend fun ensurePage(i: Int): Boolean {
        val mine = epoch
        val q = query.trim()
        val chat = currentChat()
        while (i >= hits.size && next != null) {
            val r = repo.search(q = q, chat = chat, limit = SEARCH_PAGE, offset = next)
            if (mine != epoch || chat != currentChat()) return false
            if (r !is ApiResult.Ok) return false
            hits = hits + r.value.results
            next = r.value.nextOffset
        }
        return true
    }

    /** Select the hit on message [id], paging further hits in until it is found, the results end or [MAX_SEEK_PAGES] are loaded. */
    private suspend fun seekHit(id: String) {
        val mine = epoch
        var at = hits.indexOfFirst { it.msgId == id }
        var pages = 1
        while (at < 0 && next != null && pages < MAX_SEEK_PAGES) {
            if (!ensurePage(hits.size)) return
            if (mine != epoch) return
            at = hits.indexOfFirst { it.msgId == id }
            pages++
        }
        if (at >= 0) sel = at
    }
}
