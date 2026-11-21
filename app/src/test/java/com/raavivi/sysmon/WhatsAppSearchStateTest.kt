package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WaCardsStatus
import com.raavivi.sysmon.core.model.WaChatSearchResponse
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaPickCounts
import com.raavivi.sysmon.core.model.WaSearchResponse
import com.raavivi.sysmon.core.model.WaSearchRow
import com.raavivi.sysmon.core.model.WaSearchTerm
import com.raavivi.sysmon.core.model.WaTimelineDay
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.ui.whatsapp.MAX_RECENT
import com.raavivi.sysmon.ui.whatsapp.NO_MATCHES
import com.raavivi.sysmon.ui.whatsapp.PICK_NOT_HERE
import com.raavivi.sysmon.ui.whatsapp.isRetryableSearchError
import com.raavivi.sysmon.ui.whatsapp.SEARCH_DEBOUNCE_MS
import com.raavivi.sysmon.ui.whatsapp.SEARCH_FAILED
import com.raavivi.sysmon.ui.whatsapp.SearchChip
import com.raavivi.sysmon.ui.whatsapp.SearchMode
import com.raavivi.sysmon.ui.whatsapp.SearchPhase
import com.raavivi.sysmon.ui.whatsapp.STATUS_POLL_MS
import com.raavivi.sysmon.ui.whatsapp.WhatsAppSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Search view, in-chat bar and conversation search state, over the scriptable fake repository. Made-up data only. */
@OptIn(ExperimentalCoroutinesApi::class)
class WhatsAppSearchStateTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeWhatsAppRepository()
    private val alice = "27820000001@s.whatsapp.net"
    private val bob = "27820000002@s.whatsapp.net"
    private val shown = mutableListOf<WaSearchRow>()
    private val toasts = mutableListOf<String>()
    private var current: String? = alice

    private lateinit var search: WhatsAppSearch

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        search = WhatsAppSearch(repo, CoroutineScope(dispatcher), { current }, { shown += it }, { toasts += it })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun advance(ms: Long) = dispatcher.scheduler.advanceTimeBy(ms)

    private fun row(chat: String, id: String, ts: Long = 100, snippet: String = "go to the \u0002cinema\u0003") =
        WaSearchRow(chatJid = chat, chatName = "Test Chat", msgId = id, ts = ts, snippet = snippet)

    private fun page(vararg rows: WaSearchRow, next: Int? = null, total: Int = rows.size, capped: Boolean = false, terms: List<WaSearchTerm> = emptyList()) =
        ApiResult.Ok(WaSearchResponse(results = rows.toList(), total = total, totalCapped = capped, nextOffset = next, terms = terms))

    private fun conv(chat: String = alice, start: Long = 1000, end: Long = 2000) = WaConvResult(
        nodeId = 1, chat = chat, family = listOf(chat), chatName = "Test Chat",
        startTs = start, startRowid = 5, endTs = end, endRowid = 9, nMsgs = 4,
    )

    private val convOn = ApiResult.Ok(WaChatSearchStatus(enabled = true, auto = true, nodeVectors = 50))

    // -- Messages mode ---------------------------------------------------------------------------

    @Test
    fun `typing runs the search after the debounce, not before`() {
        repo.searchResponder = { page(row(alice, "m1"), row(bob, "m2"), next = 100) }

        search.onQueryChange("cinema")
        advance(SEARCH_DEBOUNCE_MS - 1)
        assertTrue(repo.searchCalls.isEmpty())
        advance(2)

        assertEquals(1, repo.searchCalls.size)
        val call = repo.searchCalls.single()
        assertEquals("cinema", call.q)
        assertNull(call.chat)
        assertEquals(100, call.limit)
        assertEquals(listOf("m1", "m2"), search.messages.hits.map { it.msgId })
        assertEquals(100, search.messages.nextOffset)
        assertEquals(SearchPhase.DONE, search.messages.phase)
    }

    @Test
    fun `typing again restarts the debounce so only the last query runs`() {
        search.onQueryChange("cin")
        advance(200)
        search.onQueryChange("cinema")
        advance(SEARCH_DEBOUNCE_MS + 1)

        assertEquals(listOf("cinema"), repo.searchCalls.map { it.q })
    }

    @Test
    fun `a stale response never renders`() {
        val gate = CompletableDeferred<Unit>()
        repo.searchGate = gate
        repo.searchResponder = { c -> if (c.q == "old") page(row(alice, "old1")) else page(row(bob, "new1")) }
        search.openFor("old")
        search.submit()   // the first query parks on the gate

        repo.searchGate = null
        search.onQueryChange("new")
        search.submit()
        gate.complete(Unit)

        assertEquals(listOf("new1"), search.messages.hits.map { it.msgId })
    }

    @Test
    fun `a blank query clears the results and asks nothing`() {
        repo.searchResponder = { page(row(alice, "m1")) }
        search.onQueryChange("cinema")
        advance(SEARCH_DEBOUNCE_MS + 1)
        repo.searchCalls.clear()

        search.onQueryChange("  ")
        advance(SEARCH_DEBOUNCE_MS + 1)

        assertTrue(repo.searchCalls.isEmpty())
        assertTrue(search.messages.hits.isEmpty())
        assertEquals(SearchPhase.IDLE, search.messages.phase)
    }

    @Test
    fun `chips filter the query, pressing an active chip turns it off and All clears`() {
        search.onQueryChange("cinema")
        advance(SEARCH_DEBOUNCE_MS + 1)

        search.pressChip(SearchChip.MINE)
        search.pressChip(SearchChip.PHOTOS)
        val both = repo.searchCalls.last()
        assertEquals(true, both.fromMe)
        assertEquals("image", both.media)

        search.pressChip(SearchChip.MINE)
        assertNull(repo.searchCalls.last().fromMe)
        assertEquals("image", repo.searchCalls.last().media)

        search.pressChip(null)
        assertNull(repo.searchCalls.last().media)
        assertTrue(search.messages.chips.none)

        search.pressChip(SearchChip.THEIRS)
        assertEquals(false, repo.searchCalls.last().fromMe)
    }

    @Test
    fun `More results appends the next page with the same filters and stops at the last page`() {
        repo.searchResponder = { c ->
            if (c.offset == null) page(row(alice, "m1"), next = 100) else page(row(alice, "m2"))
        }
        search.openFor("cinema")
        search.submit()

        search.loadMore()

        assertEquals(listOf("m1", "m2"), search.messages.hits.map { it.msgId })
        assertEquals(100, repo.searchCalls.last().offset)
        assertNull(search.messages.nextOffset)
        val calls = repo.searchCalls.size
        search.loadMore()
        assertEquals(calls, repo.searchCalls.size)
    }

    @Test
    fun `a 400 shows the server's words and anything else says Search failed`() {
        repo.searchResponder = { ApiResult.Err("query is too long", 400) }
        search.onQueryChange("cinema")
        advance(SEARCH_DEBOUNCE_MS + 1)
        assertEquals(SearchPhase.ERROR, search.messages.phase)
        assertEquals("query is too long", search.messages.error)

        repo.searchResponder = { ApiResult.Err("HTTP 502", 502) }
        search.submit()
        assertEquals(SEARCH_FAILED, search.messages.error)
    }

    @Test
    fun `expanding a group is remembered`() {
        search.messages.expand(alice)

        assertEquals(setOf(alice), search.messages.expanded)
    }

    // -- the in-chat bar -------------------------------------------------------------------------

    @Test
    fun `the in-chat bar searches this chat, counts hits and exposes terms and days`() {
        val terms = listOf(WaSearchTerm("cinema", prefix = true))
        repo.searchResponder = { page(row(alice, "m1"), row(alice, "m2"), total = 12, terms = terms) }
        repo.searchDaysResult = ApiResult.Ok(listOf(WaTimelineDay("2026-11-01", 2, 1_700_000_000)))

        search.inChat.openBar()
        search.inChat.onQueryChange("cinema")
        advance(SEARCH_DEBOUNCE_MS + 1)

        assertEquals(alice, repo.searchCalls.single().chat)
        assertEquals("12 hits", search.inChat.countLabel)
        assertEquals(terms, search.inChat.terms)
        assertEquals(1, search.inChat.days!!.size)
        assertEquals(listOf("cinema" to alice), repo.searchDaysCalls)
    }

    @Test
    fun `stepping selects older hits, counts n of m and pages in more on demand`() {
        repo.searchResponder = { c ->
            if (c.offset == null) page(row(alice, "m1"), row(alice, "m2"), next = 2, total = 3)
            else page(row(alice, "m3"), total = 3)
        }
        search.inChat.openBar()
        search.inChat.continueWith("cinema", null)

        search.inChat.step(1)
        assertEquals("1/3", search.inChat.countLabel)
        search.inChat.step(1)
        search.inChat.step(1)

        assertEquals(listOf("m1", "m2", "m3"), shown.map { it.msgId })
        assertEquals("3/3", search.inChat.countLabel)
        assertTrue(search.inChat.buttons.olderOff)
        search.inChat.step(-1)
        assertEquals("m2", shown.last().msgId)
        assertEquals(2, repo.searchCalls.size)
    }

    @Test
    fun `a step past the last hit does nothing`() {
        repo.searchResponder = { page(row(alice, "m1")) }
        search.inChat.continueWith("cinema", null)
        search.inChat.step(1)
        shown.clear()

        search.inChat.step(1)
        search.inChat.step(-1)

        assertTrue(shown.isEmpty())
    }

    @Test
    fun `continuing from the Search view selects that hit, paging until it is found`() {
        repo.searchResponder = { c ->
            when (c.offset) {
                null -> page(row(alice, "a1"), next = 1, total = 3)
                1 -> page(row(alice, "a2"), next = 2, total = 3)
                else -> page(row(alice, "a3"), total = 3)
            }
        }

        search.inChat.continueWith("cinema", "a3")

        assertTrue(search.inChat.open)
        assertEquals("3/3", search.inChat.countLabel)
        assertEquals(3, repo.searchCalls.size)
        assertTrue(shown.isEmpty())   // seeking selects; it does not jump
    }

    @Test
    fun `a hit that is not in the results leaves the selection unset`() {
        repo.searchResponder = { page(row(alice, "a1"), total = 1) }

        search.inChat.continueWith("cinema", "missing")

        assertEquals("1 hits", search.inChat.countLabel)
    }

    @Test
    fun `a capped total reads a thousand plus`() {
        repo.searchResponder = { page(row(alice, "a1"), total = 1000, capped = true) }

        search.inChat.continueWith("cinema", null)

        assertEquals("1000+ hits", search.inChat.countLabel)
    }

    @Test
    fun `the bar reports a rejected query in the server's words`() {
        repo.searchResponder = { ApiResult.Err("query is too long", 400) }

        search.inChat.continueWith("cinema", null)

        assertEquals("query is too long", search.inChat.countLabel)
    }

    @Test
    fun `closing the bar, or changing chat, clears everything it loaded`() {
        repo.searchResponder = { page(row(alice, "m1"), total = 1, terms = listOf(WaSearchTerm("cinema"))) }
        search.inChat.continueWith("cinema", null)
        assertTrue(search.inChat.open)

        search.onChatChanged()

        assertFalse(search.inChat.open)
        assertEquals("", search.inChat.query)
        assertTrue(search.inChat.hits.isEmpty())
        assertTrue(search.inChat.terms.isEmpty())
        assertNull(search.inChat.days)
        assertEquals("", search.inChat.countLabel)
    }

    @Test
    fun `results for a chat that is no longer open are dropped`() {
        val gate = CompletableDeferred<Unit>()
        repo.searchGate = gate
        repo.searchResponder = { page(row(alice, "m1")) }
        search.inChat.continueWith("cinema", null)

        current = bob
        gate.complete(Unit)

        assertTrue(search.inChat.hits.isEmpty())
    }

    @Test
    fun `the bar cannot open without a chat`() {
        current = null

        search.inChat.openBar()

        assertFalse(search.inChat.open)
    }

    // -- Conversations mode ----------------------------------------------------------------------

    @Test
    fun `Conversations is offered when chat search is on with vectors, and is the default`() {
        search.viewShown()
        assertEquals(SearchMode.MESSAGES, search.mode)

        repo.chatSearchStatusResult = convOn
        search.viewShown()
        assertEquals(SearchMode.CONVERSATIONS, search.mode)

        search.setMode(SearchMode.MESSAGES)
        assertEquals(SearchMode.MESSAGES, search.mode)
        search.setMode(SearchMode.CONVERSATIONS)
        assertEquals(SearchMode.CONVERSATIONS, search.mode)
    }

    @Test
    fun `chat search on with nothing indexed does not offer Conversations`() {
        repo.chatSearchStatusResult = ApiResult.Ok(WaChatSearchStatus(enabled = true, nodeVectors = 0))
        search.viewShown()

        assertEquals(SearchMode.MESSAGES, search.mode)
    }

    @Test
    fun `a query typed before the status lands re-runs in Conversations once it does`() {
        repo.chatSearchStatusResult = convOn
        repo.chatSearchResult = ApiResult.Ok(WaChatSearchResponse(results = listOf(conv())))
        search.onQueryChange("cinema night")
        advance(SEARCH_DEBOUNCE_MS + 1)
        assertEquals(1, repo.searchCalls.size)   // ran as messages: the status was not known yet

        search.viewShown()

        assertEquals(listOf("cinema night"), repo.chatSearchQueries)
        assertEquals(1, search.conv.results.size)
        assertTrue(search.messages.hits.isEmpty())
    }

    @Test
    fun `the chat filter's way in runs the query at once in the mode in force`() {
        repo.chatSearchStatusResult = convOn
        search.viewShown()
        repo.chatSearchResult = ApiResult.Ok(WaChatSearchResponse(results = listOf(conv())))

        search.openFor("cinema night")

        assertTrue(search.open)
        assertEquals("cinema night", search.query)
        assertEquals(listOf("cinema night"), repo.chatSearchQueries)
        assertTrue(repo.searchCalls.isEmpty())
    }

    @Test
    fun `the first open from the filter waits for the status, then runs once`() {
        repo.chatSearchStatusResult = convOn

        search.openFor("cinema night")
        assertTrue(repo.chatSearchQueries.isEmpty())
        search.viewShown()

        assertEquals(listOf("cinema night"), repo.chatSearchQueries)
        assertTrue(repo.searchCalls.isEmpty())
    }

    @Test
    fun `a word-only answer is flagged degraded`() {
        repo.chatSearchStatusResult = convOn
        search.viewShown()
        repo.chatSearchResult = ApiResult.Ok(WaChatSearchResponse(results = listOf(conv()), degraded = "keyword"))

        search.openFor("cinema")

        assertTrue(search.conv.degraded)
        assertEquals(1, search.conv.results.size)
    }

    @Test
    fun `a 404 means the feature is off, so the mode goes away and Messages runs`() {
        repo.chatSearchStatusResult = convOn
        search.viewShown()
        repo.chatSearchResult = ApiResult.Err("not found", 404)
        repo.searchResponder = { page(row(alice, "m1")) }

        search.openFor("cinema")

        assertEquals(SearchMode.MESSAGES, search.mode)
        assertEquals(listOf("m1"), search.messages.hits.map { it.msgId })
    }

    @Test
    fun `a conversation 400 shows the server's words`() {
        repo.chatSearchStatusResult = convOn
        search.viewShown()
        repo.chatSearchResult = ApiResult.Err("query is too long", 400)

        search.openFor("cinema")

        assertEquals("query is too long", search.conv.error)
        assertEquals(SearchPhase.ERROR, search.conv.phase)
    }

    // -- Found it / Not here ---------------------------------------------------------------------

    private fun withResults(vararg convs: WaConvResult) {
        repo.chatSearchStatusResult = convOn
        search.viewShown()
        repo.chatSearchResult = ApiResult.Ok(WaChatSearchResponse(results = convs.toList()))
        search.openFor("cinema night")
    }

    @Test
    fun `Found it posts the query the results answer, the hit and the one-based rank`() {
        withResults(conv(alice), conv(bob, 3000, 4000))
        repo.pickResult = ApiResult.Ok(WaPickCounts(found = 2, notHere = 1))
        search.onQueryChange("something else typed since")   // the pick names the results' query, not this

        search.conv.pickFound(1)

        val body = repo.picks.single()
        assertEquals("cinema night", body.q)
        assertEquals("found", body.verdict)
        assertEquals(2, body.rank)
        assertEquals(2, body.shown)
        assertEquals(listOf(bob), body.hit!!.family)
        assertEquals(3000L, body.hit!!.startTs)
        assertEquals(1, search.conv.pick.saved)
        assertEquals(3, search.conv.pick.marked)
    }

    @Test
    fun `Not here is available with no results and saves the shown count`() {
        withResults()
        repo.pickResult = ApiResult.Ok(WaPickCounts(found = 0, notHere = 1))

        search.conv.pickNotHere()

        val body = repo.picks.single()
        assertEquals("not_here", body.verdict)
        assertEquals(0, body.shown)
        assertNull(body.hit)
        assertEquals(PICK_NOT_HERE, search.conv.pick.saved)
        assertEquals(1, search.conv.pick.marked)
    }

    @Test
    fun `saving a different verdict moves Saved to it`() {
        withResults(conv())
        repo.pickResult = ApiResult.Ok(WaPickCounts(found = 1, notHere = 0))
        search.conv.pickFound(0)
        repo.pickResult = ApiResult.Ok(WaPickCounts(found = 0, notHere = 1))

        search.conv.pickNotHere()

        assertEquals(PICK_NOT_HERE, search.conv.pick.saved)
    }

    @Test
    fun `a failed save reads Not saved on that button`() {
        withResults(conv())
        repo.pickResult = ApiResult.Err("down", 502)

        search.conv.pickFound(0)

        assertEquals(0, search.conv.pick.failed)
        assertNull(search.conv.pick.saved)
        assertNull(search.conv.pick.busy)
    }

    @Test
    fun `a pick answered after the query changed is ignored`() {
        withResults(conv())
        val gate = CompletableDeferred<Unit>()
        repo.pickGate = gate
        repo.pickResult = ApiResult.Ok(WaPickCounts(found = 1, notHere = 0))
        search.conv.pickFound(0)   // parks in flight

        repo.chatSearchResult = ApiResult.Ok(WaChatSearchResponse(results = emptyList()))
        search.openFor("another query")
        gate.complete(Unit)

        assertNull(search.conv.pick.saved)
        assertNull(search.conv.pick.marked)
    }

    // -- index and cards lines -------------------------------------------------------------------

    @Test
    fun `Update asks for an index run, shows it at once and re-reads the status`() {
        repo.chatSearchStatusResult = ApiResult.Ok(WaChatSearchStatus(enabled = true, auto = false, nodeVectors = 5))
        search.viewShown()
        val reads = repo.statusReads

        search.conv.requestIndex()

        assertEquals(1, repo.indexRequests)
        assertEquals(reads + 1, repo.statusReads)
    }

    @Test
    fun `a running index is polled every three seconds until the view is hidden`() {
        repo.chatSearchStatusResult = ApiResult.Ok(
            WaChatSearchStatus(enabled = true, auto = false, runRequested = true, nodeVectors = 5),
        )
        search.viewShown()
        val reads = repo.statusReads

        advance(STATUS_POLL_MS + 1)
        assertEquals(reads + 1, repo.statusReads)
        advance(STATUS_POLL_MS)
        assertEquals(reads + 2, repo.statusReads)

        search.viewHidden()
        advance(STATUS_POLL_MS * 3)
        assertEquals(reads + 2, repo.statusReads)
    }

    @Test
    fun `an idle status is not polled`() {
        repo.chatSearchStatusResult = convOn
        search.viewShown()
        val reads = repo.statusReads

        advance(STATUS_POLL_MS * 3)

        assertEquals(reads, repo.statusReads)
    }

    @Test
    fun `a card run is polled too`() {
        repo.chatSearchStatusResult = ApiResult.Ok(
            WaChatSearchStatus(enabled = true, auto = true, nodeVectors = 5, cards = WaCardsStatus(configured = true, requested = true)),
        )
        search.viewShown()
        val reads = repo.statusReads

        advance(STATUS_POLL_MS + 1)

        assertEquals(reads + 1, repo.statusReads)
    }

    @Test
    fun `Run and Stop call their routes and a failure says so`() {
        repo.chatSearchStatusResult = ApiResult.Ok(
            WaChatSearchStatus(enabled = true, nodeVectors = 5, cards = WaCardsStatus(configured = true)),
        )
        search.viewShown()

        search.conv.toggleCards(running = false)
        search.conv.toggleCards(running = true)

        assertEquals(1, repo.cardsRuns)
        assertEquals(1, repo.cardsStops)

        repo.actionResult = ApiResult.Err("cards are not configured: set SYS_MON_CARD_URL", 409)
        search.conv.toggleCards(running = false)
        assertEquals("cards are not configured: set SYS_MON_CARD_URL", toasts.last())
    }

    // -- recent queries, empty counter, retry ----------------------------------------------------

    @Test
    fun `recent queries keep the newest first, dedupe ignoring case, cap and clear`() {
        search.noteQuery("cinema")
        search.noteQuery("pottery")
        search.noteQuery("CINEMA")
        assertEquals(listOf("CINEMA", "pottery"), search.recent)

        search.noteQuery("a")   // too short to be worth remembering
        (1..20).forEach { search.noteQuery("query $it") }
        assertEquals(MAX_RECENT, search.recent.size)
        assertEquals("query 20", search.recent.first())

        search.clearRecent()
        assertTrue(search.recent.isEmpty())
    }

    @Test
    fun `submitting and the filter's way in remember the query but typing alone does not`() {
        search.onQueryChange("cin")
        advance(SEARCH_DEBOUNCE_MS + 1)
        assertTrue(search.recent.isEmpty())

        search.submit()
        search.openFor("pottery")

        assertEquals(listOf("pottery", "cin"), search.recent)
    }

    @Test
    fun `the in-chat counter says No matches for an answered query with no hits`() {
        repo.searchResponder = { page(total = 0) }

        search.inChat.continueWith("zzzz", null)

        assertEquals(NO_MATCHES, search.inChat.countLabel)
        assertTrue(search.inChat.buttons.olderOff)
        assertTrue(search.inChat.buttons.newerOff)
    }

    @Test
    fun `only a plain failure is worth a Retry`() {
        assertTrue(isRetryableSearchError(SEARCH_FAILED))
        assertFalse(isRetryableSearchError("query is too long"))
        assertFalse(isRetryableSearchError(null))
    }
}
