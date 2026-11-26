package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.model.WaCardDetail
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaFacetCount
import com.raavivi.sysmon.core.model.WaPickReq
import com.raavivi.sysmon.core.model.WaRewrite
import com.raavivi.sysmon.core.model.WaYourCard
import com.raavivi.sysmon.core.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The "Not here" button's key in [PickState] (found buttons are keyed by their result index). */
const val PICK_NOT_HERE = -1

/**
 * How the "Found it" / "Not here" buttons read: [saved] is the one that was saved (it reads "Saved", the
 * others reset), [failed] one whose save failed ("Not saved"), [busy] one in flight, [marked] the count of
 * queries marked either way.
 */
data class PickState(val saved: Int? = null, val failed: Int? = null, val busy: Int? = null, val marked: Int? = null)

/** Where the full-card sheet is: loading, showing the card, the result has no card (404), or the read failed. */
enum class CardPhase { LOADING, DONE, MISSING, ERROR }

/** The open full-card sheet: [index] is the result it shows; [detail] is set once [phase] is DONE. */
data class CardSheet(val index: Int, val phase: CardPhase = CardPhase.LOADING, val detail: WaCardDetail? = null)

/**
 * The Conversations mode of the Search view and the chat-search status behind it: the index and topic-card
 * lines (with their polling), the conversation results and the Found it / Not here verdicts. Port of the web
 * whatsapp-search-global.js / -index.js / -cards.js / -picks.js. The query is private and never logged.
 *
 * [onMessage] shows a short toast; [onStatusRead] is called after every status read (even a failed one), so
 * the Search view can settle which mode it is in.
 */
class ConvSearchState(
    private val repo: WhatsAppRepository,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
    private val onStatusRead: () -> Unit,
    private val prefs: SearchModePrefs = SearchModePrefs.None,
) {
    var status by mutableStateOf<WaChatSearchStatus?>(null)
        private set
    var results by mutableStateOf<List<WaConvResult>>(emptyList())
        private set

    /** The owner's own cards that matched, shown above [results] and never among them. */
    var yourCards by mutableStateOf<List<WaYourCard>>(emptyList())
        private set

    /** The query [results] answer: a pick names it, never the field's current text. */
    var resultsQ by mutableStateOf("")
        private set
    var degraded by mutableStateOf(false)
        private set
    /** The themes the results are filtered to (at most [MAX_THEMES]); they belong to [themeQuery]. */
    var selectedThemes by mutableStateOf<List<String>>(emptyList())
        private set

    /** How many conversations carry each theme, as the last answer counted them (most first). */
    var themeCounts by mutableStateOf<List<WaFacetCount>>(emptyList())
        private set

    /** A filtered search is running: the chips and the list stay on screen meanwhile. */
    var filtering by mutableStateOf(false)
        private set

    /** The "Up to 5 themes at once" note is showing; [capNoteSeq] moves with each refused press. */
    var capNote by mutableStateOf(false)
        private set
    var capNoteSeq by mutableStateOf(0)
        private set
    var phase by mutableStateOf(SearchPhase.IDLE)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pick by mutableStateOf(PickState())
        private set
    var indexBusy by mutableStateOf(false)
        private set
    var cardsBusy by mutableStateOf(false)
        private set

    /** The open full-card sheet, or null. Card text is private: it is only held here, never logged. */
    var cardSheet by mutableStateOf<CardSheet?>(null)
        private set

    /** Deep search is on: the server reads time, people and acts from the query and looks more carefully. Remembered. */
    @set:JvmName("writeDeep") // the private setter would clash with setDeep(on) on the JVM
    var deep by mutableStateOf(false)
        private set

    /** What the last deep answer read from the query; null for an instant answer. */
    var rewrite by mutableStateOf<WaRewrite?>(null)
        private set

    /** The results on screen came from a deep search. */
    val deepReply: Boolean get() = rewrite != null

    private var deepPicked = false
    private var themeQuery = ""
    private var viewOpen = false
    private var resultsEpoch = 0
    private var statusEpoch = 0
    private var cardEpoch = 0
    private var pollJob: Job? = null

    /** Conversations mode is offered when chat search is on and has node vectors to search. */
    val available: Boolean get() = status?.let { it.enabled && it.nodeVectors > 0 } == true

    /**
     * Read the status once when it is not known to be live, outside the Search view and without polling, so an
     * open chat can tell whether "Select messages" is on offer. A failed read changes nothing.
     */
    fun checkAvailable() {
        if (available) return
        scope.launch {
            val r = repo.chatSearchStatus()
            if (r is ApiResult.Ok) status = r.value
        }
    }

    init {
        // The remembered choice; a setDeep made before it lands wins. A query that already ran the other way runs again.
        scope.launch {
            val saved = runCatching { prefs.deep() }.getOrDefault(false)
            if (deepPicked || saved == deep) return@launch
            deep = saved
            if (themeQuery.isNotBlank()) run(themeQuery)
        }
    }

    /** Turn Deep on or off, remember it, and search the current query again (the whole list changes). */
    fun setDeep(on: Boolean) {
        if (on == deep) return
        deepPicked = true
        deep = on
        scope.launch { runCatching { prefs.saveDeep(on) } }
        if (themeQuery.isNotBlank()) run(themeQuery)
    }

    /** A card of the owner's own was deleted: it leaves the results. */
    fun removeYourCard(cardId: Long) {
        yourCards = yourCards.filter { it.cardId != cardId }
    }

    // -- status --------------------------------------------------------------------------------

    /** The Search view opened: read the status now and keep reading while a run goes. */
    fun open() {
        viewOpen = true
        refresh()
    }

    /** The Search view closed: stop polling. */
    fun close() {
        viewOpen = false
        statusEpoch++
        pollJob?.cancel()
    }

    private fun refresh() {
        scope.launch {
            read()
            schedule()
        }
    }

    private suspend fun read() {
        val mine = ++statusEpoch
        val r = repo.chatSearchStatus()
        if (mine != statusEpoch || !viewOpen) return
        if (r is ApiResult.Ok) status = r.value
        onStatusRead()
    }

    /** Poll only while the view is open and a run is going. */
    private fun schedule() {
        pollJob?.cancel()
        pollJob = null
        if (!viewOpen || !shouldPollAny(status, true)) return
        pollJob = scope.launch {
            delay(STATUS_POLL_MS)
            refresh()
        }
    }

    /** "Update" on the index line: show the run at once, then read the real numbers. */
    fun requestIndex() {
        if (indexBusy) return
        indexBusy = true
        scope.launch {
            when (repo.chatSearchIndex()) {
                is ApiResult.Ok -> {
                    status = status?.copy(runRequested = true)
                    read()
                    schedule()
                }
                is ApiResult.Err -> onMessage("Could not update the index")
            }
            indexBusy = false
        }
    }

    /** Run / Stop on the cards line: [running] is what the line shows now, so the button knows its verb. */
    fun toggleCards(running: Boolean) {
        if (cardsBusy) return
        val want = !running
        cardsBusy = true
        scope.launch {
            val r = if (want) repo.chatSearchCardsRun() else repo.chatSearchCardsStop()
            when (r) {
                is ApiResult.Ok -> {
                    status = status?.let { st -> st.copy(cards = st.cards?.copy(requested = want)) }
                    read()
                    schedule()
                }
                // A 409 says why cards cannot run (not configured): its words are about setup, not the query.
                is ApiResult.Err -> onMessage(if (r.code == 409) r.message else "Topic cards request failed")
            }
            cardsBusy = false
        }
    }

    // -- results -------------------------------------------------------------------------------

    /** Forget the results (and drop any request or pick in flight). */
    fun clear() {
        resultsEpoch++
        closeCard()
        results = emptyList()
        yourCards = emptyList()
        selectedThemes = emptyList()
        themeCounts = emptyList()
        themeQuery = ""
        filtering = false
        capNote = false
        resultsQ = ""
        degraded = false
        rewrite = null
        phase = SearchPhase.IDLE
        error = null
        pick = PickState()
    }

    /** Search [q]; the same query as the last one keeps its selected themes, a new one starts without. */
    fun run(q: String) {
        val keep = if (q == themeQuery) selectedThemes else emptyList()
        clear()
        if (q.isBlank()) return
        selectedThemes = keep
        themeQuery = q
        val mine = resultsEpoch
        phase = SearchPhase.LOADING
        val wantDeep = deep
        scope.launch { fetch(q, keep, mine, wantDeep) }
    }

    /** A chip was pressed: the theme joins or leaves the selection and the same query runs again with it. */
    fun pressTheme(name: String) {
        if (themeQuery.isBlank()) return
        capNote = false
        val t = toggleTheme(selectedThemes, name)
        if (t.capped) {
            capNote = true
            capNoteSeq++
            return
        }
        selectedThemes = t.selected
        rerun()
    }

    /** "Clear" on the chips: no theme selected, the same query runs again. */
    fun clearThemes() {
        if (themeQuery.isBlank() || selectedThemes.isEmpty()) return
        capNote = false
        selectedThemes = emptyList()
        rerun()
    }

    fun dismissCapNote() {
        capNote = false
    }

    /** The same query with the selection as it is now; the list stays on screen until the answer lands. */
    private fun rerun() {
        val q = themeQuery
        val mine = ++resultsEpoch
        closeCard()
        pick = PickState()
        filtering = true
        val themes = selectedThemes
        val wantDeep = deep
        scope.launch { fetch(q, themes, mine, wantDeep) }
    }

    private suspend fun fetch(q: String, themes: List<String>, mine: Int, wantDeep: Boolean) {
        val r = repo.chatSearch(q, CONV_LIMIT, themes, wantDeep)
        if (mine != resultsEpoch) return
        filtering = false
        when (r) {
            is ApiResult.Ok -> {
                results = r.value.results
                yourCards = r.value.yourCards
                themeCounts = cleanThemeCounts(r.value.facetCounts)
                resultsQ = q
                degraded = r.value.degraded == "keyword"
                rewrite = if (r.value.mode == "deep") r.value.rewrite ?: WaRewrite() else null
                error = null
                phase = SearchPhase.DONE
            }
            is ApiResult.Err -> {
                phase = SearchPhase.ERROR
                error = searchErrorText(r)
                // 404: the feature is off. Hide the mode; the Search view falls back to Messages.
                if (r.code == 404) {
                    status = WaChatSearchStatus()
                    onStatusRead()
                }
            }
        }
    }

    // -- full card ----------------------------------------------------------------------------

    /** Open the full-card sheet on result [index] and read its card. */
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

    /** Try the open sheet's card again after an error. */
    fun retryCard() {
        val sheet = cardSheet ?: return
        cardSheet = CardSheet(sheet.index)
        loadCard(sheet.index)
    }

    private fun loadCard(index: Int) {
        val node = results.getOrNull(index)?.nodeId ?: return
        val mine = resultsEpoch
        val mineCard = ++cardEpoch
        scope.launch {
            val r = repo.chatSearchCard(node)
            // The list, or the sheet, may have changed while this was in flight.
            if (mine != resultsEpoch || mineCard != cardEpoch) return@launch
            cardSheet = when (r) {
                is ApiResult.Ok -> CardSheet(index, CardPhase.DONE, r.value)
                is ApiResult.Err -> CardSheet(index, if (r.code == 404) CardPhase.MISSING else CardPhase.ERROR)
            }
        }
    }

    // -- picks ---------------------------------------------------------------------------------

    /** "Found it" on result [index]. */
    fun pickFound(index: Int) {
        val conv = results.getOrNull(index) ?: return
        send(index, foundPick(resultsQ, conv, rank = index + 1, shown = results.size))
    }

    /** "Not here": also available when a non-empty query found nothing. */
    fun pickNotHere() {
        if (resultsQ.isBlank()) return
        send(PICK_NOT_HERE, notHerePick(resultsQ, results.size))
    }

    private fun send(key: Int, body: WaPickReq) {
        if (pick.busy != null) return
        val mine = resultsEpoch
        pick = pick.copy(busy = key)
        scope.launch {
            val r = repo.chatSearchPick(body)
            // A new query may have replaced the list while this was in flight.
            if (mine != resultsEpoch) return@launch
            pick = when (r) {
                is ApiResult.Ok -> PickState(saved = key, marked = r.value.found + r.value.notHere)
                is ApiResult.Err -> pick.copy(busy = null, failed = key)
            }
        }
    }
}
