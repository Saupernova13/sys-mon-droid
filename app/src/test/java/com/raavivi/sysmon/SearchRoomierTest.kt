package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WaCardsStatus
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import com.raavivi.sysmon.ui.whatsapp.ChipSel
import com.raavivi.sysmon.ui.whatsapp.ScrollPos
import com.raavivi.sysmon.ui.whatsapp.SearchChip
import com.raavivi.sysmon.ui.whatsapp.SearchMode
import com.raavivi.sysmon.ui.whatsapp.SearchScrollMemory
import com.raavivi.sysmon.ui.whatsapp.SelectedTagChip
import com.raavivi.sysmon.ui.whatsapp.StatusChip
import com.raavivi.sysmon.ui.whatsapp.StatusTone
import com.raavivi.sysmon.ui.whatsapp.TagFilters
import com.raavivi.sysmon.ui.whatsapp.TagGroup
import com.raavivi.sysmon.ui.whatsapp.TagPick
import com.raavivi.sysmon.ui.whatsapp.TagSelection
import com.raavivi.sysmon.ui.whatsapp.TagSort
import com.raavivi.sysmon.ui.whatsapp.activeFilterCount
import com.raavivi.sysmon.ui.whatsapp.backToResultsVisible
import com.raavivi.sysmon.ui.whatsapp.filtersDescription
import com.raavivi.sysmon.ui.whatsapp.filtersLabel
import com.raavivi.sysmon.ui.whatsapp.messageFilterCount
import com.raavivi.sysmon.ui.whatsapp.modeHasFilters
import com.raavivi.sysmon.ui.whatsapp.selectedTagChips
import com.raavivi.sysmon.ui.whatsapp.statusChip
import com.raavivi.sysmon.ui.whatsapp.tagChipText
import com.raavivi.sysmon.ui.whatsapp.tagsFilterCount
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The roomier Search view's pure decisions: filter badge, status chip, back-to-results pill and scroll memory. Made-up data only. */
class SearchRoomierTest {

    private val utc = ZoneOffset.UTC

    // -- the Filters pill ------------------------------------------------------------------------

    @Test
    fun `only Messages and Tags have filters`() {
        assertFalse(modeHasFilters(SearchMode.CONVERSATIONS))
        assertTrue(modeHasFilters(SearchMode.MESSAGES))
        assertTrue(modeHasFilters(SearchMode.TAGS))
    }

    @Test
    fun `the badge counts the Messages chips that are on`() {
        assertEquals(0, messageFilterCount(ChipSel()))
        assertEquals(1, messageFilterCount(ChipSel(sender = SearchChip.MINE)))
        assertEquals(2, messageFilterCount(ChipSel(sender = SearchChip.THEIRS, media = SearchChip.PHOTOS)))
    }

    @Test
    fun `the badge counts the Tags refinements and the category`() {
        assertEquals(0, tagsFilterCount(TagFilters(), ""))
        assertEquals(1, tagsFilterCount(TagFilters(), "topic"))
        assertEquals(2, tagsFilterCount(TagFilters(unresolved = true, sort = TagSort.OLDEST), ""))
        assertEquals(3, tagsFilterCount(TagFilters(minIntensity = 3, unresolved = true), "who"))
    }

    @Test
    fun `the count follows the mode in force`() {
        val chips = ChipSel(media = SearchChip.VOICE)
        val tagFilters = TagFilters(chat = "someone@s.whatsapp.net")
        assertEquals(0, activeFilterCount(SearchMode.CONVERSATIONS, chips, tagFilters, "topic"))
        assertEquals(1, activeFilterCount(SearchMode.MESSAGES, chips, tagFilters, "topic"))
        assertEquals(2, activeFilterCount(SearchMode.TAGS, chips, tagFilters, "topic"))
    }

    @Test
    fun `the pill reads Filters with a count only when some are set`() {
        assertEquals("Filters", filtersLabel(0))
        assertEquals("Filters · 2", filtersLabel(2))
        assertEquals("Filters", filtersDescription(0))
        assertEquals("Filters, 1 set", filtersDescription(1))
        assertEquals("Filters, 3 set", filtersDescription(3))
    }

    @Test
    fun `selected tags flatten in group order with their glyphs`() {
        val a = TagPick("topic", "a", "Alpha")
        val b = TagPick("entity", "b", "Beta")
        val d = TagPick("topic", "d", "Delta")
        val sel = TagSelection().with(d, TagGroup.NONE).with(a, TagGroup.ALL).with(b, TagGroup.ANY)
        val chips = selectedTagChips(sel)
        assertEquals(listOf(a, b, d), chips.map { it.tag })
        assertEquals(listOf("+ Alpha", "~ Beta", "− Delta"), chips.map(::tagChipText))
        assertTrue(selectedTagChips(TagSelection()).isEmpty())
        assertEquals("~ Beta", tagChipText(SelectedTagChip(b, TagGroup.ANY)))
    }

    // -- the status chip -------------------------------------------------------------------------

    private val manual = WaChatSearchStatus(enabled = true, auto = false, messagesIndexed = 90, messagesTotal = 100)
    private fun chip(st: WaChatSearchStatus?, inConv: Boolean = true) = statusChip(st, inConv, utc)
    private fun withCards(c: WaCardsStatus, base: WaChatSearchStatus = WaChatSearchStatus(enabled = true)) = base.copy(cards = c)

    @Test
    fun `the chip is hidden when neither line would show`() {
        assertEquals(StatusChip.Hidden, chip(null))
        assertEquals(StatusChip.Hidden, chip(WaChatSearchStatus(enabled = false)))
        assertEquals(StatusChip.Hidden, chip(WaChatSearchStatus(enabled = true)))
        assertEquals(StatusChip.Hidden, chip(manual, inConv = false))
    }

    @Test
    fun `an index run reads Updating index and wins over everything`() {
        val st = withCards(WaCardsStatus(configured = true, requested = true, mode = ""), manual.copy(runRequested = true))
        assertEquals(StatusChip(true, "Updating index", StatusTone.BUSY), chip(st))
    }

    @Test
    fun `a topic-card run reads Cards writing, or why it is not`() {
        fun cards(c: WaCardsStatus) = chip(withCards(c))
        assertEquals(StatusChip(true, "Cards writing", StatusTone.BUSY), cards(WaCardsStatus(configured = true, requested = true)))
        assertEquals(StatusChip(true, "Cards writing", StatusTone.BUSY), cards(WaCardsStatus(configured = true, auto = true)))
        assertEquals("Cards on a break", cards(WaCardsStatus(configured = true, requested = true, mode = "break")).text)
        assertEquals("Cards paused", cards(WaCardsStatus(configured = true, requested = true, mode = "paused_game")).text)
        assertEquals("Cards waiting for the GPU", cards(WaCardsStatus(configured = true, requested = true, mode = "waiting_gpu")).text)
        val failing = cards(WaCardsStatus(configured = true, requested = true, lastError = JsonPrimitive("server_unavailable")))
        assertEquals(StatusChip(true, "Cards waiting", StatusTone.ATTENTION), failing)
    }

    @Test
    fun `an index that is behind asks for an update, one that is level is up to date`() {
        assertEquals(StatusChip(true, "Index needs update", StatusTone.ATTENTION), chip(manual))
        assertEquals(StatusChip(true, "Index needs update", StatusTone.ATTENTION), chip(manual.copy(messagesIndexed = null, messagesTotal = null)))
        assertEquals(StatusChip(true, "Index up to date", StatusTone.OK), chip(manual.copy(messagesIndexed = 100)))
    }

    @Test
    fun `an up to date index mentions idle cards`() {
        val level = manual.copy(messagesIndexed = 100)
        val st = withCards(WaCardsStatus(configured = true, done = 5, eligiblePending = 20), level)
        assertEquals(StatusChip(true, "Index up to date · cards idle", StatusTone.OK), chip(st))
        val allDone = withCards(WaCardsStatus(configured = true, done = 5, eligiblePending = 0), level)
        assertEquals("Index up to date", chip(allDone).text)
    }

    @Test
    fun `with automatic indexing the chip speaks for the cards alone`() {
        val idle = withCards(WaCardsStatus(configured = true, done = 5, eligiblePending = 20))
        assertEquals(StatusChip(true, "Cards idle", StatusTone.IDLE), chip(idle))
        val done = withCards(WaCardsStatus(configured = true, done = 5, eligiblePending = 0))
        assertEquals(StatusChip(true, "Cards all done", StatusTone.OK), chip(done))
    }

    @Test
    fun `the chip carries no counts or times`() {
        val st = withCards(WaCardsStatus(configured = true, requested = true, mode = "break", breakUntil = 1_000.0, done = 1234, eligiblePending = 15113))
        assertFalse(chip(st).text.any { it.isDigit() })
    }

    // -- back to results -------------------------------------------------------------------------

    @Test
    fun `the pill shows over the chat a result opened while Search is still open`() {
        assertTrue(backToResultsVisible(searchOpen = true, originChat = "a", currentChat = "a"))
    }

    @Test
    fun `the pill is gone once Search is closed, another chat is open or there is no origin`() {
        assertFalse(backToResultsVisible(searchOpen = false, originChat = "a", currentChat = "a"))
        assertFalse(backToResultsVisible(searchOpen = true, originChat = "a", currentChat = "b"))
        assertFalse(backToResultsVisible(searchOpen = true, originChat = null, currentChat = "a"))
        assertFalse(backToResultsVisible(searchOpen = true, originChat = "a", currentChat = null))
    }

    // -- scroll memory ---------------------------------------------------------------------------

    @Test
    fun `each mode remembers its own position and starts at the top`() {
        val m = SearchScrollMemory()
        assertEquals(ScrollPos(), m.restore(SearchMode.CONVERSATIONS))
        m.save(SearchMode.CONVERSATIONS, 7, 120)
        m.save(SearchMode.TAGS, 2, 0)
        assertEquals(ScrollPos(7, 120), m.restore(SearchMode.CONVERSATIONS))
        assertEquals(ScrollPos(2, 0), m.restore(SearchMode.TAGS))
        assertEquals(ScrollPos(), m.restore(SearchMode.MESSAGES))
    }

    @Test
    fun `a newer position replaces the older and clear forgets all`() {
        val m = SearchScrollMemory()
        m.save(SearchMode.MESSAGES, 3, 10)
        m.save(SearchMode.MESSAGES, 9, 4)
        assertEquals(ScrollPos(9, 4), m.restore(SearchMode.MESSAGES))
        m.clear()
        assertEquals(ScrollPos(), m.restore(SearchMode.MESSAGES))
    }

    @Test
    fun `a negative position is held at zero`() {
        val m = SearchScrollMemory()
        m.save(SearchMode.TAGS, -1, -5)
        assertEquals(ScrollPos(0, 0), m.restore(SearchMode.TAGS))
    }
}
