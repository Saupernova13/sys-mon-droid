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

/** Where the chosen search mode is remembered between launches. A failure to read or write just forgets it. */
interface SearchModePrefs {
    suspend fun load(): String?
    suspend fun save(key: String)

    /** Whether the Tags how-to has been shown (and folded) before; a new user sees it expanded. */
    suspend fun tagsGuideSeen(): Boolean = false
    suspend fun markTagsGuideSeen() = Unit

    object None : SearchModePrefs {
        override suspend fun load(): String? = null
        override suspend fun save(key: String) = Unit
    }
}

/** The mode's saved key (shared with the web dashboard's words), and back. */
val SearchMode.key: String get() = when (this) {
    SearchMode.CONVERSATIONS -> "conv"
    SearchMode.MESSAGES -> "msgs"
    SearchMode.TAGS -> "tags"
}

fun searchModeOf(key: String?): SearchMode? = SearchMode.entries.firstOrNull { it.key == key }

/**
 * Everything the WhatsApp searches keep, owned by the view model so it survives opening a hit and coming
 * back: the Search view (open flag, the query, its mode, the Messages and Conversations results), the
 * in-chat bar, and the node range a tapped conversation highlights in its chat. The query is private and
 * never logged.
 *
 * [currentChat] is the open chat's jid; [showHit] brings a hit of that chat into view; [onMessage] shows a toast;
 * [modePrefs] remembers the mode the user picked.
 */
class WhatsAppSearch(
    repo: WhatsAppRepository,
    private val scope: CoroutineScope,
    currentChat: () -> String?,
    showHit: (WaSearchRow) -> Unit,
    onMessage: (String) -> Unit,
    private val modePrefs: SearchModePrefs = SearchModePrefs.None,
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

    /** The chat a result opened from this view, while it is still the one on screen; drives "Back to results". */
    var originChat by mutableStateOf<String?>(null)
        private set

    /** The search guide sheet is open (the field's help button, or the empty state's link). */
    var guideOpen by mutableStateOf(false)
        private set

    fun openGuide() {
        guideOpen = true
    }

    fun closeGuide() {
        guideOpen = false
    }

    /** Where each mode's list was scrolled to, so coming back lands on the same result. */
    val scroll = SearchScrollMemory()

    val messages = MessageSearchState(repo, scope)
    val conv = ConvSearchState(repo, scope, onMessage) { reconcile() }
    val inChat = InChatSearchState(repo, scope, currentChat, showHit)
    val tags = TagsSearchState(repo, scope) { reconcile() }

    /** Queries of this session, newest first. In memory only, never persisted: query text is private and backups are on. */
    var recent by mutableStateOf<List<String>>(emptyList())
        private set

    private var debounce: Job? = null

    /** The Tags how-to is expanded: on first use, and whenever the user opens it. */
    var tagsGuideOpen by mutableStateOf(true)
        private set

    /** Fold or open the Tags how-to; folding it is remembered, so it does not come back expanded next launch. */
    fun showTagsGuide(open: Boolean) {
        if (open == tagsGuideOpen) return
        tagsGuideOpen = open
        if (!open) scope.launch { runCatching { modePrefs.markTagsGuideSeen() } }
    }

    /** Tags mode is offered when chat search is on (the server says 404 when it is not). */
    val tagsAvailable: Boolean get() = conv.status?.enabled == true && !tags.off

    /** The modes the switch offers, in order; none while Messages is the only one. */
    val modes: List<SearchMode> get() = buildList {
        if (conv.available) add(SearchMode.CONVERSATIONS)
        if (conv.available || tagsAvailable) add(SearchMode.MESSAGES)
        if (tagsAvailable) add(SearchMode.TAGS)
    }.let { if (it.size > 1) it else emptyList() }

    /** The mode in force: Conversations needs chat search on with something indexed, Tags needs it on. */
    val mode: SearchMode get() = when {
        picked == SearchMode.TAGS && tagsAvailable -> SearchMode.TAGS
        conv.available -> if (picked == SearchMode.MESSAGES) SearchMode.MESSAGES else SearchMode.CONVERSATIONS
        else -> SearchMode.MESSAGES
    }

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
        // A text query belongs to a text mode: leave Tags for the one that answers it.
        if (mode == SearchMode.TAGS) picked = if (conv.available) SearchMode.CONVERSATIONS else SearchMode.MESSAGES
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
        conv.closeCard()
        tags.closeCard()
    }

    /** A result opened [jid] from this view: the chat shows "Back to results" until it is left another way. */
    fun noteOpenedFromSearch(jid: String) {
        if (open) originChat = jid
    }

    /** Whether the "Back to results" pill shows over [currentChat]. */
    fun backToResultsShown(currentChat: String?): Boolean = backToResultsVisible(open, originChat, currentChat)

    /** How many filters the mode in force has set, for the "Filters" pill's badge. */
    val activeFilters: Int get() = activeFilterCount(mode, messages.chips, tags.filters, tags.cat)

    /** "Clear all" in the Filters sheet: the mode's filters, and in Tags the category and the picked tags too. */
    fun clearAllFilters() {
        when (mode) {
            SearchMode.MESSAGES -> if (!messages.chips.none) pressChip(null)
            SearchMode.TAGS -> {
                tags.resetFilters()
                tags.chooseCat("")
                tags.clearSelection()
            }
            SearchMode.CONVERSATIONS -> Unit
        }
    }

    /** The Search view is on screen: read the status now and keep reading while a run goes. */
    fun viewShown() = conv.open()

    /** The Search view left the screen (closed, or a hit opened its chat): stop polling. */
    fun viewHidden() = conv.close()

    fun onQueryChange(text: String) {
        if (mode == SearchMode.TAGS) {
            tags.onInput(text)
            return
        }
        query = text
        debounce?.cancel()
        debounce = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runQuery()
        }
    }

    /** Run the query now (the IME's Search key skips the debounce). */
    fun submit() {
        if (mode == SearchMode.TAGS) {
            tags.submitInput()
            return
        }
        noteQuery(query)
        debounce?.cancel()
        runQuery()
    }

    fun setMode(m: SearchMode) {
        if (m !in modes) return
        picked = m
        scope.launch { runCatching { modePrefs.save(m.key) } }
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
        originChat = null
    }

    private fun runQuery() {
        val q = query.trim()
        val m = mode
        ranMode = m
        scroll.clear()
        // Only the mode in force keeps results; the others clear so a switch never shows a stale list.
        if (m != SearchMode.TAGS) tags.stop()
        when (m) {
            SearchMode.CONVERSATIONS -> {
                messages.clear()
                conv.run(q)
            }
            SearchMode.MESSAGES -> {
                conv.clear()
                messages.run(q)
            }
            SearchMode.TAGS -> {
                conv.clear()
                messages.clear()
                tags.start()
            }
        }
    }

    /** The status landed (or the feature turned out to be off): re-run a query whose mode is no longer the one it ran in. */
    private fun reconcile() {
        if ((query.isNotBlank() || mode == SearchMode.TAGS) && mode != ranMode) runQuery()
    }

    init {
        // The remembered mode takes effect once the status says which modes exist; a pick made first wins.
        scope.launch {
            val saved = searchModeOf(runCatching { modePrefs.load() }.getOrNull()) ?: return@launch
            if (picked == null) {
                picked = saved
                reconcile()
            }
        }
        scope.launch {
            if (runCatching { modePrefs.tagsGuideSeen() }.getOrDefault(false)) tagsGuideOpen = false
        }
    }
}
