package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.ui.whatsapp.PageOutcome
import com.raavivi.sysmon.ui.whatsapp.WA_PAGE_LIMIT
import com.raavivi.sysmon.ui.whatsapp.formatClock
import com.raavivi.sysmon.ui.whatsapp.isAudioMedia
import com.raavivi.sysmon.ui.whatsapp.mergeMessages
import com.raavivi.sysmon.ui.whatsapp.nextSpeed
import com.raavivi.sysmon.ui.whatsapp.pageOutcome
import com.raavivi.sysmon.ui.whatsapp.shouldAutoLoadImage
import com.raavivi.sysmon.ui.whatsapp.speedLabel
import com.raavivi.sysmon.ui.whatsapp.waBeforeCursor
import com.raavivi.sysmon.ui.whatsapp.waInclusiveAfter
import com.raavivi.sysmon.ui.whatsapp.waInclusiveBefore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppLogicTest {
    @Test fun beforeCursorIsRfc3339() {
        assertEquals("2023-11-14T22:13:20Z", waBeforeCursor(1_700_000_000.9))
    }

    @Test fun inclusiveCursorsStraddleTheBoundarySecond() {
        assertEquals("2023-11-14T22:13:21Z", waInclusiveBefore(1_700_000_000.9))
        assertEquals("2023-11-14T22:13:19Z", waInclusiveAfter(1_700_000_000.9))
    }

    @Test fun pageOutcomes() {
        assertEquals(PageOutcome.END, pageOutcome(raw = 10, added = 10, limit = WA_PAGE_LIMIT))
        assertEquals(PageOutcome.END, pageOutcome(raw = 0, added = 0, limit = WA_PAGE_LIMIT))
        assertEquals(PageOutcome.END, pageOutcome(raw = 49, added = 0, limit = WA_PAGE_LIMIT))
        assertEquals(PageOutcome.STALLED, pageOutcome(raw = 50, added = 0, limit = WA_PAGE_LIMIT))
        assertEquals(PageOutcome.MORE, pageOutcome(raw = 50, added = 1, limit = WA_PAGE_LIMIT))
    }

    private fun msg(id: String, ts: Double) = WaMessage(
        id = id, chat = "c", chatName = "", sender = "", senderName = "", fromMe = false,
        ts = ts, text = id, mediaType = null, filename = null, downloaded = false,
    )

    @Test fun mergeDedupesByIdAndSorts() {
        val current = listOf(msg("b", 2.0), msg("c", 3.0))
        val (merged, added) = mergeMessages(current, listOf(msg("c", 3.0), msg("a", 1.0), msg("a", 1.0)))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals(1, added)
    }

    @Test fun mergeKeepsBlankIdRows() {
        val current = listOf(msg("", 1.0))
        val (merged, added) = mergeMessages(current, listOf(msg("", 1.0), msg("", 2.0)))
        assertEquals(3, merged.size)
        assertEquals(2, added)
    }

    @Test fun mergeNothingNew() {
        val current = listOf(msg("a", 1.0))
        val (merged, added) = mergeMessages(current, listOf(msg("a", 1.0)))
        assertEquals(1, merged.size)
        assertEquals(0, added)
    }

    @Test fun imageAutoLoadWindow() {
        val now = 100_000_000.0
        val day = 24 * 3600.0
        assertTrue(shouldAutoLoadImage(true, now - 900 * day, now))
        assertTrue(shouldAutoLoadImage(false, now - 29 * day, now))
        assertFalse(shouldAutoLoadImage(false, now - 31 * day, now))
        assertTrue(shouldAutoLoadImage(false, now + 60, now))
    }

    @Test fun audioDetection() {
        assertTrue(isAudioMedia("audio/ogg"))
        assertTrue(isAudioMedia("PTT"))
        assertTrue(isAudioMedia("Voice"))
        assertFalse(isAudioMedia("image"))
        assertFalse(isAudioMedia(null))
    }

    @Test fun clockFormat() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:09", formatClock(9_400))
        assertEquals("1:05", formatClock(65_000))
        assertEquals("61:01", formatClock(3_661_000))
        assertEquals("0:00", formatClock(-5))
    }

    @Test fun speedCycle() {
        assertEquals(1.5f, nextSpeed(1f))
        assertEquals(2f, nextSpeed(1.5f))
        assertEquals(1f, nextSpeed(2f))
        assertEquals(1f, nextSpeed(3f))
        assertEquals("1x", speedLabel(1f))
        assertEquals("1.5x", speedLabel(1.5f))
    }
}
