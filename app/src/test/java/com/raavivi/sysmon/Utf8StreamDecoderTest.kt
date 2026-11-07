package com.raavivi.sysmon

import com.raavivi.sysmon.core.term.Utf8StreamDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8StreamDecoderTest {

    // U+20AC EURO SIGN is three bytes: E2 82 AC.
    private val euro = "€".toByteArray(Charsets.UTF_8)

    @Test fun asciiPassesThrough() {
        assertEquals("hello", Utf8StreamDecoder().decode("hello".toByteArray()))
    }

    @Test fun threeByteCharSplitOnePlusTwo() {
        val d = Utf8StreamDecoder()
        val a = d.decode(byteArrayOf(0x41, euro[0]))
        val b = d.decode(byteArrayOf(euro[1], euro[2], 0x42))
        assertEquals("A", a)
        assertEquals("€B", b)
    }

    @Test fun threeByteCharSplitTwoPlusOne() {
        val d = Utf8StreamDecoder()
        val a = d.decode(byteArrayOf(euro[0], euro[1]))
        val b = d.decode(byteArrayOf(euro[2]))
        assertEquals("", a)
        assertEquals("€", b)
    }

    @Test fun malformedByteIsReplaced() {
        assertEquals("a�b", Utf8StreamDecoder().decode(byteArrayOf(0x61, 0xFF.toByte(), 0x62)))
    }

    @Test fun resetDropsPendingBytes() {
        val d = Utf8StreamDecoder()
        d.decode(byteArrayOf(euro[0]))
        d.reset()
        assertEquals("x", d.decode("x".toByteArray()))
    }
}
