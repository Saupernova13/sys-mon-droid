package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WaTimelineDay
import com.raavivi.sysmon.ui.whatsapp.buildTimeline
import com.raavivi.sysmon.ui.whatsapp.dayAt
import com.raavivi.sysmon.ui.whatsapp.firstDayOnOrAfter
import com.raavivi.sysmon.ui.whatsapp.fmtDay
import com.raavivi.sysmon.ui.whatsapp.posOfTs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import com.raavivi.sysmon.core.model.WaTimelineResponse
import com.raavivi.sysmon.core.net.SysMonJson

class WhatsAppTimelineTest {
    private fun d(day: String, count: Int, ts: Long) = WaTimelineDay(day, count, ts)

    // Jan 2022: 4 msgs over two days; Feb 2022: 16 msgs in one day; Jan 2023: 4 msgs in one day.
    private val days = listOf(
        d("2022-01-10", 1, 100), d("2022-01-11", 3, 200),
        d("2022-02-01", 16, 300),
        d("2023-01-05", 4, 400),
    )

    @Test fun nullForFewerThanTwoDays() {
        assertNull(buildTimeline(emptyList()))
        assertNull(buildTimeline(listOf(d("2022-01-10", 1, 100))))
    }

    @Test fun segmentsAreContiguous() {
        val tl = buildTimeline(days)!!
        assertEquals(0.0, tl.first.start, 0.0)
        assertEquals(1.0, tl.last.end, 0.0)
        for (i in 1 until tl.segments.size) {
            assertEquals(tl.segments[i - 1].end, tl.segments[i].start, 1e-12)
        }
    }

    @Test fun monthsWeightedBySqrtCount() {
        val tl = buildTimeline(days)!!
        val total = 2.0 + 4.0 + 2.0
        // Jan 2022 spans 2/8, split 1:3 between its days.
        assertEquals(2.0 / total * 0.25, tl.segments[0].end, 1e-12)
        assertEquals(2.0 / total, tl.segments[1].end, 1e-12)
        assertEquals(6.0 / total, tl.segments[2].end, 1e-12)
    }

    @Test fun yearsAtFirstMonthStart() {
        val tl = buildTimeline(days)!!
        assertEquals(listOf("2022", "2023"), tl.years.map { it.year })
        assertEquals(0.0, tl.years[0].pos, 0.0)
        assertEquals(6.0 / 8.0, tl.years[1].pos, 1e-12)
    }

    @Test fun zeroCountsDoNotProduceNaN() {
        val tl = buildTimeline(listOf(d("2022-01-10", 0, 1), d("2022-01-11", 0, 2)))!!
        assertEquals(0.0, tl.first.start, 0.0)
        assertEquals(1.0, tl.last.end, 0.0)
        assertEquals(false, tl.segments[0].end.isNaN())
    }

    @Test fun dayAtClampsAndFindsEdges() {
        val tl = buildTimeline(days)!!
        assertEquals("2022-01-10", dayAt(tl, -5.0).day)
        assertEquals("2022-01-10", dayAt(tl, Double.NaN).day)
        assertEquals("2023-01-05", dayAt(tl, 7.0).day)
        assertEquals("2023-01-05", dayAt(tl, 1.0).day)
        assertEquals("2022-01-11", dayAt(tl, tl.segments[1].start).day)
        assertEquals("2022-02-01", dayAt(tl, 0.5).day)
    }

    @Test fun posOfTsBeforeInsideAfter() {
        val tl = buildTimeline(days)!!
        assertEquals(0.0, posOfTs(tl, 50), 0.0)
        assertEquals(tl.segments[1].start, posOfTs(tl, 250), 0.0)
        assertEquals(tl.segments[3].start, posOfTs(tl, 9999), 0.0)
    }

    @Test fun firstDayOnOrAfterMatchesAndMisses() {
        val tl = buildTimeline(days)!!
        assertEquals("2022-01-11", firstDayOnOrAfter(tl, "2022-01-11")!!.day)
        assertEquals("2022-02-01", firstDayOnOrAfter(tl, "2022-01-12")!!.day)
        assertEquals("2022-01-10", firstDayOnOrAfter(tl, "2000-01-01")!!.day)
        assertNull(firstDayOnOrAfter(tl, "2023-01-06"))
        assertNotNull(firstDayOnOrAfter(tl, "2023-01-05"))
    }

    @Test fun fmtDayFormats() {
        assertEquals("27 Feb 2022", fmtDay("2022-02-27"))
        assertEquals("5 Dec 2023", fmtDay("2023-12-05"))
        assertEquals("junk", fmtDay("junk"))
    }

    @Test fun decodesSnakeCaseFirstTs() {
        val r = SysMonJson.decodeFromString<WaTimelineResponse>(
            """{"days":[{"day":"2022-02-27","count":12,"first_ts":1645980946}]}""",
        )
        assertEquals(1645980946L, r.days[0].firstTs)
    }
}
