package com.raavivi.sysmon

import coil.ImageLoader
import com.raavivi.sysmon.core.model.WaCardDetail
import com.raavivi.sysmon.core.model.WaChatSearchResponse
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import com.raavivi.sysmon.core.model.WaMessage
import com.raavivi.sysmon.core.model.WaPos
import com.raavivi.sysmon.core.model.WaYourCard
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.ui.whatsapp.VoiceNotePlayer
import com.raavivi.sysmon.ui.whatsapp.VoiceRecorder
import com.raavivi.sysmon.ui.whatsapp.WhatsAppViewModel
import com.raavivi.sysmon.ui.whatsapp.toRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/** "Your own cards" through the view model: when Select is offered, leaving on a chat change, Show in chat, the results group. Made-up data only. */
@OptIn(ExperimentalCoroutinesApi::class)
class WhatsAppViewModelManualTest {

    private val alice = "27820000001@s.whatsapp.net"
    private val bob = "27820000002@s.whatsapp.net"
    private val repo = FakeWhatsAppRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun msg(id: String, ts: Double, chat: String = alice, revoked: Boolean = false) = WaMessage(
        id = id, chat = chat, chatName = "", sender = chat, senderName = "", fromMe = false,
        ts = ts, text = "hello $id", mediaType = null, filename = null, downloaded = false, revoked = revoked,
    )

    private fun newViewModel(): WhatsAppViewModel {
        val cache = File(System.getProperty("java.io.tmpdir"), "wa-vm-manual-test")
        val loader = Proxy.newProxyInstance(ImageLoader::class.java.classLoader, arrayOf(ImageLoader::class.java)) { _, _, _ ->
            error("image loader is not used by these tests")
        } as ImageLoader
        return WhatsAppViewModel(
            repo = repo,
            uploads = FakeUploadSource(),
            mediaTools = FakeMediaTools(),
            imageLoader = loader,
            voiceFactory = { scope, onMessage -> VoiceNotePlayer(cache, OkHttpClient(), scope, onMessage) },
            recorderFactory = { scope, onMessage -> VoiceRecorder(cache, { error("recording is not used") }, scope, onMessage) },
            nowSeconds = { 2_000.0 },
        )
    }

    private val live = ApiResult.Ok(WaChatSearchStatus(enabled = true, auto = true, nodeVectors = 50))

    @Test
    fun `Select is not offered until chat search is known to be live`() {
        repo.chatSearchStatusResult = ApiResult.Ok(WaChatSearchStatus(enabled = false))
        val vm = newViewModel()
        vm.openChat(alice, "Test Chat")
        assertFalse(vm.canSelect)
        vm.selectMessage(msg("m1", 100.0))
        assertFalse(vm.manual.selection.active)

        repo.chatSearchStatusResult = live
        vm.openChat(alice, "Test Chat")
        assertTrue(vm.canSelect)
        vm.selectMessage(msg("m1", 100.0))
        assertTrue(vm.manual.selection.active)
    }

    @Test
    fun `a deleted or id-less message is never selectable even while live`() {
        repo.chatSearchStatusResult = live
        val vm = newViewModel()
        vm.openChat(alice, "Test Chat")
        vm.selectMessage(msg("m1", 100.0, revoked = true))
        vm.selectMessage(msg("", 100.0))
        assertFalse(vm.manual.selection.active)
    }

    @Test
    fun `a long-press range runs over the loaded messages and skips the ones that cannot be picked`() {
        repo.chatSearchStatusResult = live
        repo.messagesResponder = {
            ApiResult.Ok(listOf(msg("m1", 10.0), msg("m2", 20.0, revoked = true), msg("m3", 30.0), msg("m4", 40.0)))
        }
        val vm = newViewModel()
        vm.openChat(alice, "Test Chat")
        vm.selectMessage(msg("m1", 10.0))

        vm.selectRange(msg("m4", 40.0))

        assertEquals(listOf("m1", "m3", "m4"), vm.manual.selection.state.ids())
    }

    @Test
    fun `opening another chat leaves selection and clears the tint`() {
        repo.chatSearchStatusResult = live
        val vm = newViewModel()
        vm.openChat(alice, "Test Chat")
        vm.selectMessage(msg("m1", 100.0))
        vm.manual.setPicked(listOf("m1"))

        vm.openChat(bob, "Other Chat")

        assertFalse(vm.manual.selection.active)
        assertTrue(vm.manual.picked.isEmpty())
    }

    @Test
    fun `closing the chat also leaves selection`() {
        repo.chatSearchStatusResult = live
        val vm = newViewModel()
        vm.openChat(alice, "Test Chat")
        vm.selectMessage(msg("m1", 100.0))
        vm.closeChat()
        assertFalse(vm.manual.selection.active)
    }

    @Test
    fun `Show in chat opens at the first picked message and tints exactly the picked ids`() {
        val vm = newViewModel()
        val card = WaYourCard(
            cardId = 4, chat = alice, chatName = "Test Chat", title = "T", start = WaPos(500, 1), end = WaPos(900, 3),
            nMsgs = 2, msgIds = listOf("m7", "m9"),
        )

        vm.showYourCard(card.toRef())

        assertEquals(alice, vm.currentJid)
        assertEquals(setOf("m7", "m9"), vm.manual.picked)
        val call = repo.messageCalls.last()
        assertEquals(alice, call.chat)
        assertEquals(true, call.asc)

        vm.openChat(bob, "Other Chat")
        assertTrue(vm.manual.picked.isEmpty())
    }

    @Test
    fun `Show in chat from a card opened by id takes the ids from the loaded card`() {
        repo.cardResult = ApiResult.Ok(WaCardDetail(id = 8, origin = "manual", msgIds = listOf("a", "b")))
        val vm = newViewModel()
        vm.manual.openCard(com.raavivi.sysmon.ui.whatsapp.YourCardRef(cardId = 8, chat = alice, firstTs = 50))

        vm.showYourCard(vm.manual.cardView!!.ref)

        assertEquals(setOf("a", "b"), vm.manual.picked)
        assertEquals(null, vm.manual.cardView)
    }

    @Test
    fun `your cards come with the conversation results and leave them when deleted`() {
        val y1 = WaYourCard(cardId = 1, chat = alice, title = "One")
        val y2 = WaYourCard(cardId = 2, chat = alice, title = "Two")
        repo.chatSearchStatusResult = live
        repo.chatSearchResult = ApiResult.Ok(WaChatSearchResponse(yourCards = listOf(y1, y2)))
        val vm = newViewModel()
        vm.search.conv.run("pottery")
        assertEquals(listOf(1L, 2L), vm.search.conv.yourCards.map { it.cardId })
        assertTrue(vm.search.conv.results.isEmpty())

        vm.manual.openCard(y1.toRef())
        vm.manual.confirmDelete()

        assertEquals(listOf(2L), vm.search.conv.yourCards.map { it.cardId })
        assertEquals(listOf(1L), repo.cardDeletes)

        vm.search.conv.clear()
        assertTrue(vm.search.conv.yourCards.isEmpty())
    }
}
