package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.chatindex.ChatIndexError
import com.raavivi.sysmon.core.data.chatindex.chatIndexErrorOf
import com.raavivi.sysmon.core.data.chatindex.isChatSearchOff
import com.raavivi.sysmon.core.model.CiCardRef
import com.raavivi.sysmon.core.model.CiCardRow
import com.raavivi.sysmon.core.model.CiCardCounts
import com.raavivi.sysmon.core.model.CiDb
import com.raavivi.sysmon.core.model.CiFamily
import com.raavivi.sysmon.core.model.CiMessage
import com.raavivi.sysmon.core.model.CiMessageRef
import com.raavivi.sysmon.core.model.CiMoment
import com.raavivi.sysmon.core.model.CiNode
import com.raavivi.sysmon.core.model.CiNodeCounts
import com.raavivi.sysmon.core.model.CiOverview
import com.raavivi.sysmon.core.model.CiTable
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaPos
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.ui.chatindex.BrowseDetail
import com.raavivi.sysmon.ui.chatindex.BrowseList
import com.raavivi.sysmon.ui.chatindex.BrowseMode
import com.raavivi.sysmon.ui.chatindex.BrowseNav
import com.raavivi.sysmon.ui.chatindex.CHAT_SEARCH_OFF_TEXT
import com.raavivi.sysmon.ui.chatindex.DbStatus
import com.raavivi.sysmon.ui.chatindex.PageOf
import com.raavivi.sysmon.ui.chatindex.canDeleteCard
import com.raavivi.sysmon.ui.chatindex.cardRowLabel
import com.raavivi.sysmon.ui.chatindex.countText
import com.raavivi.sysmon.ui.chatindex.crumbs
import com.raavivi.sysmon.ui.chatindex.familyLabel
import com.raavivi.sysmon.ui.chatindex.loadErrorText
import com.raavivi.sysmon.ui.chatindex.mergePage
import com.raavivi.sysmon.ui.chatindex.messageView
import com.raavivi.sysmon.ui.chatindex.momentRequest
import com.raavivi.sysmon.ui.chatindex.momentText
import com.raavivi.sysmon.ui.chatindex.nodeFacts
import com.raavivi.sysmon.ui.chatindex.nodeLabel
import com.raavivi.sysmon.ui.chatindex.nodeSubtitle
import com.raavivi.sysmon.ui.chatindex.overviewCells
import com.raavivi.sysmon.ui.chatindex.overviewGroups
import com.raavivi.sysmon.ui.chatindex.rangeText
import com.raavivi.sysmon.ui.chatindex.storageCard
import com.raavivi.sysmon.ui.chatindex.storageLine
import com.raavivi.sysmon.ui.chatindex.vectorLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The Chat index screen's pure logic. Made-up chats and numbers only. */
class ChatIndexLogicTest {

    private val utc = ZoneId.of("UTC")
    private val joburg = ZoneId.of("Africa/Johannesburg")

    // 2023-11-14 22:13:20 UTC
    private val t0 = 1_700_000_000L

    private fun node(
        id: Long, level: Int = 0, start: Long = t0, end: Long = t0 + 600, n: Int = 5, vec: Boolean = true,
        children: Int = 0, card: CiCardRef? = null,
    ) = CiNode(
        id = id, familyId = 1, level = level, start = WaPos(start, 10), end = WaPos(end, 20),
        nMsgs = n, hasVector = vec, nChildren = children, card = card,
    )

    private val family = CiFamily(id = 7, jids = listOf("27820000001@s.whatsapp.net"), name = "Chat One", messages = 120)

    // -- time of a {ts, rowid} span ------------------------------------------------------------------

    @Test
    fun `a moment reads as local day and clock`() {
        assertEquals("14 Nov 2023 22:13", momentText(t0, utc))
        assertEquals("15 Nov 2023 00:13", momentText(t0, joburg))
        assertEquals("—", momentText(0, utc))
    }

    @Test
    fun `a span inside one day shows the day once`() {
        assertEquals("14 Nov 2023 22:13–22:23", rangeText(WaPos(t0, 1), WaPos(t0 + 600, 9), utc))
    }

    @Test
    fun `a span over midnight shows both days and the rowid never shows`() {
        val text = rangeText(WaPos(t0, 123456), WaPos(t0 + 3 * 3600, 123999), utc)
        assertEquals("14 Nov 2023 22:13 – 15 Nov 2023 01:13", text)
        assertFalse(text.contains("123456"))
    }

    @Test
    fun `a span with no start is a dash and no end falls back to the start`() {
        assertEquals("—", rangeText(WaPos(0, 0), WaPos(5, 0), utc))
        assertEquals("14 Nov 2023 22:13–22:13", rangeText(WaPos(t0, 1), WaPos(0, 0), utc))
    }

    // -- paging --------------------------------------------------------------------------------------

    @Test
    fun `page zero replaces and later pages append without repeats`() {
        val first = mergePage(emptyList(), PageOf(listOf(1, 2, 3), 3), 0) { it }
        assertEquals(listOf(1, 2, 3), first.items)
        assertEquals(3, first.next)
        val second = mergePage(first.items, PageOf(listOf(3, 4), null), 3) { it }
        assertEquals(listOf(1, 2, 3, 4), second.items)
        assertNull(second.next)
        val again = mergePage(second.items, PageOf(listOf(9), 1), 0) { it }
        assertEquals(listOf(9), again.items)
    }

    // -- overview and storage ------------------------------------------------------------------------

    @Test
    fun `the overview lists every count, grouped`() {
        val o = CiOverview(
            indexExists = true, families = 3, nodes = CiNodeCounts(10, 1234567, 8), messagesIndexed = 99,
            vectors = 5, nodeVectors = 6, cards = CiCardCounts(done = 4, failed = 1, outdated = 2),
        )
        val cells = overviewCells(o)
        assertEquals(10, cells.size)
        assertEquals("1,234,567", cells.first { it.label == "Topics" }.value)
        assertEquals("1", cells.first { it.label == "Cards failed" }.value)
        assertEquals(listOf("Index", "Search data", "Cards"), overviewGroups(o).map { it.title })
        assertEquals(10, overviewGroups(o).sumOf { it.cells.size })
        assertEquals("0", countText(0))
    }

    @Test
    fun `a storage card tells present missing and unreadable apart`() {
        val ok = storageCard(CiDb("index", "chat_search.db", true, 4_200_000, listOf(CiTable("nodes", 12), CiTable("node_vec", null))))
        assertEquals(DbStatus.PRESENT, ok.status)
        assertEquals("12", ok.tables[0].rows)
        assertEquals("virtual", ok.tables[1].rows)
        assertTrue(storageLine(ok).startsWith("Present · "))
        val gone = storageCard(CiDb("cards", "cards.db", false, null))
        assertEquals(DbStatus.MISSING, gone.status)
        assertEquals("Missing", storageLine(gone))
        val bad = storageCard(CiDb("wacli", "wacli.db", true, 2048, error = "unreadable"))
        assertEquals(DbStatus.UNREADABLE, bad.status)
        assertTrue(storageLine(bad).startsWith("Unreadable · "))
        assertEquals("Unreadable", storageLine(storageCard(CiDb("x", "x.db", true, null, error = "unreadable"))))
    }

    // -- node and card rows --------------------------------------------------------------------------

    @Test
    fun `a node row is its card title when it has one else its range`() {
        val plain = node(1)
        assertEquals("14 Nov 2023 22:13–22:23", nodeLabel(plain, utc))
        assertEquals("5 messages", nodeSubtitle(plain, utc))
        val carded = node(2, level = 1, children = 3, card = CiCardRef(9, "Planning the trip"))
        assertEquals("Planning the trip", nodeLabel(carded, utc))
        assertEquals("14 Nov 2023 22:13–22:23 · 5 messages · 3 sub-topics", nodeSubtitle(carded, utc))
        assertEquals("1 message", nodeSubtitle(node(3, n = 1), utc))
        assertEquals("5 messages · 2 topics", nodeSubtitle(node(4, children = 2), utc))
    }

    @Test
    fun `a blank card title falls back to the range`() {
        assertEquals("14 Nov 2023 22:13–22:23", nodeLabel(node(1, card = CiCardRef(1, " ")), utc))
    }

    @Test
    fun `the vector dot has a spoken label either way`() {
        assertEquals("Has a vector", vectorLabel(true))
        assertEquals("No vector", vectorLabel(false))
        assertEquals("Topic, 5 messages, has a vector", nodeFacts(node(1, level = 1)))
        assertEquals("Session, 1 message, no vector", nodeFacts(node(1, n = 1, vec = false)))
    }

    @Test
    fun `a card row says failed or untitled when there is no title`() {
        val base = CiCardRow(id = 1, title = "Dinner plans", nMsgs = 4)
        assertEquals("Dinner plans", cardRowLabel(base))
        assertEquals("Failed card", cardRowLabel(base.copy(state = "failed", title = "")))
        assertEquals("Untitled card", cardRowLabel(base.copy(title = "")))
    }

    @Test
    fun `a chat is named by its name then its first jid then its id`() {
        assertEquals("Chat One", familyLabel(family))
        assertEquals("27820000001@s.whatsapp.net", familyLabel(family.copy(name = "")))
        assertEquals("Chat 7", familyLabel(family.copy(name = "", jids = emptyList())))
    }

    // -- messages ------------------------------------------------------------------------------------

    private fun wa(text: String = "hello", media: String? = null, fromMe: Boolean = false, name: String = "Sam", ts: Double = t0.toDouble()) =
        WaMessage(
            id = "m1", chat = "27820000001@s.whatsapp.net", chatName = "", sender = "27820000002@s.whatsapp.net",
            senderName = name, fromMe = fromMe, ts = ts, text = text, mediaType = media, filename = null, downloaded = false,
        )

    @Test
    fun `a message row has sender time text and a media tag`() {
        val v = messageView(CiMessage(41, wa("see this", media = "image")), utc)
        assertEquals(41L, v.key)
        assertEquals("Sam", v.sender)
        assertEquals("14 Nov 2023 22:13", v.time)
        assertEquals("see this", v.text)
        assertEquals("[photo]", v.media)
        assertEquals("[voice message]", messageView(CiMessage(1, wa("", media = "ptt")), utc).media)
        assertEquals("[video]", messageView(CiMessage(1, wa("", media = "video")), utc).media)
        assertEquals("[sticker]", messageView(CiMessage(1, wa("", media = "sticker")), utc).media)
        assertEquals("", messageView(CiMessage(1, wa()), utc).media)
    }

    @Test
    fun `my own messages say You and an unnamed sender falls back to the number`() {
        assertEquals("You", messageView(CiMessage(1, wa(fromMe = true)), utc).sender)
        assertEquals("27820000002", messageView(CiMessage(1, wa(name = "")), utc).sender)
    }

    // -- the drill-down and breadcrumb ---------------------------------------------------------------

    @Test
    fun `choosing a chat lists its sessions and Back returns to the chat list`() {
        val nav = BrowseNav().intoFamily(family)
        assertEquals(BrowseList.Sessions(7), nav.list)
        assertTrue(nav.canGoBack)
        val home = nav.back()
        assertEquals(BrowseList.Chats, home.list)
        assertFalse(home.canGoBack)
        assertEquals(home, home.back())
    }

    @Test
    fun `drilling through session and topic builds the trail and Back unwinds it`() {
        val session = node(10, level = 0, children = 2)
        val topic = node(11, level = 1, start = t0 + 60, children = 1)
        var nav = BrowseNav().intoFamily(family)
        nav = nav.openNode(session)
        assertEquals(BrowseDetail.Node(session), nav.detail)
        nav = nav.drill(session, utc)
        assertNull(nav.detail)
        assertEquals(BrowseList.Children(10, 0), nav.list)
        nav = nav.openNode(topic).drill(topic, utc)
        assertEquals(BrowseList.Children(11, 1), nav.list)
        assertEquals(2, nav.trail.size)

        nav = nav.back()
        assertEquals(BrowseList.Children(10, 0), nav.list)
        nav = nav.back()
        assertEquals(BrowseList.Sessions(7), nav.list)
        assertNull(nav.detail)
        assertEquals(null, nav.back().family)
    }

    @Test
    fun `Back from a detail closes the detail first`() {
        val nav = BrowseNav().intoFamily(family).openNode(node(10))
        val back = nav.back()
        assertNull(back.detail)
        assertEquals(7L, back.family?.id)
    }

    @Test
    fun `switching to Cards lists the chat's cards and drops the trail`() {
        val nav = BrowseNav().intoFamily(family).openNode(node(10)).drill(node(10), utc).withMode(BrowseMode.CARDS)
        assertEquals(BrowseList.Cards(7), nav.list)
        assertTrue(nav.trail.isEmpty())
        assertNull(nav.detail)
    }

    @Test
    fun `the breadcrumb names every step and marks the current one`() {
        val session = node(10, level = 0, children = 2)
        val nav = BrowseNav().intoFamily(family).openNode(session).drill(session, utc)
        val c = crumbs(nav)
        assertEquals(listOf("Chats", "Chat One", "Session 14 Nov 2023 22:13–22:23"), c.map { it.label })
        assertEquals(listOf(false, false, true), c.map { it.current })
        // Tapping the chat crumb goes to the sessions; tapping Chats goes home.
        assertEquals(BrowseList.Sessions(7), c[1].nav.list)
        assertEquals(BrowseList.Chats, c[0].nav.list)
    }

    @Test
    fun `an open detail is the last crumb and the list crumbs are no longer current`() {
        val card = CiCardRow(id = 5, title = "Dinner plans", origin = "manual")
        val nav = BrowseNav().intoFamily(family).withMode(BrowseMode.CARDS).openCard(card)
        val c = crumbs(nav)
        assertEquals(listOf("Chats", "Chat One", "Cards", "Dinner plans"), c.map { it.label })
        assertEquals(listOf(false, false, false, true), c.map { it.current })
        assertEquals(BrowseDetail.Card(5, "Dinner plans", manual = true), nav.detail)
    }

    @Test
    fun `at the chat list the breadcrumb is just Chats`() {
        assertEquals(listOf("Chats"), crumbs(BrowseNav()).map { it.label })
    }

    // -- cards: moments and delete -------------------------------------------------------------------

    @Test
    fun `a moment opens its chat only when it points at a message`() {
        val ref = CiMessageRef(rowid = 9, chatJid = "27820000001@s.whatsapp.net", msgId = "3EB0AA", ts = t0)
        val req = momentRequest(CiMoment("plan", "We chose a date", message = ref), "Chat One")!!
        assertEquals("27820000001@s.whatsapp.net", req.jid)
        assertEquals(t0, req.ts)
        assertEquals("3EB0AA", req.msgId)
        assertEquals("Chat One", req.name)
        assertNull(momentRequest(CiMoment("plan", "No message kept")))
        assertNull(momentRequest(CiMoment("plan", "x", message = ref.copy(msgId = ""))))
        assertNull(momentRequest(CiMoment("plan", "x", message = ref.copy(chatJid = ""))))
        assertNull(momentRequest(CiMoment("plan", "x", message = ref.copy(ts = 0))))
    }

    @Test
    fun `only a card the owner asked for can be deleted`() {
        assertTrue(canDeleteCard("manual"))
        assertFalse(canDeleteCard("auto"))
        assertFalse(canDeleteCard(""))
    }

    // -- typed errors --------------------------------------------------------------------------------

    @Test
    fun `a 404 about chat search being off is Off and any other 404 is Missing`() {
        assertEquals(ChatIndexError.Off, chatIndexErrorOf(ApiResult.Err("chat search is off", 404)))
        assertTrue(isChatSearchOff(ApiResult.Err("Chat search is OFF", 404)))
        assertEquals(ChatIndexError.Missing, chatIndexErrorOf(ApiResult.Err("no such node", 404)))
        assertFalse(isChatSearchOff(ApiResult.Err("no such node", 404)))
        assertFalse(isChatSearchOff(ApiResult.Err("chat search is off", 500)))
    }

    @Test
    fun `409 codes and 400 reasons map to typed errors`() {
        assertEquals(ChatIndexError.Stale, chatIndexErrorOf(ApiResult.Err("changed", 409, "stale")))
        assertEquals(ChatIndexError.Duplicate, chatIndexErrorOf(ApiResult.Err("taken", 409, "duplicate")))
        assertEquals(ChatIndexError.NotManual, chatIndexErrorOf(ApiResult.Err("no", 409, "not_manual")))
        assertEquals(
            ChatIndexError.Refused("a word is too long (at most 80 characters)"),
            chatIndexErrorOf(ApiResult.Err("a word is too long (at most 80 characters)", 400)),
        )
        assertEquals(ChatIndexError.Refused("The request was refused"), chatIndexErrorOf(ApiResult.Err("HTTP 400", 400)))
        assertEquals(ChatIndexError.Unavailable, chatIndexErrorOf(ApiResult.Err("messages unavailable", 503)))
        assertTrue(chatIndexErrorOf(ApiResult.Err("boom", 500)) is ChatIndexError.Other)
    }

    @Test
    fun `a load failure reads as words for the screen`() {
        assertEquals(CHAT_SEARCH_OFF_TEXT, loadErrorText(OFF_ERR))
        assertEquals("It is not there any more.", loadErrorText(ApiResult.Err("no such node", 404)))
        assertEquals("The messages cannot be read right now.", loadErrorText(ApiResult.Err("messages unavailable", 503)))
        assertEquals("Network error: down", loadErrorText(ApiResult.Err("Network error: down")))
    }
}
