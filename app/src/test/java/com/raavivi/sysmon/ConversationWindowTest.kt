package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.ui.whatsapp.ConversationWindow
import com.raavivi.sysmon.ui.whatsapp.LiveEffect
import com.raavivi.sysmon.ui.whatsapp.PageOutcome
import com.raavivi.sysmon.ui.whatsapp.WA_PAGE_LIMIT
import com.raavivi.sysmon.ui.whatsapp.waBeforeCursor
import com.raavivi.sysmon.ui.whatsapp.waInclusiveAfter
import com.raavivi.sysmon.ui.whatsapp.waInclusiveBefore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWindowTest {

    private val chat = "27820000001@s.whatsapp.net"

    private fun msg(id: String, ts: Double, fromMe: Boolean = false, text: String = "t $id") = WaMessage(
        id = id, chat = chat, chatName = "", sender = chat, senderName = "", fromMe = fromMe,
        ts = ts, text = text, mediaType = null, filename = null, downloaded = false,
    )

    private fun page(prefix: String, firstTs: Double, n: Int = WA_PAGE_LIMIT) =
        (0 until n).map { msg("$prefix$it", firstTs + it) }

    private fun windowOf(vararg m: WaMessage) = ConversationWindow(messages = m.toList())

    private fun reaction(id: String, target: String, emoji: String, ts: Double) =
        msg(id, ts).copy(reactionToId = target, reactionEmoji = emoji)

    // -- cursors and stall detection ----------------------------------------------

    @Test
    fun `cursors are inclusive first and strict on the retry`() {
        assertEquals(waInclusiveBefore(100.0), ConversationWindow.olderCursor(100.0, strict = false))
        assertEquals(waBeforeCursor(100.0), ConversationWindow.olderCursor(100.0, strict = true))
        assertEquals(waInclusiveAfter(100.0), ConversationWindow.newerCursor(100.0, strict = false))
        assertEquals(waBeforeCursor(100.0), ConversationWindow.newerCursor(100.0, strict = true))
    }

    @Test
    fun `a full page of known rows stalls once, then ends on the strict retry`() {
        assertEquals(PageOutcome.STALLED, ConversationWindow.settle(WA_PAGE_LIMIT, 0, strictRetry = false))
        assertEquals(PageOutcome.END, ConversationWindow.settle(WA_PAGE_LIMIT, 0, strictRetry = true))
    }

    @Test
    fun `a full page with new rows has more and a short page is the end`() {
        assertEquals(PageOutcome.MORE, ConversationWindow.settle(WA_PAGE_LIMIT, 3, strictRetry = false))
        assertEquals(PageOutcome.END, ConversationWindow.settle(2, 0, strictRetry = false))
    }

    @Test
    fun `paging bases ignore rows without a timestamp`() {
        assertNull(windowOf(msg("a", 0.0)).oldestTs)
        assertEquals(5.0, windowOf(msg("a", 5.0), msg("b", 9.0)).oldestTs!!, 0.0)
        assertEquals(9.0, windowOf(msg("a", 5.0), msg("b", 9.0)).newestTs!!, 0.0)
    }

    // -- merging pages ------------------------------------------------------------

    @Test
    fun `an older page is merged in order and deduped by id`() {
        val w = windowOf(msg("b", 20.0), msg("c", 30.0))

        val merged = w.merged(listOf(msg("a", 10.0), msg("b", 20.0)))

        assertEquals(listOf("a", "b", "c"), merged.window.messages.map { it.id })
        assertEquals(1, merged.added)
    }

    @Test
    fun `a newer page appends after the newest row`() {
        val merged = windowOf(msg("a", 10.0)).merged(listOf(msg("b", 20.0), msg("c", 30.0)))

        assertEquals(listOf("a", "b", "c"), merged.window.messages.map { it.id })
        assertEquals(2, merged.added)
    }

    @Test
    fun `reactions in a merged page are folded, not listed, and keep earlier ones`() {
        val w = ConversationWindow(messages = listOf(msg("a", 10.0))).withReaction("a", "me", "+1", 11.0)

        val merged = w.merged(listOf(reaction("r1", "a", "heart", 12.0)))

        assertEquals("+1", merged.window.reactions["a"]?.get("me")?.emoji)
        assertEquals("heart", merged.window.reactions["a"]?.get(chat)?.emoji)
    }

    @Test
    fun `merging the newest page marks the window at latest and keeps reachedFirst`() {
        val w = ConversationWindow(messages = listOf(msg("a", 10.0)), atLatest = false, reachedFirst = true)

        val next = w.mergedWithNewest(listOf(msg("z", 99.0)))

        assertTrue(next.atLatest)
        assertTrue(next.reachedFirst)
        assertEquals(listOf("a", "z"), next.messages.map { it.id })
    }

    // -- replacing the window -----------------------------------------------------

    @Test
    fun `the newest page replaces the rows sorted and resets reachedFirst`() {
        val w = ConversationWindow(messages = listOf(msg("old", 1.0)), reachedFirst = true)

        val next = w.replacedByNewest(listOf(msg("b", 20.0), msg("a", 10.0)))

        assertEquals(listOf("a", "b"), next.messages.map { it.id })
        assertTrue(next.atLatest)
        assertFalse(next.reachedFirst)
    }

    @Test
    fun `refreshing while at latest marks only genuinely new visible rows fresh`() {
        val next = windowOf(msg("a", 10.0)).replacedByNewest(listOf(msg("a", 10.0), msg("b", 20.0)))

        assertEquals(setOf("b"), next.freshIds)
    }

    @Test
    fun `opening a chat marks nothing fresh`() {
        assertTrue(ConversationWindow().replacedByNewest(page("n", 100.0, 3)).freshIds.isEmpty())
    }

    @Test
    fun `more than five new rows are not animated`() {
        val w = windowOf(msg("a", 1.0))

        assertTrue(w.replacedByNewest(listOf(msg("a", 1.0)) + page("n", 100.0, 6)).freshIds.isEmpty())
    }

    @Test
    fun `refreshing after a jump away from latest never marks fresh rows`() {
        val w = windowOf(msg("a", 1.0)).copy(atLatest = false)

        assertTrue(w.replacedByNewest(listOf(msg("a", 1.0), msg("b", 2.0))).freshIds.isEmpty())
    }

    @Test
    fun `a full page jump leaves the window short of latest, a short one reaches it`() {
        val full = ConversationWindow().replacedByPage(page("f", 100.0), reachedFirst = true)
        assertFalse(full.atLatest)
        assertTrue(full.reachedFirst)

        val short = ConversationWindow().replacedByPage(page("f", 100.0, 3), reachedFirst = false)
        assertTrue(short.atLatest)
        assertFalse(short.reachedFirst)
    }

    @Test
    fun `a jump replaces rows and reactions and clears fresh ids`() {
        val w = ConversationWindow(messages = listOf(msg("a", 1.0)), freshIds = setOf("a"))
            .withReaction("a", "me", "+1", 2.0)

        val next = w.replacedByPage(listOf(msg("x", 50.0)), reachedFirst = false)

        assertEquals(listOf("x"), next.messages.map { it.id })
        assertTrue(next.reactions.isEmpty())
        assertTrue(next.freshIds.isEmpty())
    }

    @Test
    fun `reopening keeps the rows but resets flags, reactions and fresh ids`() {
        val w = ConversationWindow(
            messages = listOf(msg("a", 1.0)), freshIds = setOf("a"), atLatest = false, reachedFirst = true,
        ).withReaction("a", "me", "+1", 2.0)

        val next = w.reopened()

        assertEquals(1, next.messages.size)
        assertTrue(next.atLatest)
        assertFalse(next.reachedFirst)
        assertTrue(next.reactions.isEmpty())
        assertTrue(next.freshIds.isEmpty())
    }

    @Test
    fun `withReachedFirst and clearedFresh change only their field`() {
        val w = ConversationWindow(messages = listOf(msg("a", 1.0)), freshIds = setOf("a"))

        assertTrue(w.withReachedFirst().reachedFirst)
        assertTrue(w.clearedFresh().freshIds.isEmpty())
        assertEquals(setOf("a"), w.withReachedFirst().freshIds)
    }

    // -- live frames --------------------------------------------------------------

    @Test
    fun `a live message in the open chat at latest is appended and fresh`() {
        val applied = windowOf(msg("a", 10.0)).applyLive(msg("b", 20.0), inOpenChat = true)

        assertEquals(LiveEffect.APPENDED, applied.effect)
        assertEquals(listOf("a", "b"), applied.window.messages.map { it.id })
        assertEquals(setOf("b"), applied.window.freshIds)
    }

    @Test
    fun `a live message outside the loaded window is left for the final merge`() {
        val w = windowOf(msg("a", 10.0)).copy(atLatest = false)

        val applied = w.applyLive(msg("b", 20.0), inOpenChat = true)

        assertEquals(LiveEffect.NONE, applied.effect)
        assertSame(w, applied.window)
    }

    @Test
    fun `a live message for another chat does not touch the window`() {
        val w = windowOf(msg("a", 10.0))

        val applied = w.applyLive(msg("b", 20.0), inOpenChat = false)

        assertEquals(LiveEffect.NONE, applied.effect)
        assertSame(w, applied.window)
    }

    @Test
    fun `a duplicate live message is not appended twice`() {
        val applied = windowOf(msg("a", 10.0)).applyLive(msg("a", 10.0), inOpenChat = true)

        assertEquals(LiveEffect.NONE, applied.effect)
        assertEquals(1, applied.window.messages.size)
    }

    @Test
    fun `a live reaction folds into the chip only for the open chat`() {
        val w = windowOf(msg("a", 10.0))
        val r = reaction("r1", "a", "+1", 11.0)

        val open = w.applyLive(r, inOpenChat = true)
        assertEquals(LiveEffect.REACTION_FOLDED, open.effect)
        assertEquals("+1", open.window.reactions["a"]?.get(chat)?.emoji)
        assertEquals(1, open.window.messages.size)

        val elsewhere = w.applyLive(r, inOpenChat = false)
        assertEquals(LiveEffect.REACTION_FOLDED, elsewhere.effect)
        assertTrue(elsewhere.window.reactions.isEmpty())
    }

    @Test
    fun `a live revoke or edit updates the row in place`() {
        val w = windowOf(msg("a", 10.0), msg("b", 20.0, text = "before"))

        val revoked = w.applyLive(msg("a", 10.0).copy(revoked = true), inOpenChat = true)
        assertEquals(LiveEffect.ROW_UPDATED, revoked.effect)
        assertTrue(revoked.window.messages.first { it.id == "a" }.revoked)
        assertEquals(2, revoked.window.messages.size)

        val edited = w.applyLive(msg("b", 20.0, text = "after").copy(edited = true), inOpenChat = true)
        assertEquals(LiveEffect.ROW_UPDATED, edited.effect)
        assertEquals("after", edited.window.messages.first { it.id == "b" }.text)
    }

    // -- optimistic changes -------------------------------------------------------

    @Test
    fun `delete for everyone revokes the row and drops its reactions, for me hides it`() {
        val w = windowOf(msg("a", 10.0)).withReaction("a", "me", "+1", 11.0)

        val everyone = w.withRemoved("a", everyone = true)
        assertTrue(everyone.messages.single().revoked)
        assertTrue(everyone.reactions.isEmpty())

        val forMe = w.withRemoved("a", everyone = false)
        assertTrue(forMe.messages.single().deletedForMe)
        assertFalse(forMe.reactions.isEmpty())
    }

    @Test
    fun `an optimistic edit changes the text and flags it edited`() {
        val next = windowOf(msg("a", 10.0), msg("b", 20.0)).withEdited("a", "new")

        assertEquals("new", next.messages.first().text)
        assertTrue(next.messages.first().edited)
        assertFalse(next.messages.last().edited)
    }
}
