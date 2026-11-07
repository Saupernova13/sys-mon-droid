package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.HostInfo
import com.raavivi.sysmon.ui.common.formatAxisTime
import com.raavivi.sysmon.ui.more.describeDelay
import com.raavivi.sysmon.ui.more.powerPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerTextTest {

    @Test fun describeDelayPluralises() {
        assertEquals("5 seconds", describeDelay(5))
        assertEquals("1 second", describeDelay(1))
        assertEquals("1 minute", describeDelay(60))
        assertEquals("2 minutes", describeDelay(120))
        assertEquals("90 seconds", describeDelay(90))
    }

    @Test fun promptDefaultsToWindowsFiveSeconds() {
        assertEquals("Restart Windows in 5 seconds?", powerPrompt("Restart", null))
    }

    @Test fun promptOnLinuxHost() {
        val host = HostInfo(os = "linux", powerDelayS = 60)
        assertEquals("Shut down this machine in 1 minute?", powerPrompt("Shut down", host))
    }

    @Test fun axisLabelUsesDatesForLongSpans() {
        val ts = 1_700_000_000.0
        assertTrue(formatAxisTime(ts, 30 * 86_400.0).matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        assertTrue(formatAxisTime(ts, 3_600.0).matches(Regex("\\d{2}:\\d{2}")))
    }
}
