package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.whatsapp.cardSettingsError
import com.raavivi.sysmon.core.model.WaCardChat
import com.raavivi.sysmon.core.model.WaCardSettings
import com.raavivi.sysmon.core.model.WaCardSettingsPatch
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.ui.settings.CARD_MAX_CHATS
import com.raavivi.sysmon.ui.settings.CardSettingsPhase
import com.raavivi.sysmon.ui.settings.PickerChatsPhase
import com.raavivi.sysmon.ui.settings.isSearchOff
import com.raavivi.sysmon.ui.settings.CardSettingsState
import com.raavivi.sysmon.ui.settings.addChat
import com.raavivi.sysmon.ui.settings.addEstimate
import com.raavivi.sysmon.ui.settings.canSave
import com.raavivi.sysmon.ui.settings.chatProgress
import com.raavivi.sysmon.ui.settings.clockText
import com.raavivi.sysmon.ui.settings.draftOf
import com.raavivi.sysmon.ui.settings.draftProblem
import com.raavivi.sysmon.ui.settings.isEmpty
import com.raavivi.sysmon.ui.settings.nightHint
import com.raavivi.sysmon.ui.settings.parseWindow
import com.raavivi.sysmon.ui.settings.patchOf
import com.raavivi.sysmon.ui.settings.pickerChats
import com.raavivi.sysmon.ui.settings.removeChat
import com.raavivi.sysmon.ui.settings.stepMinMsgs
import com.raavivi.sysmon.ui.settings.writingHours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Card settings sheet's logic and state, over the fake repository. Made-up chats only. */
@OptIn(ExperimentalCoroutinesApi::class)
class CardSettingsTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeWhatsAppRepository()
    private val toasts = mutableListOf<String>()
        private val alice = WaCardChat("27820000001@s.whatsapp.net", "Chat One", topics = 14, carded = 9)
    private val bob = "27820000002@s.whatsapp.net"
    private val base = WaCardSettings(window = "03:00-06:00", minMsgs = 10, chats = listOf(alice))
    private lateinit var st: CardSettingsState

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo.cardSettingsResult = ApiResult.Ok(base)
        st = CardSettingsState(repo, CoroutineScope(dispatcher)) { toasts += it }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // -- logic ----------------------------------------------------------------------------------

    @Test
    fun `a window parses and prints as clock times`() {
        assertEquals(3 * 60 to 6 * 60, parseWindow("03:00-06:00"))
        assertEquals(22 * 60 + 30 to 5 * 60, parseWindow("22:30-05:00"))
        assertNull(parseWindow(""))
        assertNull(parseWindow("25:00-06:00"))
        assertNull(parseWindow("3-6"))
        assertEquals("07:05", clockText(7 * 60 + 5))
    }

    @Test
    fun `no saved window gives the default times with the switch off`() {
        val d = draftOf(base.copy(window = ""))
        assertFalse(d.night)
        assertEquals("", d.window)
        assertEquals("03:00-06:00", d.copy(night = true).window)
    }

    @Test
    fun `an untouched draft makes an empty patch and cannot be saved`() {
        val d = draftOf(base)
        assertTrue(patchOf(base, d).isEmpty())
        assertFalse(canSave(base, d))
    }

    @Test
    fun `the patch holds only the changed fields`() {
        val d = draftOf(base)
        assertEquals(WaCardSettingsPatch(window = ""), patchOf(base, d.copy(night = false)))
        assertEquals(WaCardSettingsPatch(window = "22:00-06:00"), patchOf(base, d.copy(from = 22 * 60)))
        assertEquals(WaCardSettingsPatch(minMsgs = 11), patchOf(base, d.copy(minMsgs = 11)))
        assertEquals(WaCardSettingsPatch(chats = emptyList()), patchOf(base, removeChat(d, alice.jid)))
        assertEquals(
            WaCardSettingsPatch(chats = listOf(alice.jid, bob)),
            patchOf(base, addChat(d, WaCardChat(bob))),
        )
    }

    @Test
    fun `switching the window off and on again is no change`() {
        val d = draftOf(base)
        assertTrue(patchOf(base, d.copy(night = false).copy(night = true)).isEmpty())
    }

    @Test
    fun `the patch json leaves out unchanged fields`() {
        val json = SysMonJson.encodeToString(WaCardSettingsPatch.serializer(), WaCardSettingsPatch(minMsgs = 12))
        assertEquals("""{"min_msgs":12}""", json)
        val clear = SysMonJson.encodeToString(WaCardSettingsPatch.serializer(), WaCardSettingsPatch(window = "", chats = emptyList()))
        assertEquals("""{"window":"","chats":[]}""", clear)
    }

    @Test
    fun `the settings document decodes with its probe`() {
        val raw = """{"window":"","min_msgs":10,"chats":[{"jid":"a@s.whatsapp.net","name":"Chat One","topics":3,"carded":1}],
            |"source":{"window":"settings","chats":"env","min_msgs":"env"},"probe":{"jid":"b@s.whatsapp.net","name":"","topics":7,"carded":0}}""".trimMargin()
        val s = SysMonJson.decodeFromString(WaCardSettings.serializer(), raw)
        assertEquals("", s.window)
        assertEquals(1, s.chats.single().carded)
        assertEquals("env", s.source?.minMsgs)
        assertEquals(7, s.probe?.topics)
    }

    @Test
    fun `validation refuses equal times and too many chats`() {
        val d = draftOf(base)
        assertNull(draftProblem(d))
        assertEquals("From and To must be different times", draftProblem(d.copy(to = d.from)))
        assertNull(draftProblem(d.copy(night = false, to = d.from)))
        val many = d.copy(chats = List(CARD_MAX_CHATS + 1) { WaCardChat("j$it") })
        assertTrue(draftProblem(many)!!.contains("50"))
        assertFalse(canSave(base, many))
    }

    @Test
    fun `the stepper clamps to 2 and 100000`() {
        assertEquals(11, stepMinMsgs(10, 1))
        assertEquals(2, stepMinMsgs(2, -1))
        assertEquals(100_000, stepMinMsgs(100_000, 1))
    }

    @Test
    fun `adding a listed chat again changes nothing`() {
        val d = draftOf(base)
        assertEquals(d, addChat(d, alice))
    }

    @Test
    fun `the hint names the window`() {
        val d = draftOf(base)
        assertEquals("Writes each night from 03:00 to 06:00", nightHint(d))
        assertEquals("Writes each night from 22:15 to 06:00", nightHint(d.copy(from = 22 * 60 + 15)))
        assertEquals("Cards are only written when you tap Run.", nightHint(d.copy(night = false)))
    }

    @Test
    fun `the estimate counts topics left and rounds the hours up to the half hour`() {
        assertEquals("9 / 14 topics carded", chatProgress(alice))
        assertEquals("1 / 1 topic carded", chatProgress(WaCardChat("x", topics = 1, carded = 1)))
        assertEquals(0.5, writingHours(1), 0.0)
        assertEquals(0.5, writingHours(112), 0.0) // 112 * 16 s = 0.498 h
        assertEquals(1.0, writingHours(113), 0.0)
        assertEquals("Chat One: ~5 topics to card, about 0.5 h of overnight writing", addEstimate("Chat One", alice))
        assertEquals("Big: ~900 topics to card, about 4 h of overnight writing", addEstimate("Big", WaCardChat("b", topics = 900)))
        assertEquals("Chat One: ~1 topic to card, about 0.5 h of overnight writing", addEstimate("Chat One", alice.copy(carded = 13)))
        assertEquals("Done: nothing to card yet", addEstimate("Done", alice.copy(carded = 14)))
        assertEquals("None: nothing to card yet", addEstimate("None", WaCardChat("n")))
    }

    private val pickable = listOf(
        WaChat("27821234567@s.whatsapp.net", "Chat One", 0.0, 0, false),
        WaChat("27829999999@s.whatsapp.net", "Other Person", 0.0, 0, false),
        WaChat("120363000000000001@g.us", "Group Name", 0.0, 0, false),
    )

    @Test
    fun `the picker filters by every word of the name in any case`() {
        assertEquals(listOf("Other Person"), pickerChats(pickable, "per OTHER").map { it.name })
        assertEquals(listOf("Chat One"), pickerChats(pickable, "chat").map { it.name })
        assertTrue(pickerChats(pickable, "zzz").isEmpty())
        assertTrue(pickerChats(pickable, "chat zzz").isEmpty())
    }

    @Test
    fun `a blank query lists every unlisted chat`() {
        assertEquals(pickable, pickerChats(pickable, "  "))
        val listed = setOf(pickable[0].jid)
        assertEquals(pickable.drop(1), pickerChats(pickable, "", listed))
    }

    @Test
    fun `already listed chats are left out even when they match`() {
        assertTrue(pickerChats(pickable, "chat", setOf(pickable[0].jid)).isEmpty())
        assertTrue(pickerChats(pickable, "27821234567", setOf(pickable[0].jid)).isEmpty())
    }

    @Test
    fun `a phone number finds the chat with or without the country code and zeros`() {
        val want = listOf("Chat One")
        assertEquals(want, pickerChats(pickable, "+27 82 123 4567").map { it.name })
        assertEquals(want, pickerChats(pickable, "082 123 4567").map { it.name })
        assertEquals(want, pickerChats(pickable, "27821234567").map { it.name })
        assertEquals(want, pickerChats(pickable, "123 4567").map { it.name })
    }

    @Test
    fun `fewer than four digits does not search the numbers`() {
        assertTrue(pickerChats(pickable, "123").isEmpty())
        assertTrue(pickerChats(pickable, "000").isEmpty())
    }

    @Test
    fun `only a 404 means chat search is off`() {
        assertTrue(isSearchOff(404))
        assertFalse(isSearchOff(500))
        assertFalse(isSearchOff(400))
        assertFalse(isSearchOff(null))
    }

    @Test
    fun `a failed call reads its words from a plain body or an envelope`() {
        assertEquals("window must be HH:MM-HH:MM (or empty for none)", cardSettingsError(400, "window must be HH:MM-HH:MM (or empty for none)\n"))
        assertEquals("nope", cardSettingsError(400, """{"ok":false,"error":"nope"}"""))
        assertEquals("Chat search is off", cardSettingsError(404, ""))
        assertEquals("HTTP 500", cardSettingsError(500, "<html>boom</html>"))
        assertEquals("HTTP 400", cardSettingsError(400, null))
    }

    // -- state ----------------------------------------------------------------------------------

    @Test
    fun `opening reads the settings into a draft`() {
        st.load()
        assertEquals(CardSettingsPhase.READY, st.phase)
        assertEquals(draftOf(base), st.draft)
        assertFalse(st.canSave)
    }

    @Test
    fun `a failed read shows an error and Retry reads again`() {
        repo.cardSettingsResult = ApiResult.Err("Network error: down")
        st.load()
        assertEquals(CardSettingsPhase.ERROR, st.phase)
        assertEquals("Network error: down", st.loadError)
        repo.cardSettingsResult = ApiResult.Ok(base)
        st.retry()
        assertEquals(CardSettingsPhase.READY, st.phase)
    }

    @Test
    fun `loading shows until the read answers`() {
        val gate = CompletableDeferred<Unit>()
        repo.cardSettingsGate = gate
        st.load()
        assertEquals(CardSettingsPhase.LOADING, st.phase)
        gate.complete(Unit)
        assertEquals(CardSettingsPhase.READY, st.phase)
    }

    @Test
    fun `saving sends only the change, toasts and re-bases the draft`() {
        st.load()
        st.stepMin(1)
        assertTrue(st.canSave)
        st.save()
        assertEquals(listOf(WaCardSettingsPatch(minMsgs = 11)), repo.cardSettingsSaves)
        assertEquals(listOf("Card settings saved"), toasts)
        assertFalse(st.dirty)
        assertFalse(st.canSave)
        assertEquals(11, st.saved!!.minMsgs)
    }

    @Test
    fun `a refused save keeps the draft with the reason until the next edit`() {
        st.load()
        st.setNight(false)
        repo.saveCardSettingsResult = ApiResult.Err("at most 50 chats may be listed", 400)
        st.save()
        assertTrue(st.dirty)
        assertFalse(st.saving)
        assertEquals("at most 50 chats may be listed", st.problem)
        assertTrue(toasts.isEmpty())
        st.setNight(true)
        assertNull(st.problem)
    }

    @Test
    fun `save waits while saving and is single flight`() {
        st.load()
        st.stepMin(1)
        val gate = CompletableDeferred<Unit>()
        repo.cardSettingsGate = gate
        st.save()
        assertTrue(st.saving)
        assertFalse(st.canSave)
        st.save()
        gate.complete(Unit)
        assertEquals(1, repo.cardSettingsSaves.size)
    }

    @Test
    fun `picking a chat probes it, then Add puts it in the draft`() {
        repo.probeChats = mapOf(bob to WaCardChat(bob, "", topics = 40, carded = 0))
        st.load()
        st.openPicker()
        st.pick(bob, "Chat Two")
        assertEquals(listOf<String?>(null, bob), repo.cardSettingsReads)
        assertEquals(40, st.probe?.chat?.topics)
        st.confirmAdd()
        assertFalse(st.picking)
        assertEquals(listOf(alice.jid, bob), st.draft!!.chats.map { it.jid })
        assertEquals("Chat Two", st.draft!!.chats.last().name)
        assertTrue(st.canSave)
    }

    @Test
    fun `Back from the confirm card leaves the draft alone and a failed probe cannot be added`() {
        st.load()
        st.openPicker()
        repo.probeFails = true
        st.pick(bob, "Chat Two")
        assertTrue(st.probe!!.failed)
        st.confirmAdd()
        assertTrue(st.picking)
        st.clearProbe()
        assertNull(st.probe)
        assertEquals(draftOf(base), st.draft)
    }

    @Test
    fun `a listed chat cannot be picked again`() {
        st.load()
        st.openPicker()
        st.pick(alice.jid, "Chat One")
        assertNull(st.probe)
        assertEquals(listOf<String?>(null), repo.cardSettingsReads)
    }

    @Test
    fun `a 404 on the read shows the off state, not an error`() {
        repo.cardSettingsResult = ApiResult.Err("Chat search is off", 404)
        st.load()
        assertEquals(CardSettingsPhase.OFF, st.phase)
        assertNull(st.draft)
        repo.cardSettingsResult = ApiResult.Err("HTTP 500", 500)
        st.retry()
        assertEquals(CardSettingsPhase.ERROR, st.phase)
    }

    @Test
    fun `dirty follows the edits and clears when they are undone`() {
        st.load()
        assertFalse(st.dirty)
        st.stepMin(1)
        assertTrue(st.dirty)
        st.stepMin(-1)
        assertFalse(st.dirty)
    }

    @Test
    fun `the picker reads the chat list once, on first use`() {
        repo.chatsResult = ApiResult.Ok(pickable)
        st.load()
        assertEquals(PickerChatsPhase.IDLE, st.chatsPhase)
        st.openPicker()
        assertEquals(PickerChatsPhase.READY, st.chatsPhase)
        assertEquals(3, st.chats.size)
        st.closePicker()
        st.openPicker()
        assertEquals(1, repo.chatsCalls.size)
    }

    @Test
    fun `a failed chat list can be retried`() {
        repo.chatsResult = ApiResult.Err("Network error: down")
        st.load()
        st.openPicker()
        assertEquals(PickerChatsPhase.ERROR, st.chatsPhase)
        repo.chatsResult = ApiResult.Ok(pickable)
        st.loadChats()
        assertEquals(PickerChatsPhase.READY, st.chatsPhase)
    }
}
