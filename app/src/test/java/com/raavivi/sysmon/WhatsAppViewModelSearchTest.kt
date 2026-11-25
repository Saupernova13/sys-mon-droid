package com.raavivi.sysmon

import coil.ImageLoader
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.WaChatSearchResponse
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaSearchResponse
import com.raavivi.sysmon.core.model.WaSearchRow
import com.raavivi.sysmon.core.model.WaTimelineDay
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.ui.whatsapp.ScrollPos
import com.raavivi.sysmon.ui.whatsapp.SearchChip
import com.raavivi.sysmon.ui.whatsapp.SearchMode
import com.raavivi.sysmon.ui.whatsapp.VoiceNotePlayer
import com.raavivi.sysmon.ui.whatsapp.VoiceRecorder
import com.raavivi.sysmon.ui.whatsapp.WA_PAGE_LIMIT
import com.raavivi.sysmon.ui.whatsapp.WhatsAppViewModel
import com.raavivi.sysmon.ui.whatsapp.waInclusiveAfter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/** Opening a chat at a moment, and the search surfaces' hooks into the view model. Made-up data only. */
@OptIn(ExperimentalCoroutinesApi::class)
class WhatsAppViewModelSearchTest {

    private val alice = "27820000001@s.whatsapp.net"
    private val bob = "27820000002@s.whatsapp.net"
    private val repo = FakeWhatsAppRepository()
    private val media = FakeMediaTools()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun msg(id: String, ts: Double, chat: String = alice) = WaMessage(
        id = id, chat = chat, chatName = "", sender = chat, senderName = "", fromMe = false,
        ts = ts, text = "hello $id", mediaType = null, filename = null, downloaded = false,
    )

    private fun noImageLoader(): ImageLoader =
        Proxy.newProxyInstance(ImageLoader::class.java.classLoader, arrayOf(ImageLoader::class.java)) { _, _, _ ->
            error("image loader is not used by these tests")
        } as ImageLoader

    private fun newViewModel(): WhatsAppViewModel {
        val cache = File(System.getProperty("java.io.tmpdir"), "wa-vm-search-test")
        return WhatsAppViewModel(
            repo = repo,
            uploads = FakeUploadSource(),
            mediaTools = media,
            imageLoader = noImageLoader(),
            voiceFactory = { scope, onMessage -> VoiceNotePlayer(cache, OkHttpClient(), scope, onMessage) },
            recorderFactory = { scope, onMessage ->
                VoiceRecorder(cache, { error("recording is not used by these tests") }, scope, onMessage)
            },
            nowSeconds = { 2_000.0 },
        )
    }

    private fun hit(id: String, ts: Long, chat: String = alice) =
        WaSearchRow(chatJid = chat, chatName = "Test Chat", msgId = id, ts = ts, snippet = "\u0002hello\u0003")

    // -- openChatAt ------------------------------------------------------------------------------

    @Test
    fun `opening a chat at a moment loads the page starting just before it, not the newest`() {
        repo.messagesResponder = { ApiResult.Ok(listOf(msg("m1", 990.0), msg("m2", 1000.0), msg("m3", 1010.0))) }
        val vm = newViewModel()

        vm.openChatAt(alice, "Test Chat", ts = 1000, focusId = "m2")

        val call = repo.messageCalls.single()
        assertEquals(alice, call.chat)
        assertEquals(WA_PAGE_LIMIT, call.limit)
        assertEquals(true, call.asc)
        assertEquals(waInclusiveAfter(1000.0 - 60.0), call.after)
        assertNull(call.before)
        assertEquals(alice, vm.currentJid)
        assertEquals("Test Chat", vm.currentName)
        assertEquals(listOf("m1", "m2", "m3"), vm.messages.map { it.id })
    }

    @Test
    fun `a short page at the moment is the latest, a full page is not`() {
        repo.messagesResponder = { ApiResult.Ok(listOf(msg("m1", 1000.0))) }
        val vm = newViewModel()
        vm.openChatAt(alice, "Test Chat", 1000, "m1")
        assertTrue(vm.atLatest)

        repo.messagesResponder = { ApiResult.Ok((0 until WA_PAGE_LIMIT).map { msg("p$it", 1000.0 + it) }) }
        vm.openChatAt(alice, "Test Chat", 1000, "p0")
        assertFalse(vm.atLatest)
    }

    @Test
    fun `a focus id asks the list to scroll to it, without one the list goes to the top`() {
        repo.messagesResponder = { ApiResult.Ok(listOf(msg("m1", 1000.0))) }
        val vm = newViewModel()

        vm.openChatAt(alice, "Test Chat", 1000, focusId = "m1")
        assertEquals("m1", vm.focusId)
        assertEquals(1, vm.focusTick)
        assertEquals(0, vm.topTick)

        vm.openChatAt(alice, "Test Chat", 1000)
        assertEquals(1, vm.focusTick)
        assertEquals(1, vm.topTick)
    }

    @Test
    fun `the timeline still loads as when a chat opens normally`() {
        repo.timelineResult = ApiResult.Ok(
            listOf(WaTimelineDay("2026-01-01", 3, 500), WaTimelineDay("2026-01-02", 4, 900)),
        )
        repo.messagesResponder = { ApiResult.Ok(listOf(msg("m1", 1000.0))) }
        val vm = newViewModel()

        vm.openChatAt(alice, "Test Chat", 1000, "m1")

        assertEquals(listOf(alice), repo.timelineCalls)
        assertTrue(vm.timeline != null)
    }

    @Test
    fun `a failed open says so and leaves the chat open`() {
        repo.messagesResponder = { ApiResult.Err("HTTP 502", 502) }
        val vm = newViewModel()

        vm.openChatAt(alice, "Test Chat", 1000, "m1")

        assertEquals("Open failed: HTTP 502", vm.message)
        assertEquals(alice, vm.currentJid)
    }

    @Test
    fun `a conversation opens its chat at the node start and tints the node range`() {
        repo.messagesResponder = { ApiResult.Ok(listOf(msg("m1", 1000.0))) }
        val vm = newViewModel()

        vm.openConversation(WaConvResult(chat = bob, chatName = "Fallback", startTs = 1000, endTs = 1600))

        assertEquals(waInclusiveAfter(940.0), repo.messageCalls.single().after)
        assertEquals(bob, vm.currentJid)
        assertEquals("Fallback", vm.currentName)
        assertEquals(1000L..1600L, vm.search.rangeHighlight)
        assertNull(vm.focusId)
    }

    @Test
    fun `the chat list's name wins over the hit's own`() {
        repo.chatsResult = ApiResult.Ok(listOf(WaChat(jid = alice, name = "Sam", lastTs = 1.0, unread = 0, pinned = false)))
        val vm = newViewModel()

        assertEquals("Sam", vm.searchChatName(alice, "Test Chat"))
        assertEquals("Test Chat", vm.searchChatName(bob, "Test Chat"))
        assertEquals(bob, vm.searchChatName(bob, ""))
    }

    @Test
    fun `the range and the bar are forgotten when the chat changes or closes`() {
        repo.messagesResponder = { ApiResult.Ok(listOf(msg("m1", 1000.0))) }
        val vm = newViewModel()
        vm.openConversation(WaConvResult(chat = alice, startTs = 1000, endTs = 1600))
        vm.search.inChat.openBar()
        assertTrue(vm.search.inChat.open)

        vm.openChat(bob, "Bob")
        assertNull(vm.search.rangeHighlight)
        assertFalse(vm.search.inChat.open)

        vm.openConversation(WaConvResult(chat = alice, startTs = 1000, endTs = 1600))
        assertEquals(1000L..1600L, vm.search.rangeHighlight)
        vm.closeChat()
        assertNull(vm.search.rangeHighlight)
    }

    // -- Search-view hits and in-chat stepping ----------------------------------------------------

    @Test
    fun `a Search-view hit opens its chat at the message and hands the query to the in-chat bar`() {
        repo.messagesResponder = { ApiResult.Ok(listOf(msg("m1", 1000.0), msg("m2", 1005.0))) }
        repo.searchResponder = { ApiResult.Ok(WaSearchResponse(results = listOf(hit("m1", 1000), hit("m2", 1005)), total = 2)) }
        val vm = newViewModel()
        vm.search.openFor("hello")

        vm.openSearchHit(hit("m2", 1005))

        assertEquals(alice, vm.currentJid)
        assertEquals("m2", vm.focusId)
        assertTrue(vm.search.inChat.open)
        assertEquals("hello", vm.search.inChat.query)
        assertEquals("2/2", vm.search.inChat.countLabel)   // selected on the hit it came from
        assertTrue(vm.search.open)                         // Back returns to the Search view
    }

    @Test
    fun `a hit already in the window is scrolled to without a request`() {
        repo.messagesResponder = { ApiResult.Ok(listOf(msg("m1", 1000.0), msg("m2", 1005.0))) }
        val vm = newViewModel()
        vm.openChat(alice, "Test Chat")
        val calls = repo.messageCalls.size
        val tick = vm.focusTick

        vm.focusHit(hit("m1", 1000))

        assertEquals(calls, repo.messageCalls.size)
        assertEquals("m1", vm.focusId)
        assertEquals(tick + 1, vm.focusTick)
    }

    @Test
    fun `a hit outside the window opens the window at it`() {
        repo.messagesResponder = { c ->
            if (c.after != null) ApiResult.Ok(listOf(msg("old1", 100.0), msg("old2", 105.0)))
            else ApiResult.Ok(listOf(msg("new1", 5000.0)))
        }
        val vm = newViewModel()
        vm.openChat(alice, "Test Chat")

        vm.focusHit(hit("old2", 105))

        assertEquals(listOf("old1", "old2"), vm.messages.map { it.id })
        assertEquals("old2", vm.focusId)
        assertFalse(vm.jumpingDate)
    }

    // -- back to results -------------------------------------------------------------------------

    private val trip = WaConvResult(nodeId = 11, chat = alice, chatName = "Invented", startTs = 1000, endTs = 1600, nMsgs = 3)

    /** The Search view open on a Conversations query with one result, as a user would have it before tapping Show in chat. */
    private fun searchWithResult(): WhatsAppViewModel {
        repo.messagesResponder = { ApiResult.Ok(listOf(msg("m1", 1000.0))) }
        repo.chatSearchStatusResult = ApiResult.Ok(WaChatSearchStatus(enabled = true, nodeVectors = 5))
        repo.chatSearchResult = ApiResult.Ok(WaChatSearchResponse(results = listOf(trip)))
        val vm = newViewModel()
        vm.search.openView()
        vm.search.viewShown()
        vm.search.openFor("trip")
        return vm
    }

    @Test
    fun `a chat opened from a result shows Back to results, and coming back restores the whole view`() {
        val vm = searchWithResult()
        vm.search.conv.openCard(0)
        vm.search.scroll.save(SearchMode.CONVERSATIONS, 4, 30)
        assertFalse(vm.search.backToResultsShown(vm.currentJid))

        vm.openConversation(trip)
        assertEquals(alice, vm.currentJid)
        assertTrue(vm.search.backToResultsShown(vm.currentJid))

        vm.backToResults()

        assertNull(vm.currentJid)
        assertFalse(vm.search.backToResultsShown(vm.currentJid))
        assertTrue(vm.search.open)
        assertEquals("trip", vm.search.query)
        assertEquals(SearchMode.CONVERSATIONS, vm.search.mode)
        assertEquals(listOf(trip), vm.search.conv.results)
        assertEquals(0, vm.search.conv.cardSheet?.index)          // the open card is still open
        assertEquals(ScrollPos(4, 30), vm.search.scroll.restore(SearchMode.CONVERSATIONS))
    }

    @Test
    fun `a Messages hit and a card moment open from search too`() {
        val vm = searchWithResult()
        vm.openChatAt(alice, "Invented", 1100, focusId = "m1")
        assertTrue(vm.search.backToResultsShown(vm.currentJid))
        vm.backToResults()
        assertNull(vm.currentJid)

        vm.openSearchHit(hit("m1", 1000))
        assertTrue(vm.search.backToResultsShown(vm.currentJid))
    }

    @Test
    fun `the pill goes when the chat is left another way`() {
        val vm = searchWithResult()
        vm.openConversation(trip)
        assertTrue(vm.search.backToResultsShown(vm.currentJid))

        vm.openChat(bob, "Bob")
        assertFalse(vm.search.backToResultsShown(vm.currentJid))
        assertNull(vm.search.originChat)

        vm.openConversation(trip)
        vm.closeChat()
        assertNull(vm.search.originChat)
        assertTrue(vm.search.open)                 // the header's back also lands on the results, as before
    }

    @Test
    fun `a chat opened with the Search view closed has no pill`() {
        repo.messagesResponder = { ApiResult.Ok(listOf(msg("m1", 1000.0))) }
        val vm = newViewModel()
        vm.openChatAt(alice, "Test Chat", 1000, "m1")
        assertFalse(vm.search.backToResultsShown(vm.currentJid))
        vm.backToResults()
        assertEquals(alice, vm.currentJid)         // nothing to go back to: the call does nothing
    }

    @Test
    fun `a new query starts the list at the top, closing the view drops the open card`() {
        val vm = searchWithResult()
        vm.search.scroll.save(SearchMode.CONVERSATIONS, 6, 0)
        vm.search.submit()
        assertEquals(ScrollPos(), vm.search.scroll.restore(SearchMode.CONVERSATIONS))

        vm.search.conv.openCard(0)
        vm.search.closeView()
        assertNull(vm.search.conv.cardSheet)
    }

    @Test
    fun `clearing all Messages filters turns the chips off and re-runs the query`() {
        repo.searchResponder = { ApiResult.Ok(WaSearchResponse(results = listOf(hit("m1", 1000)), total = 1)) }
        val vm = newViewModel()
        vm.search.openFor("hello")
        vm.search.pressChip(SearchChip.PHOTOS)
        vm.search.pressChip(SearchChip.MINE)
        assertEquals(2, vm.search.activeFilters)

        vm.search.clearAllFilters()

        assertEquals(0, vm.search.activeFilters)
        assertTrue(vm.search.messages.chips.none)
    }
}

