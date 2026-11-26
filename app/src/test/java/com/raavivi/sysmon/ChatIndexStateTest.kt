package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.CiCardCounts
import com.raavivi.sysmon.core.model.CiCardFull
import com.raavivi.sysmon.core.model.CiCardRef
import com.raavivi.sysmon.core.model.CiCardRow
import com.raavivi.sysmon.core.model.CiCardsPage
import com.raavivi.sysmon.core.model.CiDb
import com.raavivi.sysmon.core.model.CiFamiliesPage
import com.raavivi.sysmon.core.model.CiFamily
import com.raavivi.sysmon.core.model.CiMessage
import com.raavivi.sysmon.core.model.CiMessagesPage
import com.raavivi.sysmon.core.model.CiMessageRef
import com.raavivi.sysmon.core.model.CiMoment
import com.raavivi.sysmon.core.model.CiNode
import com.raavivi.sysmon.core.model.CiNodeCounts
import com.raavivi.sysmon.core.model.CiNodesPage
import com.raavivi.sysmon.core.model.CiOverview
import com.raavivi.sysmon.core.model.CiStorage
import com.raavivi.sysmon.core.model.CiTable
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaPos
import com.raavivi.sysmon.core.model.WaTopicCard
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.ui.chatindex.BrowseList
import com.raavivi.sysmon.ui.chatindex.BrowseMode
import com.raavivi.sysmon.ui.chatindex.ChatIndexState
import com.raavivi.sysmon.ui.chatindex.CiTab
import com.raavivi.sysmon.ui.chatindex.DbStatus
import com.raavivi.sysmon.ui.chatindex.LoadPhase
import com.raavivi.sysmon.ui.chatindex.storageCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Chat index screen's state (overview, browse, detail, storage) over the fake repository. Made-up chats only. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatIndexStateTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeChatIndexRepository()
    private val toasts = mutableListOf<String>()
    private lateinit var st: ChatIndexState

    private val chat1 = CiFamily(id = 1, jids = listOf("27820000001@s.whatsapp.net"), name = "Chat One", messages = 120)
    private val chat2 = CiFamily(id = 2, jids = listOf("27820000002@s.whatsapp.net"), name = "", messages = 30)

    private fun node(id: Long, level: Int = 0, children: Int = 0, card: CiCardRef? = null, parent: Long? = null) = CiNode(
        id = id, familyId = 1, parentId = parent, level = level, start = WaPos(1_700_000_000, 1), end = WaPos(1_700_000_600, 9),
        nMsgs = 5, hasVector = true, nChildren = children, card = card,
    )

    private fun wa(id: String) = WaMessage(
        id = id, chat = chat1.jids[0], chatName = "", sender = "27820000002@s.whatsapp.net", senderName = "Sam", fromMe = false,
        ts = 1_700_000_000.0, text = "text $id", mediaType = null, filename = null, downloaded = false,
    )

    private fun cardFull(id: Long, origin: String = "auto", nodeId: Long? = null, moments: List<CiMoment> = emptyList()) = CiCardFull(
        id = id, nMsgs = 5, origin = origin, nodeId = nodeId, instruction = if (origin == "manual") "the trip plans" else null,
        card = WaTopicCard(title = "Trip plans", summary = "They chose a date."), moments = moments,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        st = ChatIndexState(repo, CoroutineScope(dispatcher)) { toasts += it }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // -- overview and off ----------------------------------------------------------------------------

    @Test
    fun `starting reads the overview first`() {
        st.start()
        assertEquals(LoadPhase.READY, st.overview.phase)
        assertTrue(st.overview.value!!.indexExists)
        assertFalse(st.off)
        assertEquals(0, repo.storageCalls)
    }

    @Test
    fun `no index file is an overview with index_exists false`() {
        repo.overviewResult = ApiResult.Ok(CiOverview(indexExists = false, cards = CiCardCounts()))
        st.start()
        assertFalse(st.overview.value!!.indexExists)
        assertEquals(LoadPhase.READY, st.overview.phase)
    }

    @Test
    fun `a 404 chat search is off turns the whole screen off`() {
        repo.overviewResult = OFF_ERR
        st.start()
        assertTrue(st.off)
    }

    @Test
    fun `a failing overview is an error with a retry, not off`() {
        repo.overviewResult = ApiResult.Err("Network error: down")
        st.start()
        assertFalse(st.off)
        assertEquals(LoadPhase.ERROR, st.overview.phase)
        assertEquals("Network error: down", st.overview.error)
        repo.overviewResult = ApiResult.Ok(CiOverview(indexExists = true))
        st.overview.load()
        assertEquals(LoadPhase.READY, st.overview.phase)
    }

    @Test
    fun `off found by another tab also turns the screen off`() {
        st.start()
        repo.storageResult = OFF_ERR
        st.select(CiTab.STORAGE)
        assertTrue(st.off)
    }

    @Test
    fun `each tab loads on its first open only, and Refresh reloads the open one`() {
        st.start()
        st.select(CiTab.STORAGE)
        st.select(CiTab.OVERVIEW)
        st.select(CiTab.STORAGE)
        assertEquals(1, repo.storageCalls)
        assertEquals(1, repo.overviewCalls)
        st.refresh()
        assertEquals(2, repo.storageCalls)
        st.select(CiTab.OVERVIEW)
        assertEquals(2, repo.overviewCalls)
    }

    // -- storage -------------------------------------------------------------------------------------

    @Test
    fun `storage keeps null row counts as virtual and flags a missing file`() {
        repo.storageResult = ApiResult.Ok(
            CiStorage(
                listOf(
                    CiDb("index", "chat_search.db", true, 1000, listOf(CiTable("nodes", 3), CiTable("node_vec", null))),
                    CiDb("cards", "cards.db", false, null),
                ),
            ),
        )
        st.start()
        st.select(CiTab.STORAGE)
        val cards = st.storage.value!!.dbs.map(::storageCard)
        assertEquals(listOf("3", "virtual"), cards[0].tables.map { it.rows })
        assertEquals(DbStatus.MISSING, cards[1].status)
    }

    // -- browse: chats, paging ------------------------------------------------------------------------

    @Test
    fun `the chat list pages with load more and never repeats a chat`() {
        repo.familiesResponder = { offset ->
            if (offset == 0) ApiResult.Ok(CiFamiliesPage(listOf(chat1, chat2), nextOffset = 2))
            else ApiResult.Ok(CiFamiliesPage(listOf(chat2, chat1.copy(id = 3)), nextOffset = null))
        }
        st.start()
        st.select(CiTab.BROWSE)
        val chats = st.browse.chats
        assertEquals(listOf(1L, 2L), chats.items.map { it.id })
        assertEquals(2, chats.next)
        chats.loadMore()
        assertEquals(listOf(1L, 2L, 3L), chats.items.map { it.id })
        assertNull(chats.next)
        assertEquals(listOf(0, 2), repo.familiesCalls)
        chats.loadMore() // nothing left: no call
        assertEquals(2, repo.familiesCalls.size)
    }

    @Test
    fun `a failed load more keeps the rows and offers a retry`() {
        var fail = true
        repo.familiesResponder = { offset ->
            when {
                offset == 0 -> ApiResult.Ok(CiFamiliesPage(listOf(chat1), nextOffset = 1))
                fail -> ApiResult.Err("Network error: down")
                else -> ApiResult.Ok(CiFamiliesPage(listOf(chat2), nextOffset = null))
            }
        }
        st.start()
        st.select(CiTab.BROWSE)
        val chats = st.browse.chats
        chats.loadMore()
        assertTrue(chats.moreFailed)
        assertEquals(listOf(1L), chats.items.map { it.id })
        fail = false
        chats.loadMore()
        assertFalse(chats.moreFailed)
        assertEquals(listOf(1L, 2L), chats.items.map { it.id })
    }

    @Test
    fun `an empty chat list is ready and empty, not an error`() {
        st.start()
        st.select(CiTab.BROWSE)
        assertEquals(LoadPhase.READY, st.browse.chats.phase)
        assertTrue(st.browse.chats.items.isEmpty())
    }

    // -- browse: drill-down ---------------------------------------------------------------------------

    private fun openBrowse() {
        repo.familiesResponder = { ApiResult.Ok(CiFamiliesPage(listOf(chat1, chat2), null)) }
        repo.sessionsResponder = { _, _ -> ApiResult.Ok(CiNodesPage(listOf(node(10, 0, children = 2)), null)) }
        repo.childrenResponder = { parent, _ ->
            when (parent) {
                10L -> ApiResult.Ok(CiNodesPage(listOf(node(11, 1, children = 1, card = CiCardRef(5, "Trip plans"), parent = 10)), null))
                else -> ApiResult.Ok(CiNodesPage(listOf(node(12, 2, parent = 11)), null))
            }
        }
        st.start()
        st.select(CiTab.BROWSE)
    }

    @Test
    fun `choosing a chat reads its sessions`() {
        openBrowse()
        st.browse.openChat(chat1)
        assertEquals(BrowseList.Sessions(1), st.browse.nav.list)
        assertEquals(listOf(10L), st.browse.nodeList!!.items.map { it.id })
        assertEquals(listOf(1L to 0), repo.sessionsCalls)
    }

    @Test
    fun `drilling session to topic to sub-topic and Back up again`() {
        openBrowse()
        st.browse.openChat(chat1)
        st.browse.openNode(st.browse.nodeList!!.items[0])
        assertNotNull(st.browse.detail)
        st.browse.drill(st.browse.nodeList!!.items[0])
        assertNull(st.browse.detail)
        assertEquals(BrowseList.Children(10, 0), st.browse.nav.list)
        val topic = st.browse.nodeList!!.items[0]
        assertEquals("Trip plans", topic.card?.title)
        st.browse.openNode(topic)
        st.browse.drill(topic)
        assertEquals(BrowseList.Children(11, 1), st.browse.nav.list)
        assertEquals(listOf(12L), st.browse.nodeList!!.items.map { it.id })

        assertTrue(st.browse.back())
        assertEquals(BrowseList.Children(10, 0), st.browse.nav.list)
        assertTrue(st.browse.back())
        assertEquals(BrowseList.Sessions(1), st.browse.nav.list)
        assertTrue(st.browse.back())
        assertEquals(BrowseList.Chats, st.browse.nav.list)
        assertFalse(st.browse.back())
    }

    @Test
    fun `stepping back to a list already read does not read it again`() {
        openBrowse()
        st.browse.openChat(chat1)
        val session = st.browse.nodeList!!.items[0]
        st.browse.openNode(session)
        st.browse.drill(session)
        st.browse.back()
        assertEquals(1, repo.sessionsCalls.size)
        assertEquals(1, repo.childrenCalls.size)
    }

    @Test
    fun `system Back is handled inside Browse only while there is somewhere to go`() {
        openBrowse()
        assertFalse(st.handlesBack)
        st.browse.openChat(chat1)
        assertTrue(st.handlesBack)
        st.select(CiTab.STORAGE)
        assertFalse(st.handlesBack)
        assertFalse(st.back())
        st.select(CiTab.BROWSE)
        assertTrue(st.back())
        assertEquals(BrowseList.Chats, st.browse.nav.list)
    }

    @Test
    fun `a breadcrumb tap jumps straight up`() {
        openBrowse()
        st.browse.openChat(chat1)
        val session = st.browse.nodeList!!.items[0]
        st.browse.openNode(session)
        st.browse.drill(session)
        val crumbs = com.raavivi.sysmon.ui.chatindex.crumbs(st.browse.nav)
        st.browse.goTo(crumbs[1])
        assertEquals(BrowseList.Sessions(1), st.browse.nav.list)
        st.browse.goTo(crumbs[0])
        assertEquals(BrowseList.Chats, st.browse.nav.list)
    }

    @Test
    fun `the Cards mode lists the chat's cards, manual ones marked`() {
        openBrowse()
        repo.cardsResponder = { _, _ ->
            ApiResult.Ok(
                CiCardsPage(
                    listOf(
                        CiCardRow(id = 5, title = "Trip plans", nMsgs = 5),
                        CiCardRow(id = 6, title = "Mine", nMsgs = 3, origin = "manual"),
                    ),
                    null,
                ),
            )
        }
        st.browse.openChat(chat1)
        st.browse.setMode(BrowseMode.CARDS)
        assertEquals(BrowseList.Cards(1), st.browse.nav.list)
        assertEquals(listOf(false, true), st.browse.cardList!!.items.map { it.isManual })
        st.browse.setMode(BrowseMode.SESSIONS)
        assertEquals(BrowseList.Sessions(1), st.browse.nav.list)
    }

    // -- node detail ---------------------------------------------------------------------------------

    @Test
    fun `a node with a card reads the card and its messages`() {
        openBrowse()
        repo.cardResults[5] = ApiResult.Ok(cardFull(5))
        repo.messagesResponder = { id, offset ->
            if (offset == 0) ApiResult.Ok(CiMessagesPage(id, listOf(CiMessage(100, wa("a")), CiMessage(101, wa("b"))), 2))
            else ApiResult.Ok(CiMessagesPage(id, listOf(CiMessage(101, wa("b")), CiMessage(102, wa("c"))), null))
        }
        st.browse.openChat(chat1)
        val session = st.browse.nodeList!!.items[0]
        st.browse.openNode(session)
        st.browse.drill(session)
        val topic = st.browse.nodeList!!.items[0]
        st.browse.openNode(topic)
        val d = st.browse.detail!!
        assertEquals(LoadPhase.READY, d.card.phase)
        assertEquals("Trip plans", d.card.value!!.card!!.title)
        assertEquals(listOf(100L, 101L), d.messages.items.map { it.rowid })
        d.messages.loadMore()
        assertEquals(listOf(100L, 101L, 102L), d.messages.items.map { it.rowid })
        assertEquals(listOf(10L to 0, 11L to 0, 11L to 2), repo.messagesCalls)
    }

    @Test
    fun `a node without a card reads no card`() {
        openBrowse()
        st.browse.openChat(chat1)
        st.browse.openNode(st.browse.nodeList!!.items[0])
        assertEquals(0, repo.cardCalls.size)
        assertEquals(LoadPhase.IDLE, st.browse.detail!!.card.phase)
        assertFalse(st.browse.detail!!.canDelete)
    }

    @Test
    fun `messages that cannot be read say so and can be retried`() {
        openBrowse()
        repo.messagesResponder = { _, _ -> ApiResult.Err("messages unavailable", 503) }
        st.browse.openChat(chat1)
        st.browse.openNode(st.browse.nodeList!!.items[0])
        val d = st.browse.detail!!
        assertEquals(LoadPhase.ERROR, d.messages.phase)
        assertEquals("The messages cannot be read right now.", d.messages.error)
        repo.messagesResponder = { id, _ -> ApiResult.Ok(CiMessagesPage(id, listOf(CiMessage(1, wa("a"))), null)) }
        d.reloadMessages()
        assertEquals(LoadPhase.READY, d.messages.phase)
    }

    @Test
    fun `a card that is gone is Missing, not an error`() {
        openBrowse()
        repo.cardsResponder = { _, _ -> ApiResult.Ok(CiCardsPage(listOf(CiCardRow(id = 77, title = "Gone")), null)) }
        st.browse.openChat(chat1)
        st.browse.setMode(BrowseMode.CARDS)
        st.browse.openCard(st.browse.cardList!!.items[0])
        assertEquals(LoadPhase.MISSING, st.browse.detail!!.card.phase)
        assertFalse(st.off)
    }

    // -- a card and its delete -----------------------------------------------------------------------

    private fun openCard(id: Long, origin: String, nodeId: Long? = null) {
        openBrowse()
        repo.cardResults[id] = ApiResult.Ok(cardFull(id, origin, nodeId))
        repo.cardsResponder = { _, _ -> ApiResult.Ok(CiCardsPage(listOf(CiCardRow(id = id, title = "Trip plans", origin = origin)), null)) }
        st.browse.openChat(chat1)
        st.browse.setMode(BrowseMode.CARDS)
        st.browse.openCard(st.browse.cardList!!.items[0])
    }

    @Test
    fun `a card opened on its own reads the messages of its topic when it has one`() {
        repo.messagesResponder = { id, _ -> ApiResult.Ok(CiMessagesPage(id, listOf(CiMessage(1, wa("a"))), null)) }
        openCard(5, "auto", nodeId = 11)
        val d = st.browse.detail!!
        assertEquals(11L, d.messagesNode)
        assertEquals(1, d.messages.items.size)
    }

    @Test
    fun `a manual card has no topic and so no messages`() {
        openCard(6, "manual")
        val d = st.browse.detail!!
        assertNull(d.messagesNode)
        assertEquals(0, repo.messagesCalls.size)
    }

    @Test
    fun `Delete card is offered for a manual card only`() {
        openCard(5, "auto")
        assertFalse(st.browse.detail!!.canDelete)
        st.browse.detail!!.askDelete()
        assertFalse(st.browse.detail!!.confirming)
        st.browse.detail!!.confirmDelete()
        assertTrue(repo.deletedCards.isEmpty())
    }

    @Test
    fun `deleting a manual card asks first, then deletes and returns to the refreshed list`() {
        openCard(6, "manual")
        val d = st.browse.detail!!
        assertTrue(d.canDelete)
        d.askDelete()
        assertTrue(d.confirming)
        d.keepCard()
        assertFalse(d.confirming)
        assertTrue(repo.deletedCards.isEmpty())

        val readsBefore = repo.cardsCalls.size
        d.askDelete()
        d.confirmDelete()
        assertEquals(listOf(6L), repo.deletedCards)
        assertNull(st.browse.detail)
        assertEquals(BrowseList.Cards(1), st.browse.nav.list)
        assertEquals(listOf("Card deleted"), toasts)
        assertTrue(repo.cardsCalls.size > readsBefore)
    }

    @Test
    fun `a card that is already gone counts as deleted`() {
        openCard(6, "manual")
        repo.deleteCardResult = ApiResult.Err("no such card", 404)
        val d = st.browse.detail!!
        d.askDelete()
        d.confirmDelete()
        assertNull(st.browse.detail)
        assertEquals(listOf("Card deleted"), toasts)
    }

    @Test
    fun `a refused or failed delete stays on the card with the reason`() {
        openCard(6, "manual")
        val d = st.browse.detail!!
        repo.deleteCardResult = ApiResult.Err("not manual", 409, "not_manual")
        d.askDelete()
        d.confirmDelete()
        assertEquals("Only a card you asked for can be deleted.", d.deleteError)
        assertNotNull(st.browse.detail)
        repo.deleteCardResult = ApiResult.Err("Network error: down")
        d.askDelete()
        assertNull(d.deleteError)
        d.confirmDelete()
        assertEquals("The card could not be deleted. Try again.", d.deleteError)
        assertFalse(d.deleting)
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun `a moment with a message opens the chat at it and one without does not`() {
        val ref = CiMessageRef(rowid = 9, chatJid = chat1.jids[0], msgId = "3EB0AA", ts = 1_700_000_100)
        val data = cardFull(5, moments = listOf(CiMoment("plan", "A date", message = ref), CiMoment("idea", "No message")))
        val reqs = data.moments.map { com.raavivi.sysmon.ui.chatindex.momentRequest(it, "Chat One") }
        assertEquals(chat1.jids[0], reqs[0]!!.jid)
        assertEquals(1_700_000_100L, reqs[0]!!.ts)
        assertNull(reqs[1])
    }

    // -- refresh -------------------------------------------------------------------------------------

    @Test
    fun `Refresh on Browse reads the chat list and the current list again`() {
        openBrowse()
        st.browse.openChat(chat1)
        st.refresh()
        assertEquals(listOf(0, 0), repo.familiesCalls)
        assertEquals(2, repo.sessionsCalls.size)
        assertEquals(BrowseList.Sessions(1), st.browse.nav.list)
    }
}
