package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.model.WaCardDetail
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaPickReq
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
) {
    var status by mutableStateOf<WaChatSearchStatus?>(null)
        private set
    var results by mutableStateOf<List<WaConvResult>>(emptyList())
        private set

    /** The query [results] answer: a pick names it, never the field's current text. */
    var resultsQ by mutableStateOf("")
        private set
    var degraded by mutableStateOf(false)
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

    /** The Card settings sheet, opened from the topic-cards line; a save re-reads the status. */
    val cardSettings = CardSettingsState(repo, scope, onSaved = { refresh() }, onMessage = onMessage)

    private var viewOpen = false
    private var resultsEpoch = 0
    private var statusEpoch = 0
    private var cardEpoch = 0
    private var pollJob: Job? = null

    /** Conversations mode is offered when chat search is on and has node vectors to search. */
    val available: Boolean get() = status?.let { it.enabled && it.nodeVectors > 0 } == true

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
        resultsQ = ""
        degraded = false
        phase = SearchPhase.IDLE
        error = null
        pick = PickState()
    }

    fun run(q: String) {
        clear()
        if (q.isBlank()) return
        val mine = resultsEpoch
        phase = SearchPhase.LOADING
        scope.launch {
            val r = repo.chatSearch(q, CONV_LIMIT)
            if (mine != resultsEpoch) return@launch
            when (r) {
                is ApiResult.Ok -> {
                    results = r.value.results
                    resultsQ = q
                    degraded = r.value.degraded == "keyword"
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
