package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.model.WaTagCount
import com.raavivi.sysmon.core.model.WaTagFacet
import com.raavivi.sysmon.core.model.WaTagResult
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Where the Tags view is: not asked yet, in flight, answered, failed, or chat search is off (404). */
enum class TagsPhase { IDLE, LOADING, DONE, ERROR, OFF }

/**
 * The Tags mode of the Search view: browse and filter every conversation by the tags its topic card carries,
 * with no model involved. It owns the selection (must have / any of / exclude), the filters, the type-ahead,
 * the results with their paging and facets, and the full-card sheet. Every change re-runs the query after a
 * short debounce from offset 0; a reply is kept only when nothing newer was asked for since (the epoch
 * guards). Port of the web whatsapp-tags.js. Tags, typed text and card text are private: only ever held
 * here, never logged.
 *
 * [onOff] is called when the server says chat search is off, so the Search view can leave this mode.
 */
class TagsSearchState(
    private val repo: WhatsAppRepository,
    private val scope: CoroutineScope,
    private val onOff: () -> Unit = {},
) {
    var selection by mutableStateOf(TagSelection())
        private set
    var filters by mutableStateOf(TagFilters())
        private set

    /** The category chip ("" = All): narrows the type-ahead and the browse lists. */
    var cat by mutableStateOf("")
        private set

    /** The group a picked tag (suggestion, facet or result chip) goes into. */
    var addGroup by mutableStateOf(TagGroup.ALL)
        private set

    /** The search field's text while this mode is on. */
    var input by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<WaTagCount>>(emptyList())
        private set
    var suggestLoading by mutableStateOf(false)
        private set

    var results by mutableStateOf<List<WaTagResult>>(emptyList())
        private set
    var total by mutableIntStateOf(0)
        private set
    var facets by mutableStateOf<Map<String, List<WaTagFacet>>>(emptyMap())
        private set
    var phase by mutableStateOf(TagsPhase.IDLE)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var more by mutableStateOf(false)
        private set

    /** Chats seen in results so far, for the chat filter: jid to name. */
    var knownChats by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Categories whose browse list shows more than the first twelve. */
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The open full-card sheet (index into [results]), or null. Card text is private: held here, never logged. */
    var cardSheet by mutableStateOf<CardSheet?>(null)
        private set

    private var started = false
    private var queryEpoch = 0
    private var listEpoch = 0
    private var suggestEpoch = 0
    private var cardEpoch = 0
    private var queryJob: Job? = null
    private var suggestJob: Job? = null

    /** Chat search is on but answered 404 here: the mode is not offered. */
    val off: Boolean get() = phase == TagsPhase.OFF

    // -- the view ------------------------------------------------------------------------------

    /** The mode opened: the first time it loads the browse view; a failed one tries again. */
    fun start() {
        if (started && phase != TagsPhase.ERROR && phase != TagsPhase.OFF) return
        started = true
        queryJob?.cancel()
        run()
    }

    /** The mode closed: a pending type-ahead is dropped (a pending query still lands, so the list is current on return). */
    fun stop() {
        suggestJob?.cancel()
        suggestEpoch++
        suggestLoading = false
        closeCard()
    }

    // -- the query -----------------------------------------------------------------------------

    /** Ask again at once (the Retry button). */
    fun retry() {
        queryJob?.cancel()
        run()
    }

    private fun run() {
        val mine = ++queryEpoch
        listEpoch++
        closeCard()
        phase = TagsPhase.LOADING
        error = null
        loadingMore = false
        scope.launch {
            val r = repo.chatSearchTags(buildTagsQuery(selection, filters, 0))
            if (mine != queryEpoch) return@launch
            when (r) {
                is ApiResult.Ok -> {
                    results = r.value.results
                    total = r.value.total
                    facets = r.value.facets
                    more = hasMoreTags(results.size, total, results.size)
                    knownChats = noteChats(knownChats, results)
                    phase = TagsPhase.DONE
                }
                is ApiResult.Err -> {
                    if (r.code == 404) {
                        phase = TagsPhase.OFF
                        onOff()
                    } else {
                        phase = TagsPhase.ERROR
                        error = searchErrorText(r)
                    }
                }
            }
        }
    }

    /** The next page of the same query, appended to the list. */
    fun loadMore() {
        if (loadingMore || !more || phase != TagsPhase.DONE) return
        val mine = ++queryEpoch
        val offset = results.size
        loadingMore = true
        scope.launch {
            val r = repo.chatSearchTags(buildTagsQuery(selection, filters, offset))
            if (mine != queryEpoch) return@launch
            loadingMore = false
            when (r) {
                is ApiResult.Ok -> {
                    results = results + r.value.results
                    total = r.value.total
                    more = hasMoreTags(results.size, total, r.value.results.size)
                    knownChats = noteChats(knownChats, r.value.results)
                }
                // The list stays as it is; scrolling to the end tries again.
                is ApiResult.Err -> Unit
            }
        }
    }

    /** The selection or a filter changed: settle the sort, drop the open card and ask again shortly. */
    private fun changed() {
        if (filters.sort == TagSort.MATCHES && selection.any.isEmpty()) filters = filters.copy(sort = TagSort.NEWEST)
        closeCard()
        queryJob?.cancel()
        // The old answer must never land once the question changed, even before the debounce fires.
        queryEpoch++
        loadingMore = false
        queryJob = scope.launch {
            delay(TAGS_QUERY_MS)
            run()
        }
    }

    // -- the selection -------------------------------------------------------------------------

    /** Choose which group the next picked tag goes into. */
    fun chooseAddGroup(g: TagGroup) {
        addGroup = g
    }

    /** Add [tag] to [group] (the "Tap adds" choice by default) and ask again. */
    fun add(tag: TagPick, group: TagGroup = addGroup) {
        selection = selection.with(tag, group)
        changed()
    }

    fun cycle(tag: TagPick) {
        selection = selection.cycled(tag)
        changed()
    }

    fun remove(tag: TagPick) {
        selection = selection.without(tag)
        changed()
    }

    fun clearSelection() {
        if (selection.isEmpty) return
        selection = TagSelection()
        changed()
    }

    // -- filters -------------------------------------------------------------------------------

    private fun filter(next: TagFilters) {
        if (next == filters) return
        filters = next
        changed()
    }

    fun setChat(jid: String) = filter(filters.copy(chat = jid))
    fun setSince(day: LocalDate?) = filter(filters.copy(since = day))
    fun setUntil(day: LocalDate?) = filter(filters.copy(until = day))
    fun setMinIntensity(n: Int) = filter(filters.copy(minIntensity = n))
    fun setUnresolved(on: Boolean) = filter(filters.copy(unresolved = on))
    fun setSort(sort: TagSort) = filter(filters.copy(sort = sort))
    fun resetFilters() = filter(TagFilters())

    // -- browse and type-ahead -----------------------------------------------------------------

    /** The category chip: narrows the browse list and the type-ahead (which asks again at once). */
    fun chooseCat(next: String) {
        if (next == cat) return
        cat = next
        if (input.isNotBlank()) suggestNow()
    }

    /** "Show more" on a category's browse list. */
    fun expand(catId: String) {
        expanded = expanded + catId
    }

    /** The field changed: the type-ahead asks after a short quiet. */
    fun onInput(text: String) {
        input = text
        suggestJob?.cancel()
        if (text.isBlank()) {
            suggestEpoch++
            suggestions = emptyList()
            suggestLoading = false
            return
        }
        suggestLoading = true
        suggestJob = scope.launch {
            delay(TAGS_SUGGEST_MS)
            suggest(text.trim())
        }
    }

    private fun suggestNow() {
        suggestJob?.cancel()
        suggestLoading = true
        suggestJob = scope.launch { suggest(input.trim()) }
    }

    private suspend fun suggest(q: String) {
        if (q.isEmpty()) return
        val mine = ++suggestEpoch
        val r = repo.chatSearchTagSuggest(cat, q, SUGGEST_LIMIT)
        if (mine != suggestEpoch) return
        suggestLoading = false
        suggestions = when (r) {
            is ApiResult.Ok -> r.value.tags.filter { selection.groupOf(TagPick(it.cat, it.value)) == null }
            is ApiResult.Err -> emptyList()
        }
    }

    /** A suggestion was tapped (or the IME's Search key took the first one): add it and empty the field. */
    fun pickSuggestion(tag: WaTagCount) {
        clearInput()
        add(TagPick(tag.cat, tag.value, tag.label.ifBlank { tag.value }))
    }

    /** The IME's Search key: take the first suggestion, if any. */
    fun submitInput() {
        suggestions.firstOrNull()?.let(::pickSuggestion)
    }

    /** Empty the field and its suggestions, dropping any type-ahead in flight. */
    fun clearInput() {
        suggestJob?.cancel()
        suggestEpoch++
        input = ""
        suggestions = emptyList()
        suggestLoading = false
    }

    // -- full card -----------------------------------------------------------------------------

    /** Open the full-card sheet on result [index] and read its card (by node, or by card id when the node is gone). */
    fun openCard(index: Int) {
        if (results.getOrNull(index) == null) return
        cardSheet = CardSheet(index)
        loadCard(index)
    }

    /** Close the sheet; a reply still in flight is dropped. */
    fun closeCard() {
        cardEpoch++
        cardSheet = null
    }

    fun retryCard() {
        val sheet = cardSheet ?: return
        cardSheet = CardSheet(sheet.index)
        loadCard(sheet.index)
    }

    private fun loadCard(index: Int) {
        val r = results.getOrNull(index) ?: return
        val mine = listEpoch
        val mineCard = ++cardEpoch
        scope.launch {
            val reply = r.nodeId?.let { repo.chatSearchCard(it) } ?: repo.chatSearchCardById(r.cardId)
            if (mine != listEpoch || mineCard != cardEpoch) return@launch
            cardSheet = when (reply) {
                is ApiResult.Ok -> CardSheet(index, CardPhase.DONE, reply.value)
                is ApiResult.Err -> CardSheet(index, if (reply.code == 404) CardPhase.MISSING else CardPhase.ERROR)
            }
        }
    }
}
