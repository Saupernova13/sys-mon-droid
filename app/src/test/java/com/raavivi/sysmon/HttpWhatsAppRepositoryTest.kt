package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.whatsapp.parseLiveFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpWhatsAppRepositoryTest {

    @Test
    fun `a live frame maps to a message`() {
        val m = parseLiveFrame(
            """{"MsgID":"3EB0AAAA","ChatJID":"27820000001@s.whatsapp.net","FromMe":false,"Timestamp":1700000000,"Text":"hi"}""",
        )!!

        assertEquals("3EB0AAAA", m.id)
        assertEquals("27820000001@s.whatsapp.net", m.chat)
        assertEquals("hi", m.text)
        assertFalse(m.fromMe)
    }

    @Test
    fun `a reaction frame keeps its target and emoji`() {
        val m = parseLiveFrame(
            """{"MsgID":"R1","ChatJID":"27820000001@s.whatsapp.net","ReactionToID":"3EB0AAAA","ReactionEmoji":"+1"}""",
        )!!

        assertTrue(m.isReaction)
        assertEquals("3EB0AAAA", m.reactionToId)
    }

    @Test
    fun `frames that are not json objects are dropped`() {
        assertNull(parseLiveFrame("not json"))
        assertNull(parseLiveFrame("[1,2]"))
        assertNull(parseLiveFrame(""))
    }
}
