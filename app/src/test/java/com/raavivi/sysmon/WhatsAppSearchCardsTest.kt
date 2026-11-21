package com.raavivi.sysmon

import com.raavivi.sysmon.ui.whatsapp.PICK_NOT_HERE
import com.raavivi.sysmon.ui.whatsapp.PickState
import com.raavivi.sysmon.ui.whatsapp.pickLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the Found it / Not here buttons read after a save. */
class WhatsAppSearchCardsTest {

    @Test
    fun `pick buttons read Saved, Not saved or their own label`() {
        val saved = PickState(saved = 1, marked = 3)
        assertEquals("Saved", pickLabel("Found it", 1, saved))
        assertEquals("Found it", pickLabel("Found it", 0, saved))
        assertEquals("Not here", pickLabel("Not here", PICK_NOT_HERE, saved))
        assertEquals("Not saved", pickLabel("Not here", PICK_NOT_HERE, PickState(failed = PICK_NOT_HERE)))
        assertEquals("Saved", pickLabel("Not here", PICK_NOT_HERE, PickState(saved = PICK_NOT_HERE)))
    }
}
