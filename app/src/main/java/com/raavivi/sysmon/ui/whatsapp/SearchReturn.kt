package com.raavivi.sysmon.ui.whatsapp

/**
 * Coming back from a chat opened out of the Search view: whether the "Back to results" pill shows, and where
 * each mode's list was scrolled to. Pure state with no UI; the query, mode, filters and open card already
 * live in the search state, which outlives the chat.
 */

/**
 * The pill shows while the Search view is still open behind the chat and the chat on screen is the one a
 * result opened. Leaving that chat another way (closing it, opening a different one) clears [originChat].
 */
fun backToResultsVisible(searchOpen: Boolean, originChat: String?, currentChat: String?): Boolean =
    searchOpen && originChat != null && originChat == currentChat

/** A list's first visible item and how far it is scrolled into it. */
data class ScrollPos(val index: Int = 0, val offset: Int = 0)

/**
 * The list position of each mode, kept up to date while its list is on screen and read when it is composed
 * again. A new query or a mode change [clear]s it, so a fresh list always starts at the top.
 */
class SearchScrollMemory {
    private val positions = HashMap<SearchMode, ScrollPos>()

    fun save(mode: SearchMode, index: Int, offset: Int) {
        positions[mode] = ScrollPos(index.coerceAtLeast(0), offset.coerceAtLeast(0))
    }

    fun restore(mode: SearchMode): ScrollPos = positions[mode] ?: ScrollPos()

    fun clear() = positions.clear()
}
