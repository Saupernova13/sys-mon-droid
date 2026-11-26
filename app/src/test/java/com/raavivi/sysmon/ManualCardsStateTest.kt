package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WaCardDetail
import com.raavivi.sysmon.core.model.WaManualJob
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.ui.whatsapp.CardPhase
import com.raavivi.sysmon.ui.whatsapp.MANUAL_POLL_MS
import com.raavivi.sysmon.ui.whatsapp.MANUAL_WATCH_MS
import com.raavivi.sysmon.ui.whatsapp.MAX_INSTRUCTION
import com.raavivi.sysmon.ui.whatsapp.ManualCardsState
import com.raavivi.sysmon.ui.whatsapp.YourCardRef
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

/** Selection, the Make card sheet and its job, the watcher, the card view and its delete, over the fake repository. Made-up data only. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualCardsStateTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeWhatsAppRepository()
    private val alice = "27820000001@s.whatsapp.net"
    private val toasts = mutableListOf<String>()
    private val deleted = mutableListOf<Long>()
    private lateinit var state: ManualCardsState

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        state = ManualCardsState(repo, CoroutineScope(dispatcher), { alice }, { "Test Chat" }, { toasts += it }, { deleted += it })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun advance(ms: Long) { dispatcher.scheduler.advanceTimeBy(ms); dispatcher.scheduler.runCurrent() }

    private fun msg(id: String, ts: Double) = WaMessage(
        id = id, chat = alice, chatName = "", sender = alice, senderName = "", fromMe = false,
        ts = ts, text = "hello", mediaType = null, filename = null, downloaded = false,
    )

    private fun job(state: String, id: Long = 1, waiting: String? = null, error: String? = null, card: Long? = null) =
        WaManualJob(id = id, state = state, chat = alice, nMsgs = 2, waiting = waiting, error = error, cardId = card)

    /** Two messages picked and the sheet open. */
    private fun openSheet() {
        state.selection.enter(msg("m2", 200.0))
        state.selection.tap(msg("m1", 100.0))
        state.makeCard()
    }

    // -- selection mode -----------------------------------------------------------------------------------

    @Test
    fun `entering picks the message and a chat change leaves`() {
        state.selection.enter(msg("m1", 100.0))
        assertTrue(state.selection.active)
        assertEquals(1, state.selection.count)
        state.selection.tap(msg("m2", 200.0))
        assertEquals(2, state.selection.count)

        state.onChatChanged()

        assertFalse(state.selection.active)
        assertEquals(0, state.selection.count)
    }

    @Test
    fun `a message that cannot be picked never enters or toggles`() {
        state.selection.enter(msg("", 1.0))
        assertFalse(state.selection.active)
        state.selection.enter(msg("m1", 1.0))
        state.selection.tap(msg("", 2.0))
        assertEquals(1, state.selection.count)
    }

    @Test
    fun `Make card opens only with 1 to 400 picked`() {
        state.makeCard()
        assertNull(state.sheet)

        state.selection.enter(msg("m1", 100.0))
        repeat(400) { state.selection.tap(msg("x$it", 101.0 + it)) }   // 401 picked
        assertEquals(401, state.selection.count)
        state.makeCard()
        assertNull(state.sheet)

        state.selection.tap(msg("x0", 101.0))   // 400
        state.makeCard()
        assertEquals(400, state.sheet!!.req.ids.size)
    }

    // -- the sheet and its job ----------------------------------------------------------------------------------

    @Test
    fun `the sheet holds the picked ids oldest first with their span`() {
        openSheet()
        val req = state.sheet!!.req
        assertEquals(alice, req.chat)
        assertEquals(listOf("m1", "m2"), req.ids)
        assertEquals(100L, req.first)
        assertEquals(200L, req.last)
    }

    @Test
    fun `the description is cut at 500 and sent trimmed, or not at all when blank`() {
        openSheet()
        state.setText("y".repeat(MAX_INSTRUCTION + 20))
        assertEquals(MAX_INSTRUCTION, state.sheet!!.text.length)
        state.setText("  the pottery plan  ")
        repo.createManualResult = ApiResult.Ok(job("queued"))
        state.submit()
        assertEquals("the pottery plan", repo.manualRequests.single().third)
        assertEquals(listOf("m1", "m2"), repo.manualRequests.single().second)

        state.dismissSheet()
        openSheet()
        state.setText("   ")
        state.submit()
        assertNull(repo.manualRequests.last().third)
    }

    @Test
    fun `a request accepted ends selection and follows the job`() {
        repo.createManualResult = ApiResult.Ok(job("queued", waiting = "gpu"))
        openSheet()
        state.submit()

        assertFalse(state.selection.active)
        assertEquals("queued", state.sheet!!.job!!.state)
        assertFalse(state.sheet!!.busy)
    }

    @Test
    fun `a 400 shows the server's reason and keeps the form and selection`() {
        repo.createManualResult = ApiResult.Err("a message was deleted", 400)
        openSheet()
        state.submit()

        assertEquals("a message was deleted", state.sheet!!.error)
        assertNull(state.sheet!!.job)
        assertFalse(state.sheet!!.busy)
        assertTrue(state.selection.active)
    }

    @Test
    fun `409 and 404 refusals read as on the web`() {
        openSheet()
        repo.createManualResult = ApiResult.Err("x", 409, "too_many")
        state.submit()
        assertTrue(state.sheet!!.error!!.startsWith("Five cards are already waiting"))
        repo.createManualResult = ApiResult.Err("x", 409, "no_model")
        state.submit()
        assertTrue(state.sheet!!.error!!.startsWith("Cards need the card model"))
        repo.createManualResult = ApiResult.Err("x", 404)
        state.submit()
        assertEquals("Chat search is off, so cards cannot be written.", state.sheet!!.error)
    }

    @Test
    fun `the open sheet polls every 2 seconds and stops once the job is finished`() {
        repo.createManualResult = ApiResult.Ok(job("queued"))
        val answers = ArrayDeque(listOf(job("writing"), job("writing", waiting = "model_starting"), job("done", card = 9)))
        repo.manualJobResponder = { ApiResult.Ok(answers.removeFirst()) }
        openSheet()
        state.submit()
        assertEquals(0, repo.manualJobReads.size)

        advance(MANUAL_POLL_MS - 1)
        assertEquals(0, repo.manualJobReads.size)
        advance(1)
        assertEquals("writing", state.sheet!!.job!!.state)
        advance(MANUAL_POLL_MS)
        assertEquals("model_starting", state.sheet!!.job!!.waiting)
        advance(MANUAL_POLL_MS)
        assertEquals("done", state.sheet!!.job!!.state)
        assertEquals(3, repo.manualJobReads.size)

        advance(MANUAL_POLL_MS * 5)
        assertEquals(3, repo.manualJobReads.size)
        assertEquals(0, state.watching)
    }

    @Test
    fun `a job the server no longer knows shows as failed and polling stops`() {
        repo.createManualResult = ApiResult.Ok(job("queued"))
        repo.manualJobResponder = { ApiResult.Err("gone", 404) }
        openSheet()
        state.submit()
        advance(MANUAL_POLL_MS)

        assertEquals("failed", state.sheet!!.job!!.state)
        advance(MANUAL_POLL_MS * 3)
        assertEquals(1, repo.manualJobReads.size)
    }

    @Test
    fun `cancel asks the server and shows how the job ended`() {
        repo.createManualResult = ApiResult.Ok(job("writing"))
        repo.manualJobResponder = { ApiResult.Ok(job("cancelled")) }
        openSheet()
        state.submit()

        state.cancelJob()

        assertEquals(listOf(1L), repo.manualCancels)
        assertEquals("cancelled", state.sheet!!.job!!.state)
        assertFalse(state.sheet!!.busy)
    }

    @Test
    fun `try again returns to the form with the same description`() {
        repo.createManualResult = ApiResult.Ok(job("failed", error = "bad_reply"))
        openSheet()
        state.setText("plan")
        state.submit()
        assertEquals("failed", state.sheet!!.job!!.state)

        state.tryAgain()

        assertNull(state.sheet!!.job)
        assertEquals("plan", state.sheet!!.text)
        assertEquals(listOf("m1", "m2"), state.sheet!!.req.ids)
    }

    // -- dismissed while running -----------------------------------------------------------------------------------

    @Test
    fun `a dismissed running job is watched every 5 seconds and announced once ready`() {
        repo.createManualResult = ApiResult.Ok(job("writing"))
        repo.manualJobResponder = { ApiResult.Ok(job("writing")) }
        openSheet()
        state.submit()
        state.dismissSheet()

        assertNull(state.sheet)
        assertEquals(1, state.watching)
        advance(MANUAL_WATCH_MS)
        assertNull(state.ready)
        assertEquals(1, repo.manualJobReads.size)

        repo.manualJobResponder = { ApiResult.Ok(job("done", card = 42)) }
        advance(MANUAL_WATCH_MS)

        val ready = state.ready!!
        assertEquals("Your card is ready", ready.text)
        assertEquals(42L, ready.ref!!.cardId)
        assertEquals(listOf("m1", "m2"), ready.ref!!.msgIds)
        assertEquals(100L, ready.ref!!.firstTs)
        assertEquals(0, state.watching)
        advance(MANUAL_WATCH_MS * 4)
        assertEquals(2, repo.manualJobReads.size)

        state.openReady()
        assertNull(state.ready)
        assertEquals(42L, state.cardView!!.ref.cardId)
    }

    @Test
    fun `a failed watched job is announced without an Open`() {
        repo.createManualResult = ApiResult.Ok(job("writing"))
        repo.manualJobResponder = { ApiResult.Ok(job("failed", error = "no_messages")) }
        openSheet()
        state.submit()
        state.dismissSheet()
        advance(MANUAL_WATCH_MS)

        val ready = state.ready!!
        assertNull(ready.ref)
        assertTrue(ready.text.contains("deleted"))
        state.consumeReady()
        assertNull(state.ready)
    }

    @Test
    fun `dismissing a finished job starts no watcher and a gone job is dropped quietly`() {
        repo.createManualResult = ApiResult.Ok(job("done", card = 3))
        openSheet()
        state.submit()
        state.dismissSheet()
        assertEquals(0, state.watching)

        repo.createManualResult = ApiResult.Ok(job("queued", id = 2))
        repo.manualJobResponder = { ApiResult.Err("gone", 404) }
        openSheet()
        state.submit()
        state.dismissSheet()
        advance(MANUAL_WATCH_MS)
        assertNull(state.ready)
        assertEquals(0, state.watching)
    }

    @Test
    fun `Open card on a finished job in the sheet opens the card view`() {
        repo.createManualResult = ApiResult.Ok(job("done", card = 11))
        openSheet()
        state.submit()
        state.openSheetCard()

        assertNull(state.sheet)
        assertEquals(11L, state.cardView!!.ref.cardId)
    }

    // -- the card view and delete --------------------------------------------------------------------------------------

    private val ref = YourCardRef(cardId = 5, chat = alice, chatName = "Test Chat", firstTs = 100, lastTs = 200, nMsgs = 2, msgIds = listOf("m1", "m2"))

    @Test
    fun `the card view loads the card by id and a 404 means it is gone`() {
        repo.cardResult = ApiResult.Ok(WaCardDetail(id = 5, origin = "manual", instruction = "plan", msgIds = listOf("m1")))
        state.openCard(ref)
        assertEquals(listOf(5L), repo.cardIdRequests)
        assertEquals(CardPhase.DONE, state.cardView!!.phase)
        assertEquals("plan", state.cardView!!.detail!!.instruction)

        repo.cardResult = ApiResult.Err("none", 404)
        state.retryCard()
        assertEquals(CardPhase.MISSING, state.cardView!!.phase)
        repo.cardResult = ApiResult.Err("boom", 502)
        state.retryCard()
        assertEquals(CardPhase.ERROR, state.cardView!!.phase)
    }

    @Test
    fun `delete asks first, then removes the card and tells the results`() {
        state.openCard(ref)
        state.askDelete()
        assertTrue(state.cardView!!.confirming)
        state.keepCard()
        assertFalse(state.cardView!!.confirming)
        assertTrue(repo.cardDeletes.isEmpty())

        state.askDelete()
        state.confirmDelete()

        assertEquals(listOf(5L), repo.cardDeletes)
        assertNull(state.cardView)
        assertEquals(listOf(5L), deleted)
        assertEquals(listOf("Card deleted"), toasts)
    }

    @Test
    fun `a card already gone counts as deleted`() {
        repo.deleteCardResult = ApiResult.Err("unknown", 404)
        state.openCard(ref)
        state.confirmDelete()
        assertNull(state.cardView)
        assertEquals(listOf(5L), deleted)
    }

    @Test
    fun `a refused delete keeps the card and says why`() {
        repo.deleteCardResult = ApiResult.Err("not manual", 409, "not_manual")
        state.openCard(ref)
        state.confirmDelete()

        val v = state.cardView!!
        assertFalse(v.deleting)
        assertEquals("Only a card you asked for can be deleted.", v.deleteError)
        assertTrue(deleted.isEmpty())

        repo.deleteCardResult = ApiResult.Err("down", 503)
        state.confirmDelete()
        assertEquals("The card could not be deleted. Try again.", state.cardView!!.deleteError)
    }

    @Test
    fun `deleting the card whose messages are tinted clears the tint`() {
        state.setPicked(listOf("m1", "m2"))
        state.openCard(ref)
        state.confirmDelete()
        assertTrue(state.picked.isEmpty())
    }

    // -- the picked messages ---------------------------------------------------------------------------------------------

    @Test
    fun `picked ids are exactly the card's and a chat change clears them and the sheets`() {
        state.setPicked(listOf("m1", "", "m2", "m1"))
        assertEquals(setOf("m1", "m2"), state.picked)

        openSheet()
        state.openCard(ref)
        state.onChatChanged()

        assertTrue(state.picked.isEmpty())
        assertNull(state.sheet)
        assertNull(state.cardView)
        assertFalse(state.selection.active)
        assertNotNull(state)
    }
}
