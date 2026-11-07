package com.raavivi.sysmon

import com.raavivi.sysmon.ui.common.Metric
import com.raavivi.sysmon.ui.common.Severity
import com.raavivi.sysmon.ui.common.band
import com.raavivi.sysmon.ui.common.diskTempMetric
import com.raavivi.sysmon.ui.common.healthState
import com.raavivi.sysmon.ui.common.worst
import org.junit.Assert.assertEquals
import org.junit.Test

class SeverityTest {

    @Test
    fun loadCautionEdge() {
        assertEquals(Severity.Nominal, band(Metric.CpuLoad, 91.9))
        assertEquals(Severity.Caution, band(Metric.CpuLoad, 92.0))
    }

    @Test
    fun loadNeverWarns() {
        assertEquals(Severity.Caution, band(Metric.CpuLoad, 100.0))
        assertEquals(Severity.Caution, band(Metric.GpuLoad, 100.0))
    }

    @Test
    fun ramBands() {
        assertEquals(Severity.Nominal, band(Metric.RamUsed, 84.9))
        assertEquals(Severity.Caution, band(Metric.RamUsed, 85.0))
        assertEquals(Severity.Caution, band(Metric.RamUsed, 94.9))
        assertEquals(Severity.Warning, band(Metric.RamUsed, 95.0))
    }

    @Test
    fun missingOrNonFiniteIsNominal() {
        assertEquals(Severity.Nominal, band(Metric.CpuTemp, null))
        assertEquals(Severity.Nominal, band(Metric.CpuTemp, Double.NaN))
        assertEquals(Severity.Nominal, band(Metric.CpuTemp, Double.POSITIVE_INFINITY))
    }

    @Test
    fun diskTempMetricPrecedence() {
        assertEquals(Metric.DiskTempHdd, diskTempMetric("SSD", true))
        assertEquals(Metric.DiskTempSsd, diskTempMetric("HDD", false))
        assertEquals(Metric.DiskTempHdd, diskTempMetric("HDD", null))
        assertEquals(Metric.DiskTempSsd, diskTempMetric("SSD", null))
        assertEquals(Metric.DiskTempSsd, diskTempMetric(null, null))
    }

    @Test
    fun healthMapping() {
        assertEquals(Severity.Nominal, healthState("good"))
        assertEquals(Severity.Caution, healthState("caution"))
        assertEquals(Severity.Warning, healthState("bad"))
        assertEquals(Severity.Offline, healthState("unknown"))
        assertEquals(Severity.Offline, healthState(null))
    }

    @Test
    fun worstOrdering() {
        assertEquals(Severity.Warning, worst(Severity.Nominal, Severity.Warning, Severity.Caution))
        assertEquals(Severity.Nominal, worst(Severity.Offline, Severity.Nominal))
        assertEquals(Severity.Nominal, worst())
        assertEquals("CAUTION", Severity.Caution.label)
    }
}
