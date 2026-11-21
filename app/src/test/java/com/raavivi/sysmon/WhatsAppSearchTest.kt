package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaSearchRow
import com.raavivi.sysmon.core.model.WaTimelineDay
import com.raavivi.sysmon.ui.whatsapp.ChipFilters
import com.raavivi.sysmon.ui.whatsapp.ChipSel
import com.raavivi.sysmon.ui.whatsapp.GROUP_PREVIEW_ROWS
import com.raavivi.sysmon.ui.whatsapp.SearchChip
import com.raavivi.sysmon.ui.whatsapp.Span
import com.raavivi.sysmon.ui.whatsapp.StepButtons
import com.raavivi.sysmon.ui.whatsapp.chipFilters
import com.raavivi.sysmon.ui.whatsapp.convCrumb
import com.raavivi.sysmon.ui.whatsapp.convRange
import com.raavivi.sysmon.ui.whatsapp.countText
import com.raavivi.sysmon.ui.whatsapp.foundPick
import com.raavivi.sysmon.ui.whatsapp.groupHits
import com.raavivi.sysmon.ui.whatsapp.groupWindow
import com.raavivi.sysmon.ui.whatsapp.markQueryWords
import com.raavivi.sysmon.ui.whatsapp.notHerePick
import com.raavivi.sysmon.ui.whatsapp.buildTimeline
import com.raavivi.sysmon.ui.whatsapp.parseSnippet
import com.raavivi.sysmon.ui.whatsapp.pressChip
import com.raavivi.sysmon.ui.whatsapp.scrubberMarks
import com.raavivi.sysmon.ui.whatsapp.stepButtons
import com.raavivi.sysmon.ui.whatsapp.stepTarget
import com.raavivi.sysmon.ui.whatsapp.waClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The pure search decisions, mirroring the web tests (whatsapp-search-state / -convs), with made-up data. */
class WhatsAppSearchTest {

    private fun hit(chat: String, id: String) = WaSearchRow(chatJid = chat, msgId = id)

    // -- grouping --------------------------------------------------------------------------------

    @Test
    fun `hits group under their chat in the order the chats first appear`() {
        val groups = groupHits(listOf(hit("a", "1"), hit("b", "2"), hit("a", "3"), hit("c", "4"), hit("b", "5")))

        assertEquals(listOf("a", "b", "c"), groups.map { it.chat })
        assertEquals(listOf("1", "3"), groups[0].hits.map { it.msgId })
        assertEquals(listOf("2", "5"), groups[1].hits.map { it.msgId })
        assertTrue(groupHits(emptyList()).isEmpty())
    }

    @Test
    fun `a group shows its first rows and counts the rest until expanded`() {
        val group = groupHits((1..5).map { hit("a", "$it") }).single()

        val folded = groupWindow(group, expanded = false)
        assertEquals(GROUP_PREVIEW_ROWS, folded.shown.size)
        assertEquals(2, folded.rest)
        val open = groupWindow(group, expanded = true)
        assertEquals(5, open.shown.size)
        assertEquals(0, open.rest)
        assertEquals(0, groupWindow(groupHits(listOf(hit("a", "1"))).single(), false).rest)
    }

    // -- chips -----------------------------------------------------------------------------------

    @Test
    fun `one sender chip and one type chip may be on and pressing an active chip turns it off`() {
        var sel = pressChip(ChipSel(), SearchChip.MINE)
        sel = pressChip(sel, SearchChip.PHOTOS)
        assertEquals(ChipSel(SearchChip.MINE, SearchChip.PHOTOS), sel)

        sel = pressChip(sel, SearchChip.THEIRS)
        assertEquals(SearchChip.THEIRS, sel.sender)
        assertEquals(SearchChip.PHOTOS, sel.media)

        sel = pressChip(sel, SearchChip.THEIRS)
        assertNull(sel.sender)
        sel = pressChip(sel, SearchChip.VOICE)
        assertEquals(SearchChip.VOICE, sel.media)
    }

    @Test
    fun `All clears both chips`() {
        val sel = pressChip(ChipSel(SearchChip.MINE, SearchChip.VIDEOS), null)

        assertTrue(sel.none)
    }

    @Test
    fun `chips map to the api filters`() {
        assertEquals(ChipFilters(null, null), chipFilters(ChipSel()))
        assertEquals(ChipFilters(true, "image"), chipFilters(ChipSel(SearchChip.MINE, SearchChip.PHOTOS)))
        assertEquals(ChipFilters(false, "audio"), chipFilters(ChipSel(SearchChip.THEIRS, SearchChip.VOICE)))
        assertEquals(ChipFilters(null, "document"), chipFilters(ChipSel(media = SearchChip.DOCUMENTS)))
        assertEquals(ChipFilters(null, "video"), chipFilters(ChipSel(media = SearchChip.VIDEOS)))
    }

    // -- counter and stepping --------------------------------------------------------------------

    @Test
    fun `the counter reads blank, hits, n of m, and a thousand plus`() {
        assertEquals("", countText(0, false, 0, -1))
        assertEquals("12 hits", countText(12, false, 12, -1))
        assertEquals("3/12", countText(12, false, 12, 2))
        assertEquals("1/1000+", countText(1000, true, 100, 0))
        assertEquals("1000+ hits", countText(1000, true, 100, -1))
        assertEquals("5 hits", countText(5, false, 0, -1))
    }

    @Test
    fun `a step aims one hit along and stops at the ends`() {
        assertEquals(3, stepTarget(2, 1, 10, false))
        assertEquals(1, stepTarget(2, -1, 10, false))
        assertEquals(0, stepTarget(-1, 1, 10, false))
        assertNull(stepTarget(0, -1, 10, false))
        assertNull(stepTarget(-1, -1, 10, false))
        assertNull(stepTarget(-1, 1, 0, false))
    }

    @Test
    fun `past the last loaded hit a step waits for the next page only when there is one`() {
        assertNull(stepTarget(9, 1, 10, false))
        assertEquals(10, stepTarget(9, 1, 10, true))
    }

    @Test
    fun `the buttons switch off at the ends of the results`() {
        assertEquals(StepButtons(newerOff = true, olderOff = true), stepButtons(-1, 0, false))
        assertEquals(StepButtons(newerOff = true, olderOff = false), stepButtons(0, 10, false))
        assertEquals(StepButtons(newerOff = false, olderOff = false), stepButtons(5, 10, false))
        assertEquals(StepButtons(newerOff = false, olderOff = true), stepButtons(9, 10, false))
        assertEquals(StepButtons(newerOff = false, olderOff = false), stepButtons(9, 10, true))
    }

    // -- text helpers ----------------------------------------------------------------------------

    @Test
    fun `a snippet loses its markers and keeps the marked ranges`() {
        val s = parseSnippet("go to the \u0002cinema\u0003 with \u0002Sam\u0003 now")

        assertEquals("go to the cinema with Sam now", s.text)
        assertEquals(listOf(Span(10, 16), Span(22, 25)), s.marks)
        assertEquals("cinema", s.text.substring(s.marks[0].start, s.marks[0].end))
    }

    @Test
    fun `a snippet with a stray marker still reads clean`() {
        val s = parseSnippet("\u0003plain \u0002open")

        assertEquals("plain open", s.text)
        assertTrue(s.marks.isEmpty())
        assertEquals(emptyList<Span>(), parseSnippet("").marks)
    }

    @Test
    fun `query words of three letters or more are marked as whole words`() {
        val text = "the pottery wheel is booked, potteryx no"

        val marks = markQueryWords(text, "Pottery plans")

        assertEquals(listOf("pottery"), marks.map { text.substring(it.start, it.end).lowercase() })
        assertEquals(1, marks.size)
        assertTrue(markQueryWords(text, "to it").isEmpty())
        assertTrue(markQueryWords("", "pottery").isEmpty())
    }

    @Test
    fun `query words with regex characters do not break the matcher`() {
        assertTrue(markQueryWords("a+b (c)", "a+b (c) c++").isEmpty())
        assertEquals(1, markQueryWords("c++ is fun", "fun?").size)
    }

    @Test
    fun `a conversation range reads day, clock span and the parent topic`() {
        val zone = ZoneOffset.UTC
        val start = 1_741_971_600L   // 2025-03-14 17:00 UTC
        val now = start.toDouble() + 400 * 86_400

        assertEquals("17:00", waClock(start, zone))
        assertEquals("14 March 2025 · 17:00–17:30", convRange(start, start + 1800, now, zone))
        assertEquals("Today · 17:00–17:30", convRange(start, start + 1800, start.toDouble() + 60, zone))
        assertEquals("Topic 17:00–17:30 · 14 messages", convCrumb(start, start + 1800, 14, zone))
    }

    // -- picks -----------------------------------------------------------------------------------

    @Test
    fun `a found pick carries the hit, rank and shown count`() {
        val conv = WaConvResult(
            family = listOf("a@s.whatsapp.net", "1@lid"), startTs = 10, startRowid = 1, endTs = 20, endRowid = 9,
        )

        val body = foundPick("cinema night", conv, rank = 2, shown = 5)

        assertEquals("found", body.verdict)
        assertEquals("cinema night", body.q)
        assertEquals(2, body.rank)
        assertEquals(5, body.shown)
        assertEquals(listOf("a@s.whatsapp.net", "1@lid"), body.hit!!.family)
        assertEquals(1L, body.hit!!.startRowid)
        assertEquals(20L, body.hit!!.endTs)
    }

    @Test
    fun `a not here pick has no hit or rank`() {
        val body = notHerePick("cinema night", shown = 4)

        assertEquals("not_here", body.verdict)
        assertNull(body.hit)
        assertNull(body.rank)
        assertEquals(4, body.shown)
    }

    // -- scrubber marks --------------------------------------------------------------------------

    private val timeline = buildTimeline(
        listOf(WaTimelineDay("2026-01-01", 10, 1000), WaTimelineDay("2026-01-02", 10, 2000), WaTimelineDay("2026-01-03", 10, 3000)),
    )!!

    @Test
    fun `hit days and a conversation's ends become track positions`() {
        val days = listOf(WaTimelineDay("2026-01-02", 2, 2000))

        val marks = scrubberMarks(timeline, days, 1000L..3000L)

        assertEquals(timeline.segments[1].start, marks[0], 0.0)
        assertTrue(marks.contains(timeline.segments[0].start))
        assertTrue(marks.contains(timeline.segments[2].start))
        assertEquals(3, marks.size)
    }

    @Test
    fun `no hits and no range mark nothing`() {
        assertTrue(scrubberMarks(timeline, null, null).isEmpty())
        assertTrue(scrubberMarks(timeline, emptyList(), null).isEmpty())
    }
}
