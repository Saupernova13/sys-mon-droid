package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.PowerHistoryItem
import com.raavivi.sysmon.core.model.PowerHistoryResponse
import com.raavivi.sysmon.ui.power.mergePowerHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The incremental power history merge. */
class PowerHistoryMergeTest {

    private fun item(ts: Double, w: Double) = PowerHistoryItem(ts = ts, watts = w)

    private fun resp(bucket: Long, source: String, vararg items: PowerHistoryItem) =
        PowerHistoryResponse(items = items.toList(), bucketSeconds = bucket, source = source, tariff = 2.0)

    @Test
    fun replacesLastBucketAndAppendsNewer() {
        val held = resp(60, "hourly", item(0.0, 1.0), item(60.0, 2.0), item(120.0, 3.0))
        val reply = resp(60, "hourly", item(120.0, 3.5), item(180.0, 4.0))
        val merged = mergePowerHistory(held, reply, start = 120.0, cutoff = null)!!
        assertEquals(listOf(0.0, 60.0, 120.0, 180.0), merged.items.map { it.ts })
        assertEquals(listOf(1.0, 2.0, 3.5, 4.0), merged.items.map { it.watts })
    }

    @Test
    fun trimsToRange() {
        val held = resp(60, "raw", item(0.0, 1.0), item(60.0, 2.0), item(120.0, 3.0))
        val reply = resp(60, "raw", item(120.0, 3.5), item(180.0, 4.0))
        val merged = mergePowerHistory(held, reply, start = 120.0, cutoff = 100.0)!!
        assertEquals(listOf(120.0, 180.0), merged.items.map { it.ts })
    }

    @Test
    fun takesEnvelopeFieldsFromReply() {
        val held = resp(60, "raw", item(0.0, 1.0))
        val reply = resp(60, "raw", item(0.0, 1.5)).copy(tariff = 3.0)
        assertEquals(3.0, mergePowerHistory(held, reply, 0.0, null)!!.tariff, 0.0)
    }

    @Test
    fun mismatchedBucketNeedsFullFetch() {
        val held = resp(60, "raw", item(0.0, 1.0))
        assertNull(mergePowerHistory(held, resp(5, "raw", item(0.0, 1.0)), 0.0, null))
    }

    @Test
    fun sourceChangeWithSameBucketMerges() {
        val held = resp(3600, "hourly", item(0.0, 1.0), item(3600.0, 2.0))
        val reply = resp(3600, "raw", item(3600.0, 2.5), item(7200.0, 3.0))
        val merged = mergePowerHistory(held, reply, start = 3600.0, cutoff = null)!!
        assertEquals(listOf(1.0, 2.5, 3.0), merged.items.map { it.watts })
    }

    @Test
    fun unbucketedRawMerges() {
        val held = resp(0, "raw", item(1.0, 1.0), item(2.0, 2.0))
        val reply = resp(0, "raw", item(2.0, 2.5), item(3.0, 3.0))
        val merged = mergePowerHistory(held, reply, start = 2.0, cutoff = null)!!
        assertEquals(listOf(1.0, 2.5, 3.0), merged.items.map { it.watts })
    }
}
