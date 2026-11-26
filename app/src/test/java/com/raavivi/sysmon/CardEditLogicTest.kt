package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.CiCardFull
import com.raavivi.sysmon.core.model.CiCardRef
import com.raavivi.sysmon.core.model.CiCardRow
import com.raavivi.sysmon.core.model.CiCardsPage
import com.raavivi.sysmon.core.model.CiEditOptions
import com.raavivi.sysmon.core.model.CiFamiliesPage
import com.raavivi.sysmon.core.model.CiFamily
import com.raavivi.sysmon.core.model.CiNode
import com.raavivi.sysmon.core.model.CiNodesPage
import com.raavivi.sysmon.core.model.WaPos
import com.raavivi.sysmon.core.model.WaTopicCard
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.ui.chatindex.CardEditLogic
import com.raavivi.sysmon.ui.chatindex.ChatIndexState
import com.raavivi.sysmon.ui.chatindex.CiTab
import com.raavivi.sysmon.ui.chatindex.Line
import com.raavivi.sysmon.ui.chatindex.ListKey
import com.raavivi.sysmon.ui.chatindex.Loc
import com.raavivi.sysmon.ui.chatindex.MAIN_ID
import com.raavivi.sysmon.ui.chatindex.addItem
import com.raavivi.sysmon.ui.chatindex.setTitle
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

/**
 * The card editor's flow (save, reload, cancel, undo) and its entry from the card view, over the fake
 * repository. Made-up chats and cards only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardEditLogicTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeChatIndexRepository()
    private val toasts = mutableListOf<String>()
    private lateinit var st: ChatIndexState

    private var saved: CiCardFull? = null
    private var closed = 0

    private val opts = CiEditOptions()

    private fun card(title: String = "Trip plans", updatedAt: Long = 1_700_000_000, editedAt: Long? = null, state: String = "done") = CiCardFull(
        id = 5, nMsgs = 8, state = state, updatedAt = updatedAt, editedAt = editedAt, nodeId = 11,
        card = if (state == "done") WaTopicCard(title = title, summary = "They chose a date.", recallQueries = listOf("a", "b", "c")) else null,
    )

    private fun editor(c: CiCardFull = card()) = CardEditLogic(repo, CoroutineScope(dispatcher), c, opts, { saved = it }, { closed++ })

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        st = ChatIndexState(repo, CoroutineScope(dispatcher)) { toasts += it }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // -- save ---------------------------------------------------------------------------------------------

    @Test
    fun `saving sends the draft with the card's updated_at and hands back the saved card`() {
        val e = editor()
        val savedCard = card(title = "Trip plans 2", updatedAt = 1_700_000_900, editedAt = 1_700_000_900)
        repo.editResponder = { _, _ -> ApiResult.Ok(savedCard) }
        e.edit { setTitle(MAIN_ID, "Trip plans 2") }
        assertTrue(e.dirty)
        e.save()
        assertEquals(1, repo.edits.size)
        val (id, body) = repo.edits[0]
        assertEquals(5L, id)
        assertEquals("Trip plans 2", body.card.title)
        assertEquals(1_700_000_000L, body.updatedAt)
        assertEquals(savedCard, saved)
        assertFalse(e.saving)
        assertNull(e.banner)
    }

    @Test
    fun `a stale save shows the banner with Reload and keeps the draft`() {
        val e = editor()
        repo.editResponder = { _, _ -> ApiResult.Err("stale", 409, "stale") }
        e.edit { setTitle(MAIN_ID, "Mine") }
        e.save()
        val b = e.banner!!
        assertTrue(b.canReload)
        assertTrue(b.text.startsWith("This card changed since you opened it."))
        assertEquals("Mine", e.draft.main.title)
        assertNull(saved)
        assertFalse(e.saving)
    }

    @Test
    fun `Reload replaces the draft with the card as it is now and takes the new updated_at`() {
        val e = editor()
        repo.editResponder = { _, _ -> ApiResult.Err("stale", 409, "stale") }
        e.edit { setTitle(MAIN_ID, "Mine") }
        e.save()
        repo.cardResults[5] = ApiResult.Ok(card(title = "Theirs", updatedAt = 1_700_000_800))
        e.reload()
        assertEquals("Theirs", e.draft.main.title)
        assertFalse(e.dirty)
        assertNull(e.banner)
        assertEquals(1_700_000_800L, e.base.updatedAt)
        repo.editResponder = { _, b -> ApiResult.Ok(card(updatedAt = b.updatedAt + 1)) }
        e.edit { setTitle(MAIN_ID, "Mine again") }
        e.save()
        assertEquals(1_700_000_800L, repo.edits.last().second.updatedAt)
        assertNotNull(saved)
    }

    @Test
    fun `a failed reload says so and keeps the draft`() {
        val e = editor()
        e.edit { setTitle(MAIN_ID, "Mine") }
        repo.cardResults[5] = ApiResult.Err("Network error: down")
        e.reload()
        assertEquals("Mine", e.draft.main.title)
        assertTrue(e.banner!!.canReload)
        assertFalse(e.reloading)
    }

    @Test
    fun `a refused save keeps the draft and says the server's reason without Reload`() {
        val e = editor()
        repo.editResponder = { _, _ -> ApiResult.Err("the recall queries must number 3 to 5", 400) }
        e.edit { setTitle(MAIN_ID, "Mine") }
        e.save()
        val b = e.banner!!
        assertEquals("The recall queries must number 3 to 5.", b.text)
        assertFalse(b.canReload)
        assertEquals("Mine", e.draft.main.title)
        assertNull(saved)
        e.save()
        assertEquals(2, repo.edits.size)
    }

    @Test
    fun `other failures keep the draft with a sentence`() {
        for (err in listOf(
            ApiResult.Err("cards_write_failed", 503),
            ApiResult.Err("no such card", 404),
            ApiResult.Err("failed", 409, "not_done"),
            ApiResult.Err("Network error: down"),
        )) {
            val e = editor()
            repo.editResponder = { _, _ -> err }
            e.edit { setTitle(MAIN_ID, "Mine") }
            e.save()
            assertTrue(e.banner!!.text.isNotBlank())
            assertFalse(e.banner!!.canReload)
            assertEquals("Mine", e.draft.main.title)
        }
    }

    @Test
    fun `a read-back failure offers Reload and warns against saving again`() {
        val e = editor()
        repo.editResponder = { _, _ -> ApiResult.Err("the edited card could not be read back", 500) }
        e.save()
        assertTrue(e.banner!!.canReload)
        assertTrue(e.banner!!.text.contains("do not save again"))
    }

    // -- cancel -------------------------------------------------------------------------------------------

    @Test
    fun `cancel with no changes leaves at once`() {
        val e = editor()
        e.cancel()
        assertEquals(1, closed)
        assertFalse(e.confirmingDiscard)
    }

    @Test
    fun `cancel with changes asks, Keep editing stays and Discard leaves`() {
        val e = editor()
        e.edit { setTitle(MAIN_ID, "Mine") }
        e.cancel()
        assertTrue(e.confirmingDiscard)
        assertEquals(0, closed)
        e.keepEditing()
        assertFalse(e.confirmingDiscard)
        assertEquals("Mine", e.draft.main.title)
        e.cancel()
        e.discard()
        assertEquals(1, closed)
        assertTrue(repo.edits.isEmpty())
    }

    @Test
    fun `changing a thing back is no change`() {
        val e = editor()
        e.edit { setTitle(MAIN_ID, "Other") }
        e.edit { setTitle(MAIN_ID, "Trip plans") }
        e.cancel()
        assertEquals(1, closed)
    }

    // -- remove and undo ----------------------------------------------------------------------------------

    @Test
    fun `removing a line announces it, and Undo puts it back`() {
        val e = editor()
        val loc = Loc.main(ListKey.RECALL)
        val id = e.draft.recall[0].id
        e.remove(loc, id)
        assertEquals(2, e.draft.recall.size)
        assertEquals(1, e.removalSeq)
        assertNotNull(e.lastRemoval)
        e.undoRemoval()
        assertEquals(listOf("a", "b", "c"), e.draft.recall.map { it.text })
        assertNull(e.lastRemoval)
        assertFalse(e.dirty)
    }

    @Test
    fun `each removal moves the counter so the snackbar shows again`() {
        val e = editor()
        val loc = Loc.main(ListKey.RECALL)
        e.remove(loc, e.draft.recall[0].id)
        e.remove(loc, e.draft.recall[0].id)
        assertEquals(2, e.removalSeq)
        e.remove(loc, 9999)
        assertEquals(2, e.removalSeq)
    }

    @Test
    fun `sections stay open once opened`() {
        val e = editor()
        e.toggleSection("m:said")
        assertTrue("m:said" in e.expanded)
        e.edit { addItem(Loc.main(ListKey.SAID)) { Line(it, "x") } }
        assertTrue("m:said" in e.expanded)
        e.toggleSection("m:said")
        assertFalse("m:said" in e.expanded)
    }

    // -- the entry from the card view ---------------------------------------------------------------------

    private val chat1 = CiFamily(id = 1, jids = listOf("27820000001@s.whatsapp.net"), name = "Chat One", messages = 120)

    private fun openCardDetail(c: CiCardFull = card()) {
        repo.familiesResponder = { ApiResult.Ok(CiFamiliesPage(listOf(chat1), null)) }
        repo.cardsResponder = { _, _ ->
            ApiResult.Ok(CiCardsPage(listOf(CiCardRow(id = c.id, title = c.card?.title.orEmpty(), state = c.state)), null))
        }
        repo.cardResults[c.id] = ApiResult.Ok(c)
        st.start()
        st.select(CiTab.BROWSE)
        st.browse.openChat(chat1)
        st.browse.setMode(com.raavivi.sysmon.ui.chatindex.BrowseMode.CARDS)
        st.browse.openCard(st.browse.cardList!!.items[0])
    }

    @Test
    fun `Edit card reads the options and opens the editor`() {
        openCardDetail()
        val d = st.browse.detail!!
        assertTrue(d.canEdit)
        d.openEditor()
        assertEquals(1, repo.editOptionsCalls)
        assertNotNull(d.editor)
        assertFalse(d.openingEditor)
    }

    @Test
    fun `a failure reading the options stays on the card and says so`() {
        openCardDetail()
        repo.editOptionsResult = ApiResult.Err("Network error: down")
        val d = st.browse.detail!!
        d.openEditor()
        assertNull(d.editor)
        assertFalse(d.openingEditor)
        assertEquals(listOf("Could not open the editor"), toasts)
        assertNotNull(st.browse.detail)
    }

    @Test
    fun `a failed card has no Edit card`() {
        openCardDetail(card(state = "failed"))
        val d = st.browse.detail!!
        assertFalse(d.canEdit)
        d.openEditor()
        assertEquals(0, repo.editOptionsCalls)
    }

    @Test
    fun `saving shows the saved card, closes the editor, says Card saved and updates the list row`() {
        openCardDetail()
        val d = st.browse.detail!!
        d.openEditor()
        val e = d.editor!!
        val savedCard = card(title = "Trip plans 2", updatedAt = 1_700_000_900, editedAt = 1_700_000_900)
        repo.editResponder = { _, _ -> ApiResult.Ok(savedCard) }
        e.edit { setTitle(MAIN_ID, "Trip plans 2") }
        e.save()
        assertNull(d.editor)
        assertEquals(savedCard, d.card.value)
        assertEquals(listOf("Card saved"), toasts)
        val row = st.browse.cardList!!.items[0]
        assertEquals("Trip plans 2", row.title)
        assertEquals(1_700_000_900L, row.editedAt)
        assertEquals(1_700_000_900L, row.updatedAt)
        assertNotNull(st.browse.detail)
    }

    @Test
    fun `saving updates the title a topic row shows`() {
        repo.familiesResponder = { ApiResult.Ok(CiFamiliesPage(listOf(chat1), null)) }
        val topic = CiNode(
            id = 11, familyId = 1, level = 1, start = WaPos(1_700_000_000, 1), end = WaPos(1_700_000_600, 9),
            nMsgs = 5, card = CiCardRef(5, "Trip plans"),
        )
        repo.sessionsResponder = { _, _ -> ApiResult.Ok(CiNodesPage(listOf(topic), null)) }
        repo.cardResults[5] = ApiResult.Ok(card())
        st.start()
        st.select(CiTab.BROWSE)
        st.browse.openChat(chat1)
        st.browse.openNode(st.browse.nodeList!!.items[0])
        val d = st.browse.detail!!
        d.openEditor()
        repo.editResponder = { _, _ -> ApiResult.Ok(card(title = "Renamed", editedAt = 1_700_000_900)) }
        d.editor!!.save()
        assertEquals("Renamed", st.browse.nodeList!!.items[0].card?.title)
    }

    @Test
    fun `leaving the card drops the editor and a reply still in flight is ignored`() {
        openCardDetail()
        val d = st.browse.detail!!
        d.openEditor()
        val e = d.editor!!
        st.browse.back()
        assertNull(d.editor)
        e.close()
        assertTrue(toasts.isEmpty())
    }
}
