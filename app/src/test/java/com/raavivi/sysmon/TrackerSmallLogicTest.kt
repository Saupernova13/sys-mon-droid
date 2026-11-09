package com.raavivi.sysmon

import com.raavivi.sysmon.core.tracker.cmd.HandledIds
import com.raavivi.sysmon.core.tracker.net.DeviceResult
import com.raavivi.sysmon.core.tracker.net.Backoff
import com.raavivi.sysmon.core.tracker.net.pairErrorMessage
import com.raavivi.sysmon.core.tracker.track.batteryPercent
import com.raavivi.sysmon.core.tracker.track.networkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerSmallLogicTest {

    @Test
    fun backoffSchedule() {
        val seconds = (1..8).map { Backoff.delayMs(it) / 1000 }
        assertEquals(listOf(30L, 60L, 120L, 300L, 600L, 600L, 600L, 600L), seconds)
        assertEquals(30_000L, Backoff.delayMs(0))
    }

    @Test
    fun handledIdsDedupe() {
        val ids = HandledIds()
        assertTrue(ids.markIfNew("a"))
        assertFalse(ids.markIfNew("a"))
        assertTrue(ids.markIfNew("b"))
    }

    @Test
    fun handledIdsKeepOnlyTheLast50() {
        val ids = HandledIds()
        for (i in 1..60) ids.markIfNew("c$i")
        assertEquals(50, ids.snapshot().size)
        assertTrue(ids.markIfNew("c1")) // forgotten
        assertFalse(ids.markIfNew("c60")) // still remembered
    }

    @Test
    fun handledIdsRestoreFromSnapshot() {
        val first = HandledIds()
        first.markIfNew("x")
        val restored = HandledIds(first.snapshot())
        assertFalse(restored.markIfNew("x"))
    }

    @Test
    fun networkTypeMapping() {
        assertEquals("none", networkType(connected = false, wifi = true, cellular = false, ethernet = false))
        assertEquals("wifi", networkType(connected = true, wifi = true, cellular = true, ethernet = false))
        assertEquals("ethernet", networkType(connected = true, wifi = false, cellular = false, ethernet = true))
        assertEquals("cellular", networkType(connected = true, wifi = false, cellular = true, ethernet = false))
        assertNull(networkType(connected = true, wifi = false, cellular = false, ethernet = false))
    }

    @Test
    fun batteryPercentMapping() {
        assertEquals(50, batteryPercent(50, 100))
        assertEquals(25, batteryPercent(1, 4))
        assertNull(batteryPercent(-1, 100))
        assertNull(batteryPercent(10, 0))
    }

    @Test
    fun pairErrorMessages() {
        assertEquals(
            "Code expired or already used - make a new one in sys-mon",
            pairErrorMessage(DeviceResult.Err("x", 403, "pair_code_invalid")),
        )
        assertEquals("Too many tries, wait a minute", pairErrorMessage(DeviceResult.Err("x", 429, "rate_limited")))
        assertEquals("Too many tries, wait a minute", pairErrorMessage(DeviceResult.Err("x", 429, "pair_locked")))
        assertEquals("Network error: timeout", pairErrorMessage(DeviceResult.Err("Network error: timeout")))
    }

    @Test
    fun deviceUnknownDetection() {
        assertTrue(DeviceResult.Err("x", 401, "device_unknown").isDeviceUnknown)
        assertFalse(DeviceResult.Err("x", 401, null).isDeviceUnknown)
        assertFalse(DeviceResult.Err("x", 403, "device_unknown").isDeviceUnknown)
    }
}
