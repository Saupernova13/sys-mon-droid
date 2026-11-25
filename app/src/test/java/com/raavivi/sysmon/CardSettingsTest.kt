package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.whatsapp.cardSettingsError
import com.raavivi.sysmon.core.model.WaCardChat
import com.raavivi.sysmon.core.model.WaCardSettings
import com.raavivi.sysmon.core.model.WaCardSettingsPatch
import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.ui.whatsapp.CARD_MAX_CHATS
import com.raavivi.sysmon.ui.whatsapp.CardSettingsPhase
import com.raavivi.sysmon.ui.whatsapp.CardSettingsState
import com.raavivi.sysmon.ui.whatsapp.addChat
import com.raavivi.sysmon.ui.whatsapp.addEstimate
import com.raavivi.sysmon.ui.whatsapp.canSave
import com.raavivi.sysmon.ui.whatsapp.chatProgress
import com.raavivi.sysmon.ui.whatsapp.clockText
import com.raavivi.sysmon.ui.whatsapp.draftOf
import com.raavivi.sysmon.ui.whatsapp.draftProblem
import com.raavivi.sysmon.ui.whatsapp.isEmpty
import com.raavivi.sysmon.ui.whatsapp.nightHint
import com.raavivi.sysmon.ui.whatsapp.parseWindow
import com.raavivi.sysmon.ui.whatsapp.patchOf
import com.raavivi.sysmon.ui.whatsapp.pickerChats
import com.raavivi.sysmon.ui.whatsapp.removeChat
import com.raavivi.sysmon.ui.whatsapp.stepMinMsgs
import com.raavivi.sysmon.ui.whatsapp.writingHours
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
    private var savedCount = 0
    private val alice = WaCardChat("27820000001@s.whatsapp.net", "Chat One", topics = 14, carded = 9)
    private val bob = "27820000002@s.whatsapp.net"
    private val base = WaCardSettings(window = "03:00-06:00", minMsgs = 10, chats = listOf(alice))
    private lateinit var st: CardSettingsState

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo.cardSettingsResult = ApiResult.Ok(base)
        st = CardSettingsState(repo, CoroutineScope(dispatcher), { savedCount++ }, { toasts += it })
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
    fun `the hint names the next start`() {
        val d = draftOf(base)
        assertEquals("Indexes new messages first, then writes cards. Next start 03:00.", nightHint(d))
        assertEquals("Indexes new messages first, then writes cards. Next start 22:15.", nightHint(d.copy(from = 22 * 60 + 15)))
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

    @Test
    fun `the picker filters by name in any case`() {
        val chats = listOf(
            WaChat("a", "Chat One", 0.0, 0, false),
            WaChat("b", "Other", 0.0, 0, false),
        )
        assertEquals(chats, pickerChats(chats, "  "))
        assertEquals(listOf("a"), pickerChats(chats, "chat").map { it.jid })
        assertTrue(pickerChats(chats, "zzz").isEmpty())
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
        st.show()
        assertTrue(st.open)
        assertEquals(CardSettingsPhase.READY, st.phase)
        assertEquals(draftOf(base), st.draft)
        assertFalse(st.canSave)
    }

    @Test
    fun `a failed read shows an error and Retry reads again`() {
        repo.cardSettingsResult = ApiResult.Err("Network error: down")
        st.show()
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
        st.show()
        assertEquals(CardSettingsPhase.LOADING, st.phase)
        gate.complete(Unit)
        assertEquals(CardSettingsPhase.READY, st.phase)
    }

    @Test
    fun `saving sends only the change, closes, toasts and tells the status line`() {
        st.show()
        st.stepMin(1)
        assertTrue(st.canSave)
        st.save()
        assertEquals(listOf(WaCardSettingsPatch(minMsgs = 11)), repo.cardSettingsSaves)
        assertFalse(st.open)
        assertEquals(listOf("Card settings saved"), toasts)
        assertEquals(1, savedCount)
    }

    @Test
    fun `a refused save keeps the sheet open with the reason until the next edit`() {
        st.show()
        st.setNight(false)
        repo.saveCardSettingsResult = ApiResult.Err("at most 50 chats may be listed", 400)
        st.save()
        assertTrue(st.open)
        assertFalse(st.saving)
        assertEquals("at most 50 chats may be listed", st.problem)
        assertTrue(toasts.isEmpty())
        st.setNight(true)
        assertNull(st.problem)
    }

    @Test
    fun `save waits while saving and is single flight`() {
        st.show()
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
        st.show()
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
        st.show()
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
        st.show()
        st.openPicker()
        st.pick(alice.jid, "Chat One")
        assertNull(st.probe)
        assertEquals(listOf<String?>(null), repo.cardSettingsReads)
    }

    @Test
    fun `cancel drops the draft`() {
        st.show()
        st.stepMin(5)
        st.close()
        assertFalse(st.open)
        st.show()
        assertEquals(10, st.draft!!.minMsgs)
    }
}
