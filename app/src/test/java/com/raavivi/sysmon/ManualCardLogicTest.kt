package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.ManualCardError
import com.raavivi.sysmon.core.model.WaManualJob
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaPos
import com.raavivi.sysmon.core.model.WaYourCard
import com.raavivi.sysmon.ui.whatsapp.MAX_INSTRUCTION
import com.raavivi.sysmon.ui.whatsapp.MAX_PICKS
import com.raavivi.sysmon.ui.whatsapp.PickRow
import com.raavivi.sysmon.ui.whatsapp.Selection
import com.raavivi.sysmon.ui.whatsapp.WaMenuAction
import com.raavivi.sysmon.ui.whatsapp.barText
import com.raavivi.sysmon.ui.whatsapp.canMake
import com.raavivi.sysmon.ui.whatsapp.deleteErrorText
import com.raavivi.sysmon.ui.whatsapp.instructionCounter
import com.raavivi.sysmon.ui.whatsapp.instructionOk
import com.raavivi.sysmon.ui.whatsapp.isFinished
import com.raavivi.sysmon.ui.whatsapp.jobFailureText
import com.raavivi.sysmon.ui.whatsapp.jobProgressText
import com.raavivi.sysmon.ui.whatsapp.manualErrorText
import com.raavivi.sysmon.ui.whatsapp.manualSummary
import com.raavivi.sysmon.ui.whatsapp.pickOrder
import com.raavivi.sysmon.ui.whatsapp.pickedSet
import com.raavivi.sysmon.ui.whatsapp.readyText
import com.raavivi.sysmon.ui.whatsapp.selectOffered
import com.raavivi.sysmon.ui.whatsapp.toRef
import com.raavivi.sysmon.ui.whatsapp.waCanSelect
import com.raavivi.sysmon.ui.whatsapp.waMenuActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The pure decisions behind "your own cards": selection, texts, menu offer. Made-up data only. */
class ManualCardLogicTest {

    private fun msg(id: String, ts: Double = 100.0, revoked: Boolean = false, deleted: Boolean = false, reaction: String = "") = WaMessage(
        id = id, chat = "27820000001@s.whatsapp.net", chatName = "", sender = "", senderName = "", fromMe = false,
        ts = ts, text = "hello", mediaType = null, filename = null, downloaded = false,
        revoked = revoked, deletedForMe = deleted, reactionToId = reaction,
    )

    private val order = (1..6).map { PickRow("m$it", it * 10.0) }

    // -- selection ---------------------------------------------------------------------------------

    @Test
    fun `a tap toggles a message and makes it the anchor`() {
        val a = Selection().toggled("m1", 10.0)
        assertEquals(listOf("m1"), a.ids())
        assertEquals("m1", a.anchor)
        val b = a.toggled("m3", 30.0)
        assertEquals(listOf("m1", "m3"), b.ids())
        val c = b.toggled("m1", 10.0)
        assertEquals(listOf("m3"), c.ids())
        assertEquals("m1", c.anchor)
    }

    @Test
    fun `a range runs from the anchor down to the pressed row`() {
        val s = Selection().toggled("m2", 20.0).withRange(order, "m5", 50.0)
        assertEquals(listOf("m2", "m3", "m4", "m5"), s.ids())
        assertEquals("m2", s.anchor)
    }

    @Test
    fun `a range runs from the anchor up to the pressed row`() {
        val s = Selection().toggled("m5", 50.0).withRange(order, "m2", 20.0)
        assertEquals(listOf("m2", "m3", "m4", "m5"), s.ids())
    }

    @Test
    fun `a range keeps what was already picked and a second range grows from the same anchor`() {
        val s = Selection().toggled("m1", 10.0).toggled("m3", 30.0).withRange(order, "m5", 50.0)
        assertEquals(listOf("m1", "m3", "m4", "m5"), s.ids())
        val t = s.withRange(order, "m6", 60.0)
        assertEquals(listOf("m1", "m3", "m4", "m5", "m6"), t.ids())
    }

    @Test
    fun `a range with no anchor on screen picks just the pressed row`() {
        val s = Selection().toggled("gone", 5.0).withRange(order, "m4", 40.0)
        assertEquals(listOf("gone", "m4"), s.ids())
        assertEquals("m4", s.anchor)
    }

    @Test
    fun `ids come out oldest first and the span is the first and last time`() {
        val s = Selection().toggled("m4", 40.0).toggled("m1", 10.0).toggled("m2", 20.0)
        assertEquals(listOf("m1", "m2", "m4"), s.ids())
        assertEquals(10L to 40L, s.span())
        assertEquals(null, Selection().span())
    }

    @Test
    fun `the cap is 400 and over it the bar says why Make card is off`() {
        assertFalse(canMake(0))
        assertTrue(canMake(1))
        assertTrue(canMake(MAX_PICKS))
        assertFalse(canMake(MAX_PICKS + 1))
        assertEquals("3 selected", barText(3))
        assertEquals("401 selected (at most 400)", barText(401))
    }

    @Test
    fun `only messages with an id that are not deleted or reactions can be picked`() {
        assertTrue(waCanSelect(msg("a")))
        assertFalse(waCanSelect(msg("")))
        assertFalse(waCanSelect(msg("a", revoked = true)))
        assertFalse(waCanSelect(msg("a", deleted = true)))
        assertFalse(waCanSelect(msg("a", reaction = "x")))
    }

    @Test
    fun `the pick order keeps only pickable visible rows`() {
        val rows = pickOrder(listOf(msg("a", 1.0), msg("b", 2.0, revoked = true), msg("c", 3.0, reaction = "a"), msg("d", 4.0)))
        assertEquals(listOf("a", "d"), rows.map { it.id })
    }

    // -- the menu --------------------------------------------------------------------------------------

    @Test
    fun `Select messages is offered only while chat search is live and for a pickable message`() {
        assertTrue(selectOffered(true, msg("a")))
        assertFalse(selectOffered(false, msg("a")))
        assertFalse(selectOffered(true, msg("a", revoked = true)))
        assertFalse(selectOffered(true, msg("")))
        assertTrue(WaMenuAction.SELECT in waMenuActions(msg("a"), 0.0, select = true))
        assertFalse(WaMenuAction.SELECT in waMenuActions(msg("a"), 0.0, select = false))
        assertFalse(WaMenuAction.SELECT in waMenuActions(msg("a"), 0.0))
        assertFalse(WaMenuAction.SELECT in waMenuActions(msg("a", revoked = true), 0.0, select = true))
    }

    // -- texts ---------------------------------------------------------------------------------------------

    private fun job(state: String, waiting: String? = null, error: String? = null, card: Long? = null) =
        WaManualJob(id = 7, state = state, waiting = waiting, error = error, cardId = card)

    @Test
    fun `a job reads as its state, or what it waits for`() {
        assertEquals("Queued", jobProgressText(job("queued")))
        assertEquals("Waiting for the game to close", jobProgressText(job("queued", "game")))
        assertEquals("Waiting for the GPU", jobProgressText(job("queued", "gpu")))
        assertEquals("Waiting for the index to finish", jobProgressText(job("queued", "index")))
        assertEquals("Starting the model", jobProgressText(job("writing", "model_starting")))
        assertEquals("Writing your card", jobProgressText(job("writing")))
        assertEquals("Done", jobProgressText(job("done", card = 3)))
        assertEquals("The card could not be written", jobProgressText(job("failed", error = "bad_reply")))
        assertEquals("Cancelled", jobProgressText(job("cancelled")))
    }

    @Test
    fun `finished means done, failed or cancelled`() {
        assertFalse(isFinished(job("queued")))
        assertFalse(isFinished(job("writing")))
        assertTrue(isFinished(job("done")))
        assertTrue(isFinished(job("failed")))
        assertTrue(isFinished(job("cancelled")))
        assertFalse(isFinished(null))
    }

    @Test
    fun `each failure kind and refusal has plain words`() {
        assertEquals("The card model did not answer. Try again in a moment.", jobFailureText("model_call_failed"))
        assertTrue(jobFailureText("bad_reply").startsWith("The card model gave a reply"))
        assertTrue(jobFailureText("server_unavailable").contains("Topic cards"))
        assertTrue(jobFailureText("no_messages").contains("deleted"))
        assertEquals("The card could not be written.", jobFailureText("something_new"))
        assertEquals("a message was deleted", manualErrorText(ManualCardError.Refused("a message was deleted")))
        assertTrue(manualErrorText(ManualCardError.TooMany).startsWith("Five cards are already waiting"))
        assertTrue(manualErrorText(ManualCardError.NoModel).startsWith("Cards need the card model"))
        assertEquals("Chat search is off, so cards cannot be written.", manualErrorText(ManualCardError.Off))
        assertEquals("Only a card you asked for can be deleted.", deleteErrorText(ManualCardError.NotManual))
        assertEquals("The card could not be deleted. Try again.", deleteErrorText(ManualCardError.Other))
    }

    @Test
    fun `the ready text follows how the job ended`() {
        assertEquals("Your card is ready", readyText(job("done", card = 1)))
        assertEquals("Your card was cancelled", readyText(job("cancelled")))
        assertEquals(jobFailureText("bad_reply"), readyText(job("failed", error = "bad_reply")))
    }

    @Test
    fun `the description counter and limit`() {
        assertEquals("0 / 500", instructionCounter(0))
        assertEquals("12 / 500", instructionCounter(12))
        assertEquals("3 over the limit of 500", instructionCounter(MAX_INSTRUCTION + 3))
        assertTrue(instructionOk("x".repeat(MAX_INSTRUCTION)))
        assertFalse(instructionOk("x".repeat(MAX_INSTRUCTION + 1)))
        assertTrue(instructionOk(""))
    }

    @Test
    fun `the summary names the count and the range`() {
        val day = 1_700_000_000L   // 2023-11-14 22:13 UTC
        val now = day + 30 * 86_400.0
        assertEquals("1 message", manualSummary(1, null, null, now))
        assertEquals("2 messages · 14 November · 22:13–22:43", manualSummary(2, day, day + 1800, now, ZoneOffset.UTC))
        assertTrue(manualSummary(3, day, day + 3 * 86_400, now, ZoneOffset.UTC).contains(" – "))
    }

    @Test
    fun `picked ids drop blanks and repeats`() {
        assertEquals(setOf("a", "b"), pickedSet(listOf("a", "", "b", "a")))
    }

    @Test
    fun `a search result card maps to a reference`() {
        val ref = WaYourCard(
            cardId = 9, chat = "c", chatName = "Test Chat", title = " Plan ", summary = "s", instruction = " go ",
            start = WaPos(10, 1), end = WaPos(20, 2), nMsgs = 2, msgIds = listOf("a", "b"),
        ).toRef()
        assertEquals(9L, ref.cardId)
        assertEquals("Plan", ref.title)
        assertEquals("go", ref.instruction)
        assertEquals(10L, ref.firstTs)
        assertEquals(20L, ref.lastTs)
        assertEquals(listOf("a", "b"), ref.msgIds)
    }
}
