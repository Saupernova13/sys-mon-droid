package com.raavivi.sysmon.ui.chatindex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raavivi.sysmon.core.data.chatindex.ChatIndexRepository
import com.raavivi.sysmon.core.model.CiOverview
import com.raavivi.sysmon.core.model.CiStorage
import kotlinx.coroutines.CoroutineScope

/**
 * The Chat index screen's state: the tab, whether chat search is off on the server, and the four tabs' own
 * state. The Overview is read first (it doubles as the probe for "off"); each other tab loads on its first open,
 * and Refresh reads the open one again and forgets the rest. [onMessage] shows the snackbar. Chat content is
 * private and never logged.
 */
class ChatIndexState(
    repo: ChatIndexRepository,
    scope: CoroutineScope,
    onMessage: (String) -> Unit,
) {
    var tab by mutableStateOf(CiTab.OVERVIEW)
        private set

    /** The server answered 404 "chat search is off": the whole screen says so. */
    var off by mutableStateOf(false)
        private set

    private val setOff = { off = true }

    val overview = Loadable<CiOverview>(scope, setOff) { repo.overview() }
    val storage = Loadable<CiStorage>(scope, setOff) { repo.storage() }
    val browse = BrowseState(repo, scope, setOff, onMessage)
    val lexicon = LexiconState(repo, scope, setOff)

    private val loaded = mutableSetOf<CiTab>()

    fun start() {
        off = false
        loaded.clear()
        open(tab)
    }

    fun select(t: CiTab) {
        tab = t
        if (t !in loaded) open(t)
    }

    private fun open(t: CiTab) {
        loaded += t
        when (t) {
            CiTab.OVERVIEW -> overview.load()
            CiTab.BROWSE -> browse.start()
            CiTab.LEXICON -> lexicon.load()
            CiTab.STORAGE -> storage.load()
        }
    }

    /** Read the open tab again (Browse keeps its place) and forget what the others held. */
    fun refresh() {
        off = false
        loaded.clear()
        loaded += tab
        when (tab) {
            CiTab.OVERVIEW -> overview.load()
            CiTab.BROWSE -> browse.refresh()
            CiTab.LEXICON -> lexicon.load()
            CiTab.STORAGE -> storage.load()
        }
        // The Overview reads again the next time it is opened.
        if (tab != CiTab.OVERVIEW) overview.reset()
    }

    /**
     * Back inside the screen: a Browse drill-down steps up. False means nothing was left to step back to and the
     * screen should close.
     */
    fun back(): Boolean = tab == CiTab.BROWSE && browse.back()

    /** Whether system Back is handled inside the screen. */
    val handlesBack: Boolean get() = tab == CiTab.BROWSE && browse.nav.canGoBack
}
