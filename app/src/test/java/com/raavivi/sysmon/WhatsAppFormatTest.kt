package com.raavivi.sysmon

import com.raavivi.sysmon.ui.whatsapp.FmtKind
import com.raavivi.sysmon.ui.whatsapp.WA_SENDER_COLORS
import com.raavivi.sysmon.ui.whatsapp.formatWaText
import com.raavivi.sysmon.ui.whatsapp.waEmojiOnlyCount
import com.raavivi.sysmon.ui.whatsapp.waJumboSize
import com.raavivi.sysmon.ui.whatsapp.waSenderColorIndex
import com.raavivi.sysmon.ui.whatsapp.waWaveformBars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of sys-mon's frontend/tests/whatsapp-format.test.js; the expected strings are the web's HTML shape. */
class WhatsAppFormatTest {

    /** Debug rendering of the spans: b i s c (inline code) q l cb tags, and <a href> for links. */
    private fun fmt(input: String?): String {
        val f = formatWaText(input)
        val tag = mapOf(
            FmtKind.BOLD to "b", FmtKind.ITALIC to "i", FmtKind.STRIKE to "s", FmtKind.CODE to "c",
            FmtKind.CODE_BLOCK to "cb", FmtKind.QUOTE to "q", FmtKind.LIST to "l", FmtKind.LINK to "a",
        )
        val opens = HashMap<Int, MutableList<String>>()
        val closes = HashMap<Int, MutableList<String>>()
        // Wider spans open first and close last at a shared boundary; of equal ranges the later-added is outer.
        for ((_, sp) in f.spans.withIndex().sortedWith(compareBy({ it.value.start }, { -it.value.end }, { -it.index }))) {
            val t = tag.getValue(sp.kind)
            val open = when (sp.kind) {
                FmtKind.LINK -> "<a href=${sp.data}>"
                FmtKind.LIST -> "<l ${sp.data}>"
                else -> "<$t>"
            }
            opens.getOrPut(sp.start) { ArrayList() }.add(open)
            closes.getOrPut(sp.end) { ArrayList() }.add(0, "</$t>")
        }
        val sb = StringBuilder()
        for (i in 0..f.text.length) {
            closes[i]?.forEach { sb.append(it) }
            opens[i]?.forEach { sb.append(it) }
            if (i < f.text.length) sb.append(f.text[i])
        }
        return sb.toString()
    }

    @Test fun singleAndDoubledMarkers() {
        assertEquals("<b>bold</b>", fmt("*bold*"))
        assertEquals("<b>bold</b>", fmt("**bold**"))
        assertEquals("<i>it</i> and <i>it2</i>", fmt("_it_ and __it2__"))
        assertEquals("<s>s</s> <s>s2</s>", fmt("~s~ ~~s2~~"))
    }

    @Test fun inlineCodeIsRawInside() {
        assertEquals("<c>code *x*</c>", fmt("`code *x*`"))
    }

    @Test fun nesting() {
        assertEquals("<b><i>both</i></b>", fmt("*_both_*"))
        assertEquals("<b>bold <i>and italic</i> end</b>", fmt("*bold _and italic_ end*"))
    }

    @Test fun openingMarkerRules() {
        assertEquals("(<b>bold</b>)", fmt("(*bold*)"))
        assertEquals("\"<b>q</b>\"", fmt("\"*q*\""))
        assertEquals("a <b>b</b> c", fmt("a *b* c"))
    }

    @Test fun closingMarkerRules() {
        assertEquals("<b>bold</b>!", fmt("*bold*!"))
        assertEquals("<b>bold</b>, then", fmt("*bold*, then"))
    }

    @Test fun markersInsideWordsStayLiteral() {
        assertEquals("snake_case_word", fmt("snake_case_word"))
        assertEquals("a*b*c", fmt("a*b*c"))
        assertEquals("2 * 3 * 4", fmt("2 * 3 * 4"))
    }

    @Test fun unmatchedMarkersRenderLiterally() {
        assertEquals("*oops", fmt("*oops"))
        assertEquals("**a*", fmt("**a*"))
        assertEquals("oops_", fmt("oops_"))
        assertEquals("***x***", fmt("***x***"))
    }

    @Test fun aStyleNeverCrossesANewline() {
        assertEquals("*a\nb*", fmt("*a\nb*"))
    }

    @Test fun aCrossedPairClosesTheFirstAndLeavesTheRestLiteral() {
        assertEquals("<i>a *b</i> c*", fmt("_a *b_ c*"))
    }

    @Test fun emptyAndNullInput() {
        assertEquals("", fmt(""))
        assertEquals("", fmt(null))
    }

    @Test fun underscoresInAUrlAreNotItalics() {
        val f = formatWaText("see https://a.com/x_y_z ok")
        assertEquals("see https://a.com/x_y_z ok", f.text)
        assertEquals(listOf(FmtKind.LINK), f.spans.map { it.kind })
        assertEquals("https://a.com/x_y_z", f.spans[0].data)
    }

    @Test fun aStyleMayWrapALinkAndKeepsItsMarkerOutOfTheLink() {
        assertEquals("<b><a href=https://a.com/x_y>https://a.com/x_y</a></b>", fmt("*https://a.com/x_y*"))
    }

    @Test fun wwwLinksGetHttpsAndTrailingPunctuationStaysOutside() {
        val f = formatWaText("go to www.x.com/a_b.")
        val link = f.spans.single { it.kind == FmtKind.LINK }
        assertEquals("https://www.x.com/a_b", link.data)
        assertEquals("www.x.com/a_b", f.text.substring(link.start, link.end))
        assertTrue(f.text.endsWith("."))
    }

    @Test fun quoteBulletsAndNumberedLines() {
        assertEquals(
            "<q>q</q><l •>• b</l><l •>• c</l><l 1.>1. n</l>",
            fmt("> q\n- b\n* c\n1. n"),
        )
    }

    @Test fun aStarFollowedByAWordIsBoldNotABullet() {
        assertEquals("<b>word</b>", fmt("*word*"))
    }

    @Test fun inlineStylesWorkInsideAQuoteAndABullet() {
        assertEquals("<q><b>x</b></q>", fmt("> *x*"))
        assertTrue(fmt("- _y_").contains("<i>y</i>"))
    }

    @Test fun plainLinesKeepTheirNewlines() {
        assertEquals("a\nb\n\nc", fmt("a\nb\n\nc"))
    }

    @Test fun codeBlocksSpanLinesAndAreNeverFormattedInside() {
        assertEquals("<cb>a *b*\nc</cb>", fmt("```a *b*\nc```"))
        assertEquals("x <cb>y</cb> z", fmt("x ```y``` z"))
    }

    @Test fun aCodeBlockOnItsOwnLinesAddsNoStrayBlankLines() {
        assertEquals("hi<cb>code</cb>bye", fmt("hi\n```\ncode\n```\nbye"))
    }

    @Test fun anUnclosedBlockStaysLiteral() {
        assertEquals("```oops", fmt("```oops"))
    }

    @Test fun trailingWhitespaceIsDropped() {
        assertEquals("hi", fmt("hi  \n\n"))
    }

    @Test fun theMarkupIsNeverInterpretedAndThePrivateUseCharCannotForgeALink() {
        assertEquals("<b><img src=x onerror=alert(1)></b>", fmt("*<img src=x onerror=alert(1)>*"))
        assertEquals("ab", fmt("ab"))
    }

    @Test fun emojiOnlyOneToThree() {
        assertEquals(1, waEmojiOnlyCount("👍"))
        assertEquals(2, waEmojiOnlyCount("😂😂"))
        assertEquals(3, waEmojiOnlyCount("🎉 🎉 🎉"))
    }

    @Test fun skinTonesFlagsZwjAndSelectorsCountOnce() {
        assertEquals(1, waEmojiOnlyCount("👍🏽"))
        assertEquals(1, waEmojiOnlyCount("🇿🇦"))
        assertEquals(1, waEmojiOnlyCount("❤️"))
        assertEquals(1, waEmojiOnlyCount("👨‍👩‍👧"))
        assertEquals(1, waEmojiOnlyCount("1️⃣"))
    }

    @Test fun fourOrMoreOrAnyOtherTextIsNotJumbo() {
        val party = "🎉"
        assertEquals(0, waEmojiOnlyCount(party.repeat(4)))
        assertEquals(0, waEmojiOnlyCount("hi 👍"))
        assertEquals(0, waEmojiOnlyCount("1"))
        assertEquals(0, waEmojiOnlyCount(""))
        assertEquals(0, waEmojiOnlyCount("©"))
        assertEquals(0, waEmojiOnlyCount(null))
    }

    @Test fun jumboSizes() {
        assertEquals(listOf(48, 40, 32), listOf(1, 2, 3).map(::waJumboSize))
    }

    @Test fun senderColourMatchesTheCrossPlatformHash() {
        assertEquals(0, waSenderColorIndex(""))
        assertEquals(97 % 8, waSenderColorIndex("a"))
        assertEquals((97 * 31 + 98) % 8, waSenderColorIndex("ab"))
        // "polygenelubricants".hashCode() is Int.MIN_VALUE: abs must not stay negative
        assertEquals(0, waSenderColorIndex("polygenelubricants"))
        // values produced by the web widget's senderColorIndex
        assertEquals(2, waSenderColorIndex("27820000001@s.whatsapp.net"))
        assertEquals(5, waSenderColorIndex("120363000000000001@g.us"))
        assertEquals(0, waSenderColorIndex("x"))
        assertEquals(4, waSenderColorIndex("Ab😀"))
    }

    @Test fun senderColourIsStableAndInRange() {
        for (jid in listOf("27820000001@s.whatsapp.net", "120363000000000001@g.us", "x")) {
            val i = waSenderColorIndex(jid)
            assertEquals(i, waSenderColorIndex(jid))
            assertTrue(i in 0..7)
        }
        assertEquals(8, WA_SENDER_COLORS.size)
    }

    @Test fun waveformMatchesTheWebWidget() {
        val web = listOf(
            0.72f, 0.92f, 0.64f, 0.35f, 0.6f, 0.59f, 0.52f, 0.7f, 0.57f, 0.84f, 0.72f, 0.7f, 0.85f, 0.43f,
            0.62f, 0.87f, 0.76f, 0.29f, 0.92f, 0.54f, 0.47f, 0.27f, 0.87f, 0.41f, 0.63f, 0.42f, 0.48f, 0.32f,
        )
        assertEquals(web, waWaveformBars("3EB0ABC"))
        assertEquals(listOf(0.72f, 0.3f, 0.7f, 0.27f, 0.91f, 0.31f), waWaveformBars("A", 6))
    }

    @Test fun waveformBarsAre28InRangeAndDifferPerId() {
        val a = waWaveformBars("3EB0ABC")
        assertEquals(28, a.size)
        assertTrue(a.all { it in 0.2f..1f })
        assertNotEquals(waWaveformBars("A"), waWaveformBars("B"))
    }
}
