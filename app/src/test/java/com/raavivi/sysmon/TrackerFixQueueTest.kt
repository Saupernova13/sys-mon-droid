package com.raavivi.sysmon

import com.raavivi.sysmon.core.tracker.data.FixQueue
import com.raavivi.sysmon.core.tracker.model.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrackerFixQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun queue(cap: Int = 1000, name: String = "q.json") = FixQueue(File(tmp.root, name), cap)

    private fun fix(ts: Double) = Fix(ts = ts, lat = 1.0, lon = 2.0)

    @Test
    fun keepsOldestFirstRegardlessOfInsertOrder() {
        val q = queue()
        q.add(fix(30.0))
        q.add(fix(10.0))
        q.add(fix(20.0))
        assertEquals(listOf(10.0, 20.0, 30.0), q.snapshot().map { it.ts })
    }

    @Test
    fun dedupesByTimestamp() {
        val q = queue()
        assertTrue(q.add(fix(10.0)))
        assertFalse(q.add(fix(10.0)))
        assertEquals(1, q.size.value)
    }

    @Test
    fun dropsOldestPastTheCap() {
        val q = queue(cap = 5)
        for (i in 1..8) q.add(fix(i.toDouble()))
        assertEquals(listOf(4.0, 5.0, 6.0, 7.0, 8.0), q.snapshot().map { it.ts })
    }

    @Test
    fun defaultCapIs1000() {
        val q = queue()
        for (i in 1..1005) q.add(fix(i.toDouble()))
        assertEquals(1000, q.size.value)
        assertEquals(6.0, q.snapshot().first().ts, 0.0)
    }

    @Test
    fun chunksOfAtMost200OldestFirst() {
        val q = queue()
        for (i in 1..450) q.add(fix(i.toDouble()))
        val chunks = q.chunks()
        assertEquals(listOf(200, 200, 50), chunks.map { it.size })
        assertEquals(1.0, chunks[0].first().ts, 0.0)
        assertEquals(201.0, chunks[1].first().ts, 0.0)
        assertEquals(450.0, chunks[2].last().ts, 0.0)
    }

    @Test
    fun removeDropsOnlySentFixes() {
        val q = queue()
        for (i in 1..5) q.add(fix(i.toDouble()))
        q.remove(listOf(fix(1.0), fix(2.0)))
        assertEquals(listOf(3.0, 4.0, 5.0), q.snapshot().map { it.ts })
    }

    @Test
    fun persistenceRoundTrip() {
        val q = queue()
        val full = Fix(
            ts = 1700000000.5, lat = -26.2, lon = 28.04, accuracyM = 12.5, altitudeM = 1700.0,
            speedMps = 1.2, bearingDeg = 90.0, provider = "fused", source = "locate",
            batteryPct = 80, charging = true, network = "wifi",
        )
        q.add(full)
        q.add(fix(5.0))

        val reloaded = queue()
        assertEquals(listOf(5.0, 1700000000.5), reloaded.snapshot().map { it.ts })
        assertEquals(full, reloaded.snapshot().last())
        assertEquals(2, reloaded.size.value)
    }

    @Test
    fun corruptFileStartsEmpty() {
        File(tmp.root, "q.json").writeText("{not json")
        assertEquals(0, queue().size.value)
    }

    @Test
    fun clearEmptiesTheFileToo() {
        val q = queue()
        q.add(fix(1.0))
        q.clear()
        assertEquals(0, queue().size.value)
    }
}
