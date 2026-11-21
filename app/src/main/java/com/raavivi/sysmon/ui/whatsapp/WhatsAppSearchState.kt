package com.raavivi.sysmon.ui.whatsapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.model.WaSearchRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Recent queries kept for the empty Search view. */
const val MAX_RECENT = 8

/**
 * Everything the WhatsApp searches keep, owned by the view model so it survives opening a hit and coming
 * back: the Search view (open flag, the query, its mode, the Messages and Conversations results), the
 * in-chat bar, and the node range a tapped conversation highlights in its chat. The query is private and
 * never logged.
 *
 * [currentChat] is the open chat's jid; [showHit] brings a hit of that chat into view; [onMessage] shows a toast.
 */
class WhatsAppSearch(
    repo: WhatsAppRepository,
    private val scope: CoroutineScope,
    currentChat: () -> String?,
    showHit: (WaSearchRow) -> Unit,
    onMessage: (String) -> Unit,
) {
    /** The Search view is showing (or a conversation opened from it is, and Back returns to it). */
    var open by mutableStateOf(false)
        private set

    /** The query field's text, shared by both modes. */
    var query by mutableStateOf("")
        private set

    /** The mode the user picked; null until they pick one (Conversations is the default when offered). */
    private var picked by mutableStateOf<SearchMode?>(null)

    /** Message-time range of a tapped conversation, tinted in its chat; null otherwise. */
    var rangeHighlight by mutableStateOf<LongRange?>(null)

    val messages = MessageSearchState(repo, scope)
    val conv = ConvSearchState(repo, scope, onMessage) { reconcile() }
    val inChat = InChatSearchState(repo, scope, currentChat, showHit)

    /** Queries of this session, newest first. In memory only, never persisted: query text is private and backups are on. */
    var recent by mutableStateOf<List<String>>(emptyList())
        private set

    private var debounce: Job? = null

    /** The mode in force: Conversations needs chat search on with something indexed. */
    val mode: SearchMode get() = if (conv.available) picked ?: SearchMode.CONVERSATIONS else SearchMode.MESSAGES

    /** The mode the current results answer, so a mode that changed under a query re-runs it. */
    private var ranMode: SearchMode? = null

    /** Open the Search view; the screen calls [viewShown] once it is composed. */
    fun openView() {
        open = true
    }

    /** Open the Search view on [text] and run it at once (the chat filter's way in). */
    fun openFor(text: String) {
        noteQuery(text)
        open = true
        query = text
        debounce?.cancel()
        if (conv.status != null) {
            runQuery()
        } else {
            // The mode is unknown until the first status read lands; reconcile() runs the query then.
            ranMode = null
        }
    }

    fun closeView() {
        open = false
        debounce?.cancel()
    }

    /** The Search view is on screen: read the status now and keep reading while a run goes. */
    fun viewShown() = conv.open()

    /** The Search view left the screen (closed, or a hit opened its chat): stop polling. */
    fun viewHidden() = conv.close()

    fun onQueryChange(text: String) {
        query = text
        debounce?.cancel()
        debounce = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runQuery()
        }
    }

    /** Run the query now (the IME's Search key skips the debounce). */
    fun submit() {
        noteQuery(query)
        debounce?.cancel()
        runQuery()
    }

    fun setMode(m: SearchMode) {
        if (!conv.available) return
        picked = m
        debounce?.cancel()
        runQuery()
    }

    fun pressChip(chip: SearchChip?) {
        messages.press(chip)
        debounce?.cancel()
        runQuery()
    }

    fun loadMore() = messages.more(query.trim())

    /** Remember a query the user committed to (submitted, picked, or opened a result from). */
    fun noteQuery(q: String) {
        val t = q.trim()
        if (t.length < SEARCH_MIN_CHARS) return
        recent = (listOf(t) + recent.filterNot { it.equals(t, ignoreCase = true) }).take(MAX_RECENT)
    }

    fun clearRecent() {
        recent = emptyList()
    }

    /** The open chat changed (or closed): the bar closes and a conversation's range is forgotten. */
    fun onChatChanged() {
        inChat.close()
        rangeHighlight = null
    }

    private fun runQuery() {
        val q = query.trim()
        val m = mode
        ranMode = m
        // Only the mode in force keeps results; the other clears so a switch never shows a stale list.
        if (m == SearchMode.CONVERSATIONS) {
            messages.clear()
            conv.run(q)
        } else {
            conv.clear()
            messages.run(q)
        }
    }

    /** The status landed (or the feature turned out to be off): re-run a query whose mode is no longer the one it ran in. */
    private fun reconcile() {
        if (query.isNotBlank() && mode != ranMode) runQuery()
    }
}
