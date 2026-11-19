package com.raavivi.sysmon

import coil.ImageLoader
import com.raavivi.sysmon.core.data.whatsapp.UploadFile
import com.raavivi.sysmon.core.model.WaAvatarsRefreshResponse
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaStatus
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.ui.whatsapp.VoiceNotePlayer
import com.raavivi.sysmon.ui.whatsapp.VoiceRecorder
import com.raavivi.sysmon.ui.whatsapp.WA_PAGE_LIMIT
import com.raavivi.sysmon.ui.whatsapp.WhatsAppViewModel
import com.raavivi.sysmon.ui.whatsapp.waBeforeCursor
import com.raavivi.sysmon.ui.whatsapp.waInclusiveAfter
import com.raavivi.sysmon.ui.whatsapp.waInclusiveBefore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class WhatsAppViewModelTest {

    private val alice = "27820000001@s.whatsapp.net"
    private val bob = "27820000002@s.whatsapp.net"
    private val group = "27820000003-1600000000@g.us"

    private val repo = FakeWhatsAppRepository()
    private val media = FakeMediaTools()
    private var now = 2_000.0

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    // -- helpers ------------------------------------------------------------------

    private fun chat(jid: String, lastTs: Double = 100.0, unread: Int = 0, name: String = jid.substringBefore('@')) =
        WaChat(jid = jid, name = name, lastTs = lastTs, unread = unread, pinned = false)

    private fun msg(
        id: String,
        ts: Double,
        chat: String = alice,
        fromMe: Boolean = false,
        text: String = "hello $id",
        sender: String = chat,
    ) = WaMessage(
        id = id, chat = chat, chatName = "", sender = sender, senderName = "", fromMe = fromMe,
        ts = ts, text = text, mediaType = null, filename = null, downloaded = false,
    )

    private fun fullPage(prefix: String, firstTs: Double): List<WaMessage> =
        (0 until WA_PAGE_LIMIT).map { msg("$prefix$it", firstTs + it) }

    private fun noImageLoader(): ImageLoader =
        Proxy.newProxyInstance(ImageLoader::class.java.classLoader, arrayOf(ImageLoader::class.java)) { _, _, _ ->
            error("image loader is not used by these tests")
        } as ImageLoader

    private fun newViewModel(uploads: FakeUploadSource = FakeUploadSource()): WhatsAppViewModel {
        val cache = File(System.getProperty("java.io.tmpdir"), "wa-vm-test")
        return WhatsAppViewModel(
            repo = repo,
            uploads = uploads,
            mediaTools = media,
            imageLoader = noImageLoader(),
            voiceFactory = { scope, onMessage -> VoiceNotePlayer(cache, OkHttpClient(), scope, onMessage) },
            recorderFactory = { scope, onMessage ->
                VoiceRecorder(cache, { error("recording is not used by these tests") }, scope, onMessage)
            },
            nowSeconds = { now },
        )
    }

    /** A view model with [chats] listed and [page] as the newest page of whichever chat gets opened. */
    private fun openedViewModel(
        jid: String = alice,
        page: List<WaMessage> = listOf(msg("a1", 1000.0), msg("a2", 1001.0)),
        chats: List<WaChat> = listOf(chat(alice), chat(bob)),
    ): WhatsAppViewModel {
        repo.chatsResult = ApiResult.Ok(chats)
        repo.messagesResponder = { ApiResult.Ok(page) }
        val vm = newViewModel()
        vm.openChat(jid, "Name")
        return vm
    }

    private fun isNewestRequest(c: MessagesCall) = c.before == null && c.after == null && c.asc == null

    // -- loading ------------------------------------------------------------------

    @Test
    fun `init loads status and sorts chats newest first`() {
        repo.statusResult = ApiResult.Ok(WaStatus(enabled = true, unreadTotal = 4))
        repo.chatsResult = ApiResult.Ok(listOf(chat(alice, lastTs = 10.0), chat(bob, lastTs = 20.0)))

        val vm = newViewModel()

        assertEquals(4, vm.status?.unreadTotal)
        assertEquals(listOf(bob, alice), vm.chats.map { it.jid })
        assertFalse(vm.loadingChats)
        assertNull(vm.error)
    }

    @Test
    fun `init offers each chat to the avatar refresh once and bumps the refreshed versions`() {
        repo.chatsResult = ApiResult.Ok(listOf(chat(alice), chat(bob)))
        repo.avatarsResult = ApiResult.Ok(WaAvatarsRefreshResponse(updated = listOf(alice), attempted = 2, limit = 40))

        val vm = newViewModel()
        vm.loadChats()

        assertEquals(listOf(listOf(alice, bob)), repo.avatarCalls)
        assertEquals(mapOf(alice to 1), vm.avatarVersions)
    }

    @Test
    fun `a failed chat list surfaces into error`() {
        repo.chatsResult = ApiResult.Err("Network error: down")

        val vm = newViewModel()

        assertEquals("Network error: down", vm.error)
        assertTrue(vm.chats.isEmpty())
    }

    @Test
    fun `opening a chat loads its newest page sorted and asks for the timeline`() {
        val vm = openedViewModel(page = listOf(msg("a2", 1001.0), msg("a1", 1000.0)))

        assertEquals(alice, vm.currentJid)
        assertEquals(listOf("a1", "a2"), vm.messages.map { it.id })
        assertEquals(listOf(MessagesCall(alice, WA_PAGE_LIMIT, null, null, null)), repo.messageCalls)
        assertEquals(listOf(alice), repo.timelineCalls)
        assertEquals(1, vm.scrollTick)
        assertTrue(vm.atLatest)
        assertFalse(vm.loadingMessages)
        assertEquals(2, media.prefetched.single().size)
    }

    @Test
    fun `a failed newest page surfaces into error and leaves the list empty`() {
        repo.messagesResponder = { ApiResult.Err("HTTP 500") }
        val vm = newViewModel()

        vm.openChat(alice, "Alice")

        assertEquals("HTTP 500", vm.error)
        assertTrue(vm.messages.isEmpty())
        assertEquals(0, vm.scrollTick)
    }

    // -- paging -------------------------------------------------------------------

    @Test
    fun `loadOlder prepends the page before the oldest row using the inclusive cursor`() {
        val vm = openedViewModel(page = fullPage("n", 1000.0))
        repo.messagesResponder = { ApiResult.Ok(fullPage("o", 900.0)) }

        vm.loadOlder()

        assertEquals(waInclusiveBefore(1000.0), repo.messageCalls.last().before)
        assertEquals(2 * WA_PAGE_LIMIT, vm.messages.size)
        assertEquals("o0", vm.messages.first().id)
        assertNull(vm.message)
    }

    @Test
    fun `loadOlder retries with the strict cursor when the page adds nothing, then reports the end`() {
        val newest = fullPage("n", 1000.0)
        val vm = openedViewModel(page = newest)
        repo.messagesResponder = { ApiResult.Ok(newest) }

        vm.loadOlder()

        val cursors = repo.messageCalls.drop(1).map { it.before }
        assertEquals(listOf(waInclusiveBefore(1000.0), waBeforeCursor(1000.0)), cursors)
        assertEquals("No older messages", vm.message)
    }

    @Test
    fun `once the first message is reached loadOlder makes no further request`() {
        val vm = openedViewModel()
        repo.messagesResponder = { ApiResult.Ok(emptyList()) }
        vm.loadOlder()   // an empty page: the end is reached
        val requests = repo.messageCalls.size
        vm.message = null

        vm.loadOlder()

        assertEquals(requests, repo.messageCalls.size)
        assertEquals("No older messages", vm.message)
    }

    @Test
    fun `silent loadOlder at the first message stays quiet`() {
        val vm = openedViewModel()

        vm.loadOlder(silent = true)

        assertNull(vm.message)
    }

    @Test
    fun `jumpToFirst loads the oldest page ascending and loadNewer then walks forward and merges the newest`() {
        val oldest = fullPage("f", 100.0)
        val vm = openedViewModel(page = fullPage("n", 1000.0))
        repo.messagesResponder = { ApiResult.Ok(oldest) }

        vm.jumpToFirst()

        assertEquals(MessagesCall(alice, WA_PAGE_LIMIT, null, null, true), repo.messageCalls.last())
        assertFalse(vm.atLatest)
        assertEquals(1, vm.topTick)
        assertEquals("f0", vm.messages.first().id)

        val following = listOf(msg("g0", 150.0), msg("g1", 151.0))
        repo.messagesResponder = { c -> ApiResult.Ok(if (isNewestRequest(c)) fullPage("n", 1000.0) else following) }
        vm.loadNewer()

        val newer = repo.messageCalls.first { it.after != null && it.after != waInclusiveAfter(0.0) }
        assertEquals(waInclusiveAfter(149.0), newer.after)
        assertEquals(true, newer.asc)
        assertTrue("short page reaches the end, merging the newest page", vm.atLatest)
        assertTrue(vm.messages.any { it.id == "g1" })
        assertTrue(vm.messages.any { it.id == "n49" })
    }

    @Test
    fun `jumpToLatest reloads, has the server fetch media, then reloads again`() {
        val vm = openedViewModel()
        val before = repo.messageCalls.size

        vm.jumpToLatest()

        assertEquals(listOf(alice), repo.latestFetched)
        assertEquals(before + 2, repo.messageCalls.size)
        assertFalse(vm.jumping)
        assertEquals(3, vm.scrollTick)
    }

    @Test
    fun `a failed latest fetch is reported but the list still reloads`() {
        val vm = openedViewModel()
        repo.actionResult = ApiResult.Err("slow")

        vm.jumpToLatest()

        assertEquals("Latest failed: slow", vm.message)
        assertFalse(vm.jumping)
    }

    // -- sending ------------------------------------------------------------------

    @Test
    fun `sending a reply passes the target id, clears the chip and reloads the chat`() {
        val vm = openedViewModel()
        val target = vm.messages.first()
        vm.setReply(target)
        val loads = repo.messageCalls.size

        vm.send("on my way")

        assertEquals(listOf(SentText(alice, "on my way", target.id)), repo.sentTexts)
        assertNull(vm.replyTo)
        assertFalse(vm.sending)
        assertEquals(loads + 1, repo.messageCalls.size)
    }

    @Test
    fun `a failed send reports it and keeps the reply chip`() {
        val vm = openedViewModel()
        vm.setReply(vm.messages.first())
        repo.actionResult = ApiResult.Err("offline")

        vm.send("hi")

        assertEquals("Send failed: offline", vm.message)
        assertEquals("a1", vm.replyTo?.id)
        assertFalse(vm.sending)
    }

    @Test
    fun `blank text is not sent`() {
        val vm = openedViewModel()

        vm.send("   ")

        assertTrue(repo.sentTexts.isEmpty())
    }

    @Test
    fun `sendMedia uploads the described file and clears the upload state`() {
        val file = UploadFile("photo.jpg", 12L, "image/jpeg") { ByteArrayInputStream(ByteArray(12)) }
        repo.chatsResult = ApiResult.Ok(listOf(chat(alice)))
        val vm = newViewModel(FakeUploadSource(mapOf("content://pick/1" to file)))
        vm.openChat(alice, "Alice")

        vm.sendMedia("content://pick/1", "look")

        assertEquals(listOf(SentMedia(alice, "photo.jpg", "look")), repo.sentMedia)
        assertNull(vm.uploading)
        assertFalse(vm.sending)
        assertEquals("photo.jpg", vm.displayName("content://pick/1"))
    }

    @Test
    fun `sendMedia refuses a file over the size limit without calling the repository`() {
        val huge = UploadFile("movie.mp4", 101L * 1024 * 1024, "video/mp4") { null }
        val vm = newViewModel(FakeUploadSource(mapOf("content://pick/2" to huge)))
        vm.openChat(alice, "Alice")

        vm.sendMedia("content://pick/2", "")

        assertTrue(repo.sentMedia.isEmpty())
        assertEquals("Send failed: movie.mp4 is larger than 100 MiB", vm.message)
        assertFalse(vm.sending)
    }

    @Test
    fun `sendVoice uploads the file with the reply id and deletes it afterwards`() {
        val vm = openedViewModel()
        val audio = File.createTempFile("wa-vn-test", ".m4a")

        vm.sendVoice(audio, "a1")

        assertEquals(listOf(SentVoice(alice, "a1", audio)), repo.sentVoices)
        assertFalse(audio.exists())
        assertNull(vm.uploading)
    }

    // -- edit, delete, react, pin -------------------------------------------------

    private fun editable(id: String = "mine") =
        msg(id, 1500.0, fromMe = true, text = "original").copy(editUntil = 9_999)

    @Test
    fun `editing a message updates it optimistically and calls the repository`() {
        val vm = openedViewModel(page = listOf(editable()))

        vm.editMessage(vm.messages.single(), "fixed")

        assertEquals(listOf(Edited(alice, "mine", "fixed")), repo.edits)
        val edited = vm.messages.single()
        assertEquals("fixed", edited.text)
        assertTrue(edited.edited)
    }

    @Test
    fun `a failed edit rolls the text back and restores the edit chip`() {
        val vm = openedViewModel(page = listOf(editable()))
        val original = vm.messages.single()
        repo.actionResult = ApiResult.Err("too late")

        vm.editMessage(original, "fixed")

        assertEquals("original", vm.messages.single().text)
        assertEquals(original, vm.editingMsg)
        assertEquals("Edit failed: too late", vm.message)
    }

    @Test
    fun `send while an edit chip is up edits instead of sending`() {
        val vm = openedViewModel(page = listOf(editable()))
        vm.startEdit(vm.messages.single())

        vm.send("rewritten")

        assertTrue(repo.sentTexts.isEmpty())
        assertEquals("rewritten", repo.edits.single().message)
        assertNull(vm.editingMsg)
    }

    @Test
    fun `an expired message cannot start an edit`() {
        val vm = openedViewModel(page = listOf(editable().copy(editUntil = 1_000)))

        vm.startEdit(vm.messages.single())

        assertNull(vm.editingMsg)
    }

    @Test
    fun `delete for everyone marks the message revoked and calls the repository`() {
        val vm = openedViewModel()

        vm.removeMessage(vm.messages.first(), everyone = true)

        assertEquals(listOf(Deleted(alice, "a1", true)), repo.deletes)
        assertTrue(vm.messages.first { it.id == "a1" }.revoked)
    }

    @Test
    fun `delete for me marks the message hidden, and a failure restores it`() {
        val vm = openedViewModel()
        repo.actionResult = ApiResult.Err("nope")

        vm.removeMessage(vm.messages.first(), everyone = false)

        assertFalse(vm.messages.first { it.id == "a1" }.deletedForMe)
        assertEquals("Delete failed: nope", vm.message)
    }

    @Test
    fun `reacting then clearing the reaction toggles the chip`() {
        val vm = openedViewModel()
        val target = vm.messages.first()

        vm.react(target, "👍")
        assertEquals("👍", vm.reactions["a1"]?.get("me")?.emoji)

        now += 1
        vm.react(target, "")
        assertEquals("", vm.reactions["a1"]?.get("me")?.emoji)

        assertEquals(listOf("👍", ""), repo.reactions.map { it.emoji })
        assertNull(repo.reactions.first().sender)
    }

    @Test
    fun `a failed reaction rolls back`() {
        val vm = openedViewModel()
        repo.actionResult = ApiResult.Err("rejected")

        vm.react(vm.messages.first(), "😂")

        assertTrue(vm.reactions.isEmpty())
        assertEquals("Reaction failed: rejected", vm.message)
    }

    @Test
    fun `reacting in a group names the target's sender`() {
        val vm = openedViewModel(jid = group, page = listOf(msg("g1", 1000.0, chat = group, sender = alice)))

        vm.react(vm.messages.single(), "❤")

        assertEquals(alice, repo.reactions.single().sender)
    }

    @Test
    fun `local pin pins the chat in the list and floats it to the top`() {
        val vm = openedViewModel(chats = listOf(chat(alice, lastTs = 50.0), chat(bob, lastTs = 90.0)))
        assertEquals(bob, vm.chats.first().jid)

        vm.localPin(vm.chats.first { it.jid == alice }, pinned = true)

        assertEquals(listOf(PinCall(alice, true)), repo.localPins)
        val pinned = vm.chats.first()
        assertEquals(alice, pinned.jid)
        assertTrue(pinned.localPin)
        assertTrue(pinned.pinned)
    }

    @Test
    fun `renaming a chat shows the sys-mon name and keeps WhatsApp's`() {
        val vm = openedViewModel(chats = listOf(chat(alice, name = "Alice Fake"), chat(bob)))

        vm.renameChat(vm.chats.first { it.jid == alice }, " Old number ")

        assertEquals(listOf(alice to " Old number "), repo.chatNames)
        val row = vm.chats.first { it.jid == alice }
        assertEquals(Triple("Old number", true, "Alice Fake"), Triple(row.name, row.renamed, row.waName))
        assertEquals("Old number", vm.currentName)   // alice is the open chat
    }

    @Test
    fun `a failed rename keeps the name and says why`() {
        repo.actionResult = ApiResult.Err("nope")
        val vm = openedViewModel(chats = listOf(chat(alice, name = "Alice Fake")))

        vm.renameChat(vm.chats.first(), "Old number")

        assertEquals("Alice Fake", vm.chats.first().name)
        assertEquals("Rename failed: nope", vm.message)
    }

    @Test
    fun `whatsapp pin toggles through the repository and is skipped for local pins`() {
        val vm = openedViewModel()

        vm.togglePin()

        assertEquals(listOf(PinCall(alice, true)), repo.pins)
        assertTrue(vm.currentPinned)
    }

    @Test
    fun `mark read clears the chat unread and the status total`() {
        repo.statusResult = ApiResult.Ok(WaStatus(enabled = true, unreadTotal = 5))
        val vm = openedViewModel(chats = listOf(chat(alice, unread = 3)))

        vm.markRead()

        assertEquals(listOf(alice), repo.markedRead)
        assertEquals(0, vm.chats.single().unread)
        assertEquals(2, vm.status?.unreadTotal)
    }

    // -- live frames --------------------------------------------------------------

    @Test
    fun `a live message for the open chat is appended and marked fresh`() {
        val vm = openedViewModel(chats = listOf(chat(alice, lastTs = 1001.0)))

        repo.emitLive(msg("a3", 1002.0, text = "new one"))

        assertEquals(listOf("a1", "a2", "a3"), vm.messages.map { it.id })
        assertTrue("a3" in vm.freshIds)
        assertEquals(1, vm.liveTick)
        assertEquals(0, vm.chats.first { it.jid == alice }.unread)
        assertEquals("new one", vm.chats.first { it.jid == alice }.preview)
    }

    @Test
    fun `a live message already in the list is not added twice`() {
        val vm = openedViewModel()

        repo.emitLive(msg("a2", 1001.0))

        assertEquals(2, vm.messages.size)
        assertEquals(0, vm.liveTick)
    }

    @Test
    fun `a live message for another chat bumps its unread count and preview only`() {
        val vm = openedViewModel()

        repo.emitLive(msg("b1", 1500.0, chat = bob, text = "ping"))

        val bobRow = vm.chats.first { it.jid == bob }
        assertEquals(1, bobRow.unread)
        assertEquals("ping", bobRow.preview)
        assertEquals(1500.0, bobRow.lastTs, 0.0)
        assertEquals(listOf("a1", "a2"), vm.messages.map { it.id })
        assertEquals(bob, vm.chats.first().jid)
    }

    @Test
    fun `my own live message elsewhere does not bump unread`() {
        val vm = openedViewModel()

        repo.emitLive(msg("b1", 1500.0, chat = bob, fromMe = true))

        assertEquals(0, vm.chats.first { it.jid == bob }.unread)
    }

    @Test
    fun `a live message for an unknown chat reloads the chat list`() {
        val vm = openedViewModel()
        val loads = repo.chatsCalls.size

        repo.emitLive(msg("z1", 1500.0, chat = "27820000009@s.whatsapp.net"))

        assertEquals(loads + 1, repo.chatsCalls.size)
        assertEquals(2, vm.chats.size)
    }

    @Test
    fun `a live reaction updates the chip without adding a row or an unread`() {
        val vm = openedViewModel()
        val reaction = msg("r1", 1003.0, sender = alice).copy(reactionToId = "a1", reactionEmoji = "👍")

        repo.emitLive(reaction)

        assertEquals("👍", vm.reactions["a1"]?.get(alice)?.emoji)
        assertEquals(2, vm.messages.size)
        assertEquals(0, vm.chats.first { it.jid == alice }.unread)
    }

    @Test
    fun `a live revoke updates the message in place`() {
        val vm = openedViewModel()

        repo.emitLive(msg("a2", 1001.0).copy(revoked = true))

        assertEquals(2, vm.messages.size)
        assertTrue(vm.messages.first { it.id == "a2" }.revoked)
    }

    // -- misc ---------------------------------------------------------------------

    @Test
    fun `saving an image reports where it went, or why it failed`() {
        val vm = newViewModel()

        vm.saveImage(msg("a1", 1.0))
        assertEquals("Saved to Pictures/test.jpg", vm.message)

        media.saveResult = Result.failure(IllegalStateException("HTTP 404"))
        vm.saveImage(msg("a1", 1.0))
        assertEquals("Save failed: HTTP 404", vm.message)
    }

    @Test
    fun `media and avatar urls come from the repository`() {
        val vm = newViewModel()

        assertEquals("https://fake.test/media/$alice/a1", vm.mediaUrl(msg("a1", 1.0)))
        assertEquals("https://fake.test/avatar/$alice", vm.avatarUrl(alice))
    }

    @Test
    fun `closing the chat clears the window`() {
        val vm = openedViewModel()

        vm.closeChat()

        assertNull(vm.currentJid)
        assertTrue(vm.messages.isEmpty())
    }
}
