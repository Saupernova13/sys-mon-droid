package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WaCardsStatus
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import com.raavivi.sysmon.core.model.WaVoiceNotesStatus
import com.raavivi.sysmon.ui.whatsapp.LineView
import com.raavivi.sysmon.ui.whatsapp.cardsView
import com.raavivi.sysmon.ui.whatsapp.indexView
import com.raavivi.sysmon.ui.whatsapp.isManual
import com.raavivi.sysmon.ui.whatsapp.progressText
import com.raavivi.sysmon.ui.whatsapp.shouldPollAny
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The index and topic-cards lines, mirroring the web whatsapp-search-index-state / -cards-state tests. */
class WhatsAppSearchStatusTest {

    private val utc = ZoneOffset.UTC
    private val manual = WaChatSearchStatus(enabled = true, auto = false)
    private val indexing = manual.copy(
        runRequested = true, messagesIndexed = 1200, messagesTotal = 4000, voiceNotes = WaVoiceNotesStatus(pending = 3),
    )
    private val idleCards = WaCardsStatus(configured = true, done = 1234, eligiblePending = 15113)
    private fun cards(c: WaCardsStatus) = WaChatSearchStatus(enabled = true, cards = c)

    // -- the index line --------------------------------------------------------------------------

    @Test
    fun `the index line shows only while automatic indexing is off`() {
        assertEquals(LineView.Hidden, indexView(manual.copy(auto = true), true))
        assertEquals(LineView.Hidden, indexView(WaChatSearchStatus(enabled = false), true))
        assertEquals(LineView.Hidden, indexView(WaChatSearchStatus(enabled = true), true))
        assertEquals(LineView.Hidden, indexView(null, true))
        assertTrue(isManual(manual))
    }

    @Test
    fun `the index line belongs to Conversations`() {
        assertEquals(LineView.Hidden, indexView(manual, false))
        assertTrue(indexView(manual, true).visible)
    }

    @Test
    fun `idle it offers the update and a run shows progress without a button`() {
        assertEquals(LineView(true, false, "Search index ·", "Update"), indexView(manual, true))
        assertEquals(
            LineView(true, true, "Updating the index… 1,200 / 4,000 messages · 3 voice notes left", ""),
            indexView(indexing, true),
        )
    }

    @Test
    fun `progress leaves out what the status does not say`() {
        assertEquals("", progressText(WaChatSearchStatus()))
        assertEquals("", progressText(WaChatSearchStatus(messagesIndexed = 5, messagesTotal = 0)))
        assertEquals("1 voice note left", progressText(WaChatSearchStatus(voiceNotes = WaVoiceNotesStatus(1))))
        assertEquals("", progressText(WaChatSearchStatus(voiceNotes = WaVoiceNotesStatus(0))))
        assertEquals("", progressText(WaChatSearchStatus(voiceNotes = WaVoiceNotesStatus(null))))
        assertEquals("4 / 4 messages", progressText(WaChatSearchStatus(messagesIndexed = 9, messagesTotal = 4)))
        assertEquals("Updating the index…", indexView(manual.copy(runRequested = true), true).text)
    }

    @Test
    fun `the status is polled only while the view is open and a run is going`() {
        assertTrue(shouldPollAny(indexing, true))
        assertFalse(shouldPollAny(indexing, false))
        assertFalse(shouldPollAny(manual, true))
        assertFalse(shouldPollAny(indexing.copy(auto = true), true))
        assertFalse(shouldPollAny(null, true))
        assertTrue(shouldPollAny(cards(idleCards.copy(requested = true)), true))
        assertFalse(shouldPollAny(cards(idleCards), true))
    }

    // -- the cards line --------------------------------------------------------------------------

    @Test
    fun `the cards line shows only when enabled, configured and in Conversations`() {
        assertEquals(LineView.Hidden, cardsView(null, true))
        assertEquals(LineView.Hidden, cardsView(cards(idleCards).copy(enabled = false), true))
        assertEquals(LineView.Hidden, cardsView(WaChatSearchStatus(enabled = true), true))
        assertEquals(LineView.Hidden, cardsView(cards(WaCardsStatus(configured = false)), true))
        assertEquals(LineView.Hidden, cardsView(cards(idleCards), false))
    }

    @Test
    fun `idle it offers Run with the counts and without numbers it says less`() {
        assertEquals(LineView(true, false, "Topic cards · 1,234 done · 15,113 left", "Run"), cardsView(cards(idleCards), true))
        assertEquals("Topic cards ·", cardsView(cards(WaCardsStatus(configured = true)), true).text)
        assertEquals("Topic cards · 5 done", cardsView(cards(WaCardsStatus(configured = true, done = 5)), true).text)
    }

    @Test
    fun `when nothing is left there is no button`() {
        val st = cards(WaCardsStatus(configured = true, done = 1234, eligiblePending = 0))

        assertEquals(LineView(true, false, "Topic cards · all 1,234 done", ""), cardsView(st, true))
        assertEquals("Topic cards · all done", cardsView(cards(WaCardsStatus(configured = true, eligiblePending = 0)), true).text)
    }

    @Test
    fun `a run shows progress and offers Stop`() {
        val run = cards(idleCards.copy(requested = true))

        assertEquals(
            LineView(true, true, "Writing topic cards… 1,234 done · 15,113 left", "Stop"),
            cardsView(run, true),
        )
        assertEquals("Writing topic cards…", cardsView(cards(WaCardsStatus(configured = true, requested = true)), true).text)
    }

    @Test
    fun `a failing run says what it waits for in plain words`() {
        fun wait(err: kotlinx.serialization.json.JsonElement) =
            cardsView(cards(idleCards.copy(requested = true, lastError = err)), true).text

        val head = "Writing topic cards… 1,234 done · 15,113 left"
        assertEquals("$head · waiting (model server not available)", wait(JsonObject(mapOf("kind" to JsonPrimitive("server_unavailable")))))
        assertEquals("$head · waiting (model server not available)", wait(JsonPrimitive("server_unavailable")))
        assertTrue(wait(JsonPrimitive("bad_json")).contains("waiting (a problem; retrying)"))
        assertFalse(wait(JsonPrimitive("bad_json")).contains("bad_json"))
        assertFalse(cardsView(cards(idleCards.copy(lastError = JsonPrimitive("x"))), true).text.contains("waiting"))
    }

    @Test
    fun `a break, a game pause and a GPU wait follow the schedule`() {
        val at = 1_800_000_000.0   // 2027-01-15 08:00 UTC
        fun run(c: WaCardsStatus) = cardsView(cards(c), true, utc)
        val base = idleCards.copy(requested = true)

        assertEquals(
            LineView(true, true, "Topic cards on a break until 08:00 · 1,234 done · 15,113 left", "Stop"),
            run(base.copy(mode = "break", breakUntil = at)),
        )
        assertEquals("Topic cards on a break · 1,234 done · 15,113 left", run(base.copy(mode = "break")).text)
        assertEquals("Topic cards paused: a game is running · 1,234 done · 15,113 left", run(base.copy(mode = "paused_game")).text)
        assertEquals("Topic cards waiting for the GPU · 1,234 done · 15,113 left", run(base.copy(mode = "waiting_gpu")).text)
        assertEquals(
            "Topic cards paused: a game is running",
            run(base.copy(mode = "paused_game", done = null, eligiblePending = null)).text,
        )
    }

    @Test
    fun `the night window writing offers Stop though nobody asked`() {
        val view = cardsView(cards(idleCards.copy(auto = true, mode = "writing")), true)

        assertEquals(LineView(true, true, "Writing topic cards… 1,234 done · 15,113 left", "Stop"), view)
        assertEquals("Stop", cardsView(cards(idleCards.copy(auto = true, mode = "paused_game")), true).button)
    }

    @Test
    fun `outside the window it says when the next one is and falls back without a time`() {
        val at = 1_800_000_000.0
        val waiting = idleCards.copy(mode = "waiting_window", nextWindowStart = at)

        assertEquals(
            LineView(true, false, "Topic cards · next at 08:00 · 1,234 done · 15,113 left", "Run"),
            cardsView(cards(waiting), true, utc),
        )
        assertEquals("Topic cards · 1,234 done · 15,113 left", cardsView(cards(waiting.copy(nextWindowStart = null)), true, utc).text)
        assertEquals("Topic cards · all 1,234 done", cardsView(cards(waiting.copy(eligiblePending = 0)), true, utc).text)
    }
}
