package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.splitQuotedDisplay
import com.raavivi.sysmon.core.model.toWaChat
import com.raavivi.sysmon.core.model.toWaMessage
import com.raavivi.sysmon.ui.whatsapp.DayRow
import com.raavivi.sysmon.ui.whatsapp.MsgRow
import com.raavivi.sysmon.ui.whatsapp.WaMediaKind
import com.raavivi.sysmon.ui.whatsapp.WA_EDIT_WINDOW_SEC
import com.raavivi.sysmon.ui.whatsapp.WA_REVOKE_WINDOW_SEC
import com.raavivi.sysmon.ui.whatsapp.WA_SWIPE_FREE
import com.raavivi.sysmon.ui.whatsapp.WA_SWIPE_MAX
import com.raavivi.sysmon.ui.whatsapp.WA_SWIPE_TRIGGER
import com.raavivi.sysmon.ui.whatsapp.buildRows
import com.raavivi.sysmon.ui.whatsapp.waAggregateReactions
import com.raavivi.sysmon.ui.whatsapp.waApplyLiveUpdate
import com.raavivi.sysmon.ui.whatsapp.waApplyReaction
import com.raavivi.sysmon.ui.whatsapp.waCanEdit
import com.raavivi.sysmon.ui.whatsapp.waCanRevoke
import com.raavivi.sysmon.ui.whatsapp.waCaptionOf
import com.raavivi.sysmon.ui.whatsapp.waChatMatches
import com.raavivi.sysmon.ui.whatsapp.waChatTime
import com.raavivi.sysmon.ui.whatsapp.waDayKey
import com.raavivi.sysmon.ui.whatsapp.waDayLabel
import com.raavivi.sysmon.ui.whatsapp.waForwardLabel
import com.raavivi.sysmon.ui.whatsapp.waGalleryItems
import com.raavivi.sysmon.ui.whatsapp.waKindFromType
import com.raavivi.sysmon.ui.whatsapp.waMediaBox
import com.raavivi.sysmon.ui.whatsapp.waMediaKind
import com.raavivi.sysmon.ui.whatsapp.waMenuActions
import com.raavivi.sysmon.ui.whatsapp.WaMenuAction
import com.raavivi.sysmon.ui.whatsapp.waNameBySender
import com.raavivi.sysmon.ui.whatsapp.waNeedsDaySep
import com.raavivi.sysmon.ui.whatsapp.waPhoneSubtitle
import com.raavivi.sysmon.ui.whatsapp.waPlanMeta
import com.raavivi.sysmon.ui.whatsapp.waPreviewLine
import com.raavivi.sysmon.ui.whatsapp.waQuoteView
import com.raavivi.sysmon.ui.whatsapp.waReactionSig
import com.raavivi.sysmon.ui.whatsapp.waRowIndexOf
import com.raavivi.sysmon.ui.whatsapp.waSameGroup
import com.raavivi.sysmon.ui.whatsapp.waSortChats
import com.raavivi.sysmon.ui.whatsapp.waSummarizeReactions
import com.raavivi.sysmon.ui.whatsapp.waSwipeOffset
import com.raavivi.sysmon.ui.whatsapp.waSwipeProgress
import com.raavivi.sysmon.ui.whatsapp.waSwipeReached
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class WhatsAppModelTest {
    private val utc: ZoneId = ZoneOffset.UTC

    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Double =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, utc).toEpochSecond().toDouble()

    private fun msg(
        id: String, ts: Double, sender: String = "a@s.whatsapp.net", fromMe: Boolean = false, text: String = id,
        reactionTo: String = "", emoji: String = "", deletedForMe: Boolean = false, mediaType: String? = null,
    ) = WaMessage(
        id = id, chat = "c", chatName = "", sender = sender, senderName = "", fromMe = fromMe, ts = ts, text = text,
        mediaType = mediaType, filename = null, downloaded = false,
        reactionToId = reactionTo, reactionEmoji = emoji, deletedForMe = deletedForMe,
    )

    // -- normalisation ---------------------------------------------------------

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun normalisesTheNewMessageFieldsFromPascalCase() {
        val m = parse(
            """{"MsgID":"X1","ChatJID":"1@g.us","SenderJID":"2@s.whatsapp.net","FromMe":false,"Timestamp":1700000000,
               "Text":"hi","MediaType":"image","MimeType":"image/jpeg","width":640,"height":480,"media_ready":true,
               "IsForwarded":true,"ForwardingScore":7,"Edited":true,"Starred":true,"Revoked":false,"DeletedForMe":true,
               "ReplyToID":"Q1","ReplyToSenderJID":"3@s.whatsapp.net","ReplyToDisplay":"quoted"}""",
        ).toWaMessage()
        assertEquals("image/jpeg", m.mime)
        assertEquals(640, m.width)
        assertEquals(480, m.height)
        assertTrue(m.downloaded)
        assertTrue(m.forwarded)
        assertEquals(7, m.forwardScore)
        assertTrue(m.edited && m.starred && m.deletedForMe)
        assertFalse(m.revoked)
        assertEquals("Q1", m.replyToId)
        assertEquals("3@s.whatsapp.net", m.replyToSender)
        assertEquals("quoted", m.replyToText)
    }

    @Test fun anOlderServerRowStillParsesWithDefaults() {
        val m = parse("""{"MsgID":"X1","ChatJID":"1@s.whatsapp.net","Timestamp":1700000000,"Text":"hello"}""").toWaMessage()
        assertEquals("hello", m.text)
        assertEquals(0, m.width)
        assertFalse(m.forwarded || m.edited || m.revoked || m.isReaction)
    }

    @Test fun aReactionRowIsFlaggedAsOne() {
        val m = parse("""{"MsgID":"R1","ChatJID":"1@s.whatsapp.net","ReactionToID":"X1","ReactionEmoji":"👍"}""").toWaMessage()
        assertTrue(m.isReaction)
        assertEquals("X1", m.reactionToId)
    }

    @Test fun replyQuoteIsPulledOutOfTheDisplayTextQuoteLines() {
        val (body, quoted) = splitQuotedDisplay(rawText = "my answer", display = "> the question\n> line 2\nmy answer")
        assertEquals("my answer", body)
        assertEquals("the question\nline 2", quoted)
    }

    @Test fun quoteExtractionFillsAnEmptyBodyFromTheNonQuoteLines() {
        val (body, quoted) = splitQuotedDisplay(rawText = "", display = ">q\n  answer  ")
        assertEquals("answer", body)
        assertEquals("q", quoted)
    }

    @Test fun displayTextEqualToTextAndCaptionFallbacks() {
        assertEquals("a > b" to "", splitQuotedDisplay("a > b", "a > b"))
        assertEquals("cap" to "", splitQuotedDisplay("", "", caption = "cap"))
    }

    @Test fun chatPreviewFieldsAreParsed() {
        val c = parse(
            """{"jid":"1@g.us","name":"G","preview":"hey","preview_type":"image","preview_from_me":true,"preview_sender":"Sam"}""",
        ).toWaChat()
        assertEquals("hey", c.preview)
        assertEquals("image", c.previewType)
        assertTrue(c.previewFromMe)
        assertEquals("Sam", c.previewSender)
        assertFalse(c.previewRevoked)
        assertTrue(parse("""{"jid":"1@g.us","preview_type":"revoked"}""").toWaChat().previewRevoked)
    }

    // -- days ------------------------------------------------------------------

    @Test fun dayLabels() {
        val now = at(2026, 11, 18)   // a Wednesday
        assertEquals("Today", waDayLabel(at(2026, 11, 18, 1), now, utc))
        assertEquals("Yesterday", waDayLabel(at(2026, 11, 17, 23), now, utc))
        assertEquals("Monday", waDayLabel(at(2026, 11, 16), now, utc))
        assertEquals("Thursday", waDayLabel(at(2026, 11, 12), now, utc))   // 6 days ago still a weekday
        assertEquals("11 November", waDayLabel(at(2026, 11, 11), now, utc))   // 7 days ago is a date
        assertEquals("12 March", waDayLabel(at(2026, 3, 12), now, utc))
        assertEquals("12 March 2025", waDayLabel(at(2025, 3, 12), now, utc))
        assertEquals("Mon", waDayLabel(at(2026, 11, 16), now, utc, short = true))
        assertEquals("12 Mar", waDayLabel(at(2026, 3, 12), now, utc, short = true))
    }

    @Test fun chatTimeIsClockToday() {
        val now = at(2026, 11, 18, 15)
        assertEquals("09:05", waChatTime(at(2026, 11, 18, 9, 5), now, utc))
        assertEquals("Yesterday", waChatTime(at(2026, 11, 17, 9, 5), now, utc))
    }

    @Test fun dayKeyUsesTheZone() {
        val ts = at(2026, 11, 18, 23, 30)
        assertEquals("2026-11-18", waDayKey(ts, utc))
        assertEquals("2026-11-19", waDayKey(ts, ZoneOffset.ofHours(2)))
    }

    // -- grouping + rows -------------------------------------------------------

    @Test fun groupingNeedsSameSideSenderWindowAndDay() {
        val t = at(2026, 11, 18, 10)
        val a = msg("a", t)
        assertTrue(waSameGroup(a, msg("b", t + 299), utc))
        assertFalse(waSameGroup(a, msg("b", t + 301), utc))
        assertFalse(waSameGroup(a, msg("b", t + 10, sender = "other@s.whatsapp.net"), utc))
        assertFalse(waSameGroup(a, msg("b", t + 10, fromMe = true), utc))
        assertTrue(waSameGroup(msg("m1", t, fromMe = true, sender = ""), msg("m2", t + 5, fromMe = true, sender = "x"), utc))
        assertFalse(waSameGroup(null, a, utc))
        assertFalse(waSameGroup(a, null, utc))
        // within 5 minutes but across midnight
        assertFalse(waSameGroup(msg("a", at(2026, 11, 18, 23, 58)), msg("b", at(2026, 11, 19, 0, 1)), utc))
        assertFalse(waSameGroup(msg("a", 0.0), msg("b", 1.0), utc))
    }

    @Test fun daySeparatorBeforeTheFirstMessageAndOnEachNewDay() {
        val m1 = msg("1", at(2026, 11, 17, 10))
        val m2 = msg("2", at(2026, 11, 17, 11))
        val m3 = msg("3", at(2026, 11, 18, 9))
        assertTrue(waNeedsDaySep(null, m1, utc))
        assertFalse(waNeedsDaySep(m1, m2, utc))
        assertTrue(waNeedsDaySep(m2, m3, utc))
        assertFalse(waNeedsDaySep(null, msg("x", 0.0), utc))
    }

    @Test fun rowsInterleaveDayHeadersAndFlagGroupEnds() {
        val d1 = at(2026, 11, 17, 10)
        val rows = buildRows(
            listOf(
                msg("a1", d1), msg("a2", d1 + 60), msg("a3", d1 + 120),             // one run from a
                msg("b1", d1 + 180, sender = "b@s.whatsapp.net"),                    // b breaks it
                msg("a4", d1 + 4000),                                                // a again, > 5 min later
                msg("n1", at(2026, 11, 18, 8)),                                      // next day
            ),
            utc,
        )
        assertEquals(
            listOf("day:2026-11-17", "a1", "a2", "a3", "b1", "a4", "day:2026-11-18", "n1"),
            rows.map { it.key },
        )
        val flags = rows.filterIsInstance<MsgRow>().associate { it.msg.id to (it.groupFirst to it.groupLast) }
        assertEquals(true to false, flags["a1"])
        assertEquals(false to false, flags["a2"])
        assertEquals(false to true, flags["a3"])
        assertEquals(true to true, flags["b1"])
        assertEquals(true to true, flags["a4"])
        assertEquals(true to true, flags["n1"])
        assertTrue(rows[0] is DayRow)
    }

    @Test fun reactionRowsAndDeletedForMeNeverBecomeRows() {
        val t = at(2026, 11, 18, 10)
        val rows = buildRows(
            listOf(
                msg("a", t), msg("r", t + 1, reactionTo = "a", emoji = "👍"),
                msg("gone", t + 2, deletedForMe = true), msg("b", t + 3),
            ),
            utc,
        )
        assertEquals(listOf("day:2026-11-18", "a", "b"), rows.map { it.key })
        // a and b are neighbours in one group despite the hidden rows between them
        val flags = rows.filterIsInstance<MsgRow>()
        assertEquals(true to false, flags[0].groupFirst to flags[0].groupLast)
        assertEquals(false to true, flags[1].groupFirst to flags[1].groupLast)
    }

    @Test fun rowIndexLookup() {
        val rows = buildRows(listOf(msg("a", at(2026, 11, 18)), msg("b", at(2026, 11, 18) + 1)), utc)
        assertEquals(2, waRowIndexOf(rows, "b"))
        assertEquals(-1, waRowIndexOf(rows, "zzz"))
        assertEquals(-1, waRowIndexOf(rows, ""))
    }

    // -- reactions -------------------------------------------------------------

    private fun react(target: String, who: String, emoji: String, ts: Double, mine: Boolean = false) =
        msg("r-$who-$ts", ts, sender = who, fromMe = mine, reactionTo = target, emoji = emoji)

    @Test fun aggregationMergesRowsByReactor() {
        val store = waAggregateReactions(
            listOf(react("t", "a", "👍", 1.0), react("t", "b", "❤", 2.0), react("t", "x", "😂", 3.0, mine = true)),
        )
        val sum = waSummarizeReactions(store["t"])
        assertEquals(3, sum.total)
        assertEquals("😂", sum.mine)
        assertEquals("me", sum.reactors.first().key)
    }

    @Test fun aLaterEmojiFromTheSameReactorReplacesTheEarlierOne() {
        val store = waAggregateReactions(listOf(react("t", "a", "👍", 1.0), react("t", "a", "❤", 5.0)))
        val sum = waSummarizeReactions(store["t"])
        assertEquals(1, sum.total)
        assertEquals("❤", sum.top.single().emoji)
    }

    @Test fun anEmptyEmojiRemovesAndAnOlderRowCannotResurrectIt() {
        val removed = waAggregateReactions(listOf(react("t", "a", "👍", 1.0), react("t", "a", "", 5.0)))
        assertEquals(0, waSummarizeReactions(removed["t"]).total)
        // the older row arriving later (an older page) must not bring it back
        val again = waApplyReaction(removed, "t", "a", "👍", 1.0)
        assertSame(removed, again)
        assertEquals(0, waSummarizeReactions(again["t"]).total)
    }

    @Test fun aLateReactionForAnUnloadedTargetIsKept() {
        val store = waAggregateReactions(listOf(react("not-loaded-yet", "a", "👍", 1.0)))
        assertEquals(1, waSummarizeReactions(store["not-loaded-yet"]).total)
    }

    @Test fun aggregationIsOrderIndependent() {
        val rows = listOf(react("t", "a", "👍", 1.0), react("t", "a", "", 2.0), react("t", "a", "❤", 3.0))
        assertEquals(waAggregateReactions(rows), waAggregateReactions(rows.reversed()))
    }

    @Test fun topEmojiAreMostFrequentFirstTiesByFirstReactionAndCappedAtThree() {
        val rows = listOf(
            react("t", "a", "😂", 1.0), react("t", "b", "👍", 2.0), react("t", "c", "👍", 3.0),
            react("t", "d", "❤", 4.0), react("t", "e", "😮", 5.0),
        )
        val sum = waSummarizeReactions(waAggregateReactions(rows)["t"])
        assertEquals(5, sum.total)
        assertEquals(listOf("👍", "😂", "❤"), sum.top.map { it.emoji })
        assertEquals(2, sum.top[0].count)
    }

    @Test fun signatureChangesWhenTheChipShouldPop() {
        val one = waSummarizeReactions(waAggregateReactions(listOf(react("t", "a", "👍", 1.0)))["t"])
        val two = waSummarizeReactions(waAggregateReactions(listOf(react("t", "a", "👍", 1.0), react("t", "b", "👍", 2.0)))["t"])
        assertEquals("", waReactionSig(waSummarizeReactions(null)))
        assertTrue(waReactionSig(one) != waReactionSig(two))
    }

    // -- live edits / revokes --------------------------------------------------

    @Test fun aLiveEditUpdatesTheOriginalInPlace() {
        val list = listOf(msg("a", 1.0, text = "old"), msg("b", 2.0))
        val out = waApplyLiveUpdate(list, msg("a", 1.0, text = "new").copy(edited = true))!!
        assertEquals(2, out.size)
        assertEquals("new", out[0].text)
        assertTrue(out[0].edited)
        assertEquals("b", out[1].id)
    }

    @Test fun aLiveRevokeMarksTheOriginalDeleted() {
        val out = waApplyLiveUpdate(listOf(msg("a", 1.0, text = "secret")), msg("a", 1.0, text = "").copy(revoked = true))!!
        assertTrue(out.single().revoked)
        assertEquals("secret", out.single().text)
    }

    @Test fun editsForUnloadedMessagesAndPlainMessagesAreNotUpdates() {
        val list = listOf(msg("a", 1.0))
        assertNull(waApplyLiveUpdate(list, msg("zzz", 1.0).copy(edited = true)))
        assertNull(waApplyLiveUpdate(list, msg("a", 1.0)))
    }

    // -- media -----------------------------------------------------------------

    @Test fun mediaKinds() {
        assertEquals(WaMediaKind.STICKER, waMediaKind("sticker"))
        assertEquals(WaMediaKind.GIF, waMediaKind("gif"))
        assertEquals(WaMediaKind.IMAGE, waMediaKind("image"))
        assertEquals(WaMediaKind.IMAGE, waMediaKind("media", "image/jpeg"))
        assertEquals(WaMediaKind.VIDEO, waMediaKind("video"))
        assertEquals(WaMediaKind.VOICE, waMediaKind("ptt"))
        assertEquals(WaMediaKind.VOICE, waMediaKind("audio"))
        assertEquals(WaMediaKind.DOCUMENT, waMediaKind("document", "application/pdf"))
        assertNull(waMediaKind(null))
        assertNull(waKindFromType(""))
        assertNull(waKindFromType("text"))
        assertNull(waKindFromType("revoked"))
        assertEquals(WaMediaKind.VOICE, waKindFromType("voice"))
    }

    @Test fun mediaBoxScalesToFitAndReservesAMinimum() {
        assertEquals(260f to 195f, waMediaBox(1040, 780).let { it.w to it.h })       // landscape: width bound
        assertEquals(240f to 320f, waMediaBox(900, 1200).let { it.w to it.h })       // portrait: height bound
        assertEquals(140f to 320f, waMediaBox(100, 2000).let { it.w to it.h })       // tall and thin: min width
        val unknown = waMediaBox(0, 0)
        assertEquals(240f to 180f, unknown.w to unknown.h)
        assertFalse(unknown.known)
        assertTrue(waMediaBox(10, 10).known)
    }

    @Test fun captionHidesPlaceholdersAndFileNames() {
        assertEquals("", waCaptionOf(msg("a", 1.0, text = "[image]", mediaType = "image")))
        assertEquals("", waCaptionOf(msg("a", 1.0, text = "Photo", mediaType = "image")))
        assertEquals("", waCaptionOf(msg("a", 1.0, text = "report.pdf", mediaType = "document").copy(filename = "report.pdf")))
        assertEquals("look at this", waCaptionOf(msg("a", 1.0, text = "look at this", mediaType = "image")))
        assertEquals("[image]", waCaptionOf(msg("a", 1.0, text = "[image]")))   // text message: shown as typed
    }

    @Test fun forwardLabels() {
        assertEquals("", waForwardLabel(msg("a", 1.0)))
        assertEquals("Forwarded", waForwardLabel(msg("a", 1.0).copy(forwarded = true, forwardScore = 1)))
        assertEquals("Forwarded many times", waForwardLabel(msg("a", 1.0).copy(forwarded = true, forwardScore = 5)))
    }

    // -- quotes ----------------------------------------------------------------

    @Test fun quoteCardUsesTheLoadedMessageForAuthorAndMedia() {
        val target = msg("q", 1.0, sender = "b@s.whatsapp.net", mediaType = "image", text = "[image]").copy(senderName = "Bea")
        val reply = msg("r", 2.0).copy(replyToId = "q", replyToText = "")
        val names = waNameBySender(listOf(target))
        val view = waQuoteView(reply, mapOf("q" to target), names)!!
        assertEquals("Bea", view.author)
        assertEquals(WaMediaKind.IMAGE, view.mediaKind)
        assertEquals("", view.text)
    }

    @Test fun quoteCardOfMyOwnMessageSaysYouAndUnloadedFallsBackToTheJid() {
        val mine = msg("q", 1.0, fromMe = true, sender = "", text = "hello there")
        val v1 = waQuoteView(msg("r", 2.0).copy(replyToId = "q"), mapOf("q" to mine), emptyMap())!!
        assertEquals("You", v1.author)
        assertEquals("hello there", v1.text)
        val v2 = waQuoteView(
            msg("r", 2.0).copy(replyToId = "gone", replyToSender = "27820000001@s.whatsapp.net", replyToText = "x\ny"),
            emptyMap(), emptyMap(),
        )!!
        assertEquals("+27820000001", v2.author)
        assertEquals("x y", v2.text)
        assertNull(waQuoteView(msg("p", 3.0), emptyMap(), emptyMap()))
    }

    @Test fun phoneSubtitle() {
        assertEquals("+27821234567", waPhoneSubtitle("27821234567@s.whatsapp.net"))
        assertEquals("+27821234567", waPhoneSubtitle("27821234567:12@s.whatsapp.net"))
        assertEquals("", waPhoneSubtitle("120363@g.us"))
        assertEquals("", waPhoneSubtitle("abc@lid"))
        assertEquals("", waPhoneSubtitle(null))
    }

    // -- chat list preview -----------------------------------------------------

    private fun chat(preview: String = "", type: String = "", fromMe: Boolean = false, sender: String = "", revoked: Boolean = false) =
        WaChat("j", "n", 0.0, 0, false, preview, type, fromMe, sender, revoked)

    @Test fun previewIsNullWithoutServerFields() {
        assertNull(waPreviewLine(chat(), group = false))
    }

    @Test fun previewPrefixes() {
        assertEquals("You: ", waPreviewLine(chat("hi", fromMe = true), false)!!.prefix)
        assertEquals("Sam: ", waPreviewLine(chat("hi", sender = "Sam"), true)!!.prefix)
        assertEquals("", waPreviewLine(chat("hi", sender = "Sam"), false)!!.prefix)
    }

    @Test fun previewShowsAMediaLabelWhenThereIsNoText() {
        val p = waPreviewLine(chat(type = "image"), false)!!
        assertEquals("Photo", p.text)
        assertEquals(WaMediaKind.IMAGE, p.kind)
        assertEquals("Voice message", waPreviewLine(chat(type = "ptt"), false)!!.text)
        assertEquals("caption", waPreviewLine(chat("caption", "image"), false)!!.text)
    }

    @Test fun previewOfARevokedMessage() {
        val p = waPreviewLine(chat(revoked = true), false)!!
        assertEquals("This message was deleted", p.text)
        assertTrue(p.revoked)
    }

    // -- chat ordering ---------------------------------------------------------

    @Test fun chatsSortWhatsAppPinsThenLocalPinsByRankThenRecent() {
        fun ch(jid: String, ts: Double, pinned: Boolean = false, local: Boolean = false, rank: Int = 0) =
            WaChat(jid, jid, ts, 0, pinned, localPin = local, pinRank = rank)
        val sorted = waSortChats(
            listOf(
                ch("old", 1.0), ch("new", 9.0), ch("waOld", 2.0, pinned = true), ch("waNew", 8.0, pinned = true),
                ch("l2", 100.0, pinned = true, local = true, rank = 2), ch("l1", 1.0, pinned = true, local = true, rank = 1),
            ),
        )
        assertEquals(listOf("waNew", "waOld", "l1", "l2", "new", "old"), sorted.map { it.jid })
    }

    @Test fun localPinFieldsAreParsed() {
        val c = parse("""{"jid":"1@s.whatsapp.net","pinned":true,"local_pin":true,"pin_rank":3}""").toWaChat()
        assertTrue(c.localPin)
        assertEquals(3, c.pinRank)
        assertFalse(parse("""{"jid":"1@s.whatsapp.net"}""").toWaChat().localPin)
    }

    // -- meta placement --------------------------------------------------------

    @Test fun metaSharesTheLastLineWhenItFits() {
        val p = waPlanMeta(textW = 100, textH = 40, lastLineRight = 60f, metaW = 30, metaH = 14, maxW = 300, gap = 8)
        assertTrue(p.inline)
        assertEquals(100, p.width)          // text already wider than the line + meta
        assertEquals(40, p.height)
        assertEquals(70, p.metaX)
        assertEquals(26, p.metaY)
    }

    @Test fun aShortMessageGrowsToFitItsMeta() {
        val p = waPlanMeta(textW = 40, textH = 20, lastLineRight = 40f, metaW = 30, metaH = 14, maxW = 300, gap = 8)
        assertTrue(p.inline)
        assertEquals(78, p.width)
        assertEquals(48, p.metaX)
    }

    @Test fun metaWrapsToItsOwnLineWhenTheLastLineIsFull() {
        val p = waPlanMeta(textW = 300, textH = 40, lastLineRight = 290f, metaW = 30, metaH = 14, maxW = 300, gap = 8)
        assertFalse(p.inline)
        assertEquals(300, p.width)
        assertEquals(54, p.height)
        assertEquals(270, p.metaX)
        assertEquals(40, p.metaY)
    }

    @Test fun theBoundaryIsInclusiveAndAnUnknownLineWraps() {
        assertTrue(waPlanMeta(100, 20, 262f, 30, 14, 300, 8).inline)
        assertFalse(waPlanMeta(100, 20, 262.5f, 30, 14, 300, 8).inline)
        assertFalse(waPlanMeta(100, 20, null, 30, 14, 300, 8).inline)
    }

    // -- message menu (hold for more options) ----------------------------------

    @Test fun aRevokedMessageOnlyOffersInfoAndDelete() {
        val m = msg("r", at(2026, 11, 18)).copy(revoked = true)
        assertEquals(listOf(WaMenuAction.INFO, WaMenuAction.DELETE), waMenuActions(m, m.ts + 1))
    }

    @Test fun aFreshOwnTextMessageOffersTheFullSetExceptSave() {
        val now = at(2026, 11, 18, 12, 10)
        val acts = waMenuActions(msg("a", now, fromMe = true), now + 60)
        assertEquals(
            listOf(WaMenuAction.REPLY, WaMenuAction.COPY, WaMenuAction.FORWARD, WaMenuAction.EDIT, WaMenuAction.INFO, WaMenuAction.DELETE),
            acts,
        )
    }

    @Test fun someoneElsesTextNeverOffersEdit() {
        val now = at(2026, 11, 18)
        val acts = waMenuActions(msg("a", now, fromMe = false), now + 60)
        assertFalse(acts.contains(WaMenuAction.EDIT))
    }

    @Test fun editOnlyAppliesToOwnFreshText() {
        val now = at(2026, 11, 18)
        assertTrue(waCanEdit(msg("a", now, fromMe = true), now + 60))
        assertFalse(waCanEdit(msg("a", now, fromMe = true), now + WA_EDIT_WINDOW_SEC + 1))
        assertFalse(waCanEdit(msg("a", now, fromMe = false), now + 60))
        assertFalse(waCanEdit(msg("p", now, fromMe = true, mediaType = "image"), now + 60))
        assertFalse(waCanEdit(msg("r", now, fromMe = true).copy(revoked = true), now + 60))
    }

    @Test fun revokeOnlyAppliesToOwnFreshMessages() {
        val now = at(2026, 11, 18)
        assertTrue(waCanRevoke(msg("a", now, fromMe = true), now + 60))
        assertFalse(waCanRevoke(msg("a", now, fromMe = true), now + WA_REVOKE_WINDOW_SEC + 1))
        assertFalse(waCanRevoke(msg("a", now, fromMe = false), now + 60))
        assertFalse(waCanRevoke(msg("r", now, fromMe = true).copy(revoked = true), now + 60))
    }

    @Test fun mediaOffersSaveAndDropsCopyWhenThereIsNoCaption() {
        val now = at(2026, 11, 18)
        val plain = waMenuActions(msg("p", now, mediaType = "image", text = "[image]"), now + 60)
        assertTrue(plain.contains(WaMenuAction.SAVE))
        assertFalse(plain.contains(WaMenuAction.COPY))
        assertFalse(plain.contains(WaMenuAction.EDIT))
        val captioned = waMenuActions(msg("c", now, mediaType = "image", text = "Look at this"), now + 60)
        assertTrue(captioned.contains(WaMenuAction.COPY))
        assertTrue(captioned.contains(WaMenuAction.SAVE))
    }

    // -- swipe to reply ---------------------------------------------------------

    @Test fun theSwipeFollowsTheFingerThenResistsTowardTheCap() {
        assertEquals(0f, waSwipeOffset(0f), 0.01f)
        assertEquals(0f, waSwipeOffset(-30f), 0.01f)
        assertEquals(30f, waSwipeOffset(30f), 0.01f)
        assertEquals(WA_SWIPE_FREE, waSwipeOffset(WA_SWIPE_FREE), 0.01f)
        val past = waSwipeOffset(WA_SWIPE_FREE + 20)
        assertTrue("resisted, got $past", past > WA_SWIPE_FREE && past < WA_SWIPE_MAX)
        assertTrue("never exceeds the cap", waSwipeOffset(400f) < WA_SWIPE_MAX)
        assertTrue("still creeps toward the cap", waSwipeOffset(400f) > waSwipeOffset(80f))
    }

    @Test fun theReplyTriggersPastItsOffsetThreshold() {
        // Web parity: a 60px drag eases to ~54.9 (no reply), 70px reaches ~59.5 (reply).
        assertFalse(waSwipeReached(waSwipeOffset(60f)))
        assertTrue(waSwipeReached(waSwipeOffset(70f)))
        assertEquals(0f, waSwipeProgress(0f), 0.01f)
        assertEquals(1f, waSwipeProgress(WA_SWIPE_TRIGGER + 20), 0.01f)
        assertTrue(waSwipeProgress(waSwipeOffset(60f)) > 0.9f)
    }

    // -- photo gallery ----------------------------------------------------------

    @Test fun theGalleryCollectsLoadedPhotosInOrderAndNothingElse() {
        val t = at(2026, 11, 18)
        val msgs = listOf(
            msg("p1", t, mediaType = "image"),
            msg("t1", t + 1),
            msg("p2", t + 2, fromMe = true, mediaType = "image"),
            msg("s1", t + 3, mediaType = "sticker"),
            msg("r1", t + 4, reactionTo = "p1", emoji = "👍"),
            msg("p3", t + 5, mediaType = "image").copy(revoked = true),
            msg("", t + 6, mediaType = "image"),
            msg("v1", t + 7, mediaType = "video"),
        )
        assertEquals(listOf("p1", "p2"), waGalleryItems(msgs).map { it.id })
    }

    // -- chat-list search ------------------------------------------------------

    private fun searchChat(jid: String, name: String) = WaChat(jid = jid, name = name, lastTs = 1.0, unread = 0, pinned = false)

    @Test fun searchMatchesByNameRawJidAndPhoneDigits() {
        val dm = searchChat("27821234567@s.whatsapp.net", "Ravi")
        val group = searchChat("120363012345678901@g.us", "Family")
        assertTrue(waChatMatches(dm, ""))
        assertTrue(waChatMatches(dm, "   "))
        assertTrue(waChatMatches(dm, "rav"))
        assertTrue(waChatMatches(dm, "27821234567@s.whatsapp.net"))
        assertTrue(waChatMatches(dm, "34567@s.whatsapp.net"))
        assertTrue(waChatMatches(dm, "+27 82 123 4567"))
        assertTrue(waChatMatches(dm, "0821234567"))
        assertTrue(waChatMatches(dm, "082 123 4567"))
        assertFalse(waChatMatches(dm, "00821234567"))   // only one trunk 0 is dropped
        assertTrue(waChatMatches(dm, "2782"))
        assertFalse(waChatMatches(dm, "zzz"))
        assertFalse(waChatMatches(dm, "999"))
        assertFalse(waChatMatches(dm, "012"))
        assertTrue(waChatMatches(group, "fam"))
        assertTrue(waChatMatches(group, "4567890"))       // raw jid substring, not the digit rule
        assertFalse(waChatMatches(group, "888"))
        assertFalse(waChatMatches(searchChat("x@lid", "Linked"), "2782"))
    }
}
