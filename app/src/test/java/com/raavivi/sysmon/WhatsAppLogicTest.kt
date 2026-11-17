package com.raavivi.sysmon

import com.raavivi.sysmon.ui.whatsapp.formatClock
import com.raavivi.sysmon.ui.whatsapp.isAudioMedia
import com.raavivi.sysmon.ui.whatsapp.nextSpeed
import com.raavivi.sysmon.ui.whatsapp.speedLabel
import com.raavivi.sysmon.ui.whatsapp.waBeforeCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppLogicTest {
    @Test fun beforeCursorIsRfc3339() {
        assertEquals("2023-11-14T22:13:20Z", waBeforeCursor(1_700_000_000.9))
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
