package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.FindMeFix
import com.raavivi.sysmon.ui.findme.mergeTrail
import org.junit.Assert.assertEquals
import org.junit.Test

/** The incremental Find Me trail merge. */
class TrailMergeTest {

    private fun fix(ts: Double, lat: Double = 0.0) = FindMeFix(ts = ts, lat = lat)

    @Test
    fun appendsOnlyNewerFixes() {
        val held = listOf(fix(100.0), fix(200.0))
        // `start` is inclusive, so the reply repeats the newest held fix.
        val fetched = listOf(fix(200.0, lat = 9.0), fix(300.0), fix(400.0))
        val merged = mergeTrail(held, fetched, cutoff = 0.0)
        assertEquals(listOf(100.0, 200.0, 300.0, 400.0), merged.map { it.ts })
        assertEquals(0.0, merged[1].lat, 0.0) // the held copy is kept, not duplicated
    }

    @Test
    fun dropsFixesOlderThanCutoff() {
        val held = listOf(fix(100.0), fix(200.0), fix(300.0))
        val merged = mergeTrail(held, listOf(fix(300.0), fix(400.0)), cutoff = 250.0)
        assertEquals(listOf(300.0, 400.0), merged.map { it.ts })
    }

    @Test
    fun nothingNewKeepsTrail() {
        val held = listOf(fix(100.0), fix(200.0))
        assertEquals(held, mergeTrail(held, listOf(fix(200.0)), cutoff = 0.0))
        assertEquals(held, mergeTrail(held, emptyList(), cutoff = 0.0))
    }

    @Test
    fun emptyHeldTakesEverythingInRange() {
        val merged = mergeTrail(emptyList(), listOf(fix(100.0), fix(200.0)), cutoff = 150.0)
        assertEquals(listOf(200.0), merged.map { it.ts })
    }
}
