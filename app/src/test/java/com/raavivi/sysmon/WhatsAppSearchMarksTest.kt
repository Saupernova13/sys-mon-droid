package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WaSearchTerm
import com.raavivi.sysmon.ui.whatsapp.Span
import com.raavivi.sysmon.ui.whatsapp.findTermRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Term matching, mirroring the web whatsapp-search-marks tests. */
class WhatsAppSearchMarksTest {

    private fun term(text: String, prefix: Boolean = false, phrase: Boolean = false, negated: Boolean = false, indexable: Boolean = true) =
        WaSearchTerm(text, prefix, phrase, negated, indexable)

    private fun slices(text: String, vararg terms: WaSearchTerm) =
        findTermRanges(text, terms.toList()).map { text.substring(it.start, it.end) }

    @Test
    fun `case folds in both directions`() {
        assertEquals(listOf("CINEMA"), slices("Go to the CINEMA now", term("cinema")))
        assertEquals(listOf("cinema"), slices("go to the cinema now", term("CiNeMa")))
    }

    @Test
    fun `diacritics fold in both directions and keep the original span`() {
        assertEquals(listOf("café"), slices("a quiet café", term("cafe")))
        assertEquals(listOf("cafe"), slices("a quiet cafe", term("café")))
        assertEquals(listOf(Span(8, 12)), findTermRanges("a quiet café", listOf(term("cafe"))))
    }

    @Test
    fun `a decomposed accent still lands on the whole letter`() {
        assertEquals(listOf(Span(0, 5)), findTermRanges("café au lait", listOf(term("cafe"))))
    }

    @Test
    fun `only token starts match`() {
        assertEquals(listOf("art", "art"), slices("start art artist party", term("art", prefix = true)))
        assertTrue(findTermRanges("start", listOf(term("art", prefix = true))).isEmpty())
    }

    @Test
    fun `a prefix term runs on and an exact term must end at a token boundary`() {
        assertEquals(listOf("cinema", "cinema"), slices("cinemas and cinema", term("cinema", prefix = true)))
        assertEquals(listOf("cinema"), slices("cinemas and cinema", term("cinema")))
    }

    @Test
    fun `punctuation and string ends are token boundaries`() {
        assertEquals(listOf("art", "art", "art"), slices("(art), \"art\"; art", term("art")))
    }

    @Test
    fun `digits are token characters`() {
        assertEquals(listOf("12"), slices("room 12 and 123", term("12")))
        assertEquals(listOf("12", "12"), slices("room 12 and 123", term("12", prefix = true)))
    }

    @Test
    fun `every word of a phrase is highlighted on its own and a phrase never prefixes its last word`() {
        assertEquals(listOf("red", "car", "red"), slices("big red car, red big", term("red car", phrase = true)))
        assertEquals(listOf("red"), slices("red cars", term("red car", phrase = true, prefix = true)))
    }

    @Test
    fun `a term with no letter or digit matches as a plain substring`() {
        val smile = term("😀", indexable = false)

        assertEquals(listOf("😀", "😀"), slices("ha 😀 😀 ok", smile))
        assertEquals(listOf(Span(3, 5)), findTermRanges("ha 😀 ok", listOf(smile)))
        assertEquals(listOf("--"), slices("a-b -- c", term("--", indexable = false)))
    }

    @Test
    fun `multi-byte text keeps its indices in the original string`() {
        val text = "😀 café 日本語 cinema"

        assertEquals(listOf("cinema"), slices(text, term("cinema")))
        assertEquals(listOf("日本語"), slices(text, term("日本語")))
        assertEquals(listOf("cinema"), slices("😀cinema", term("cinema")))
    }

    @Test
    fun `negated terms never mark`() {
        assertEquals(listOf(Span(0, 3)), findTermRanges("red car", listOf(term("red"), term("car", negated = true))))
        assertTrue(findTermRanges("red car", listOf(term("red", negated = true))).isEmpty())
    }

    @Test
    fun `overlapping and touching ranges merge and come back sorted`() {
        assertEquals(listOf(Span(0, 6)), findTermRanges("cinema", listOf(term("cin", prefix = true), term("cinema"))))
        assertEquals(listOf(Span(0, 2)), findTermRanges("ab", listOf(term("a", indexable = false), term("b", indexable = false))))
        assertEquals(listOf(Span(0, 3), Span(8, 13)), findTermRanges("one two three", listOf(term("three"), term("one"))))
    }

    @Test
    fun `nothing to find is an empty list`() {
        assertTrue(findTermRanges("", listOf(term("x"))).isEmpty())
        assertTrue(findTermRanges("text", emptyList()).isEmpty())
        assertTrue(findTermRanges("text", listOf(term(""))).isEmpty())
        assertTrue(findTermRanges("text", listOf(term("", indexable = false))).isEmpty())
    }
}
