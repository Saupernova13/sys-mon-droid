package com.raavivi.sysmon

import androidx.compose.ui.geometry.Offset
import com.raavivi.sysmon.core.model.WaCardAct
import com.raavivi.sysmon.core.model.WaCardDetail
import com.raavivi.sysmon.core.model.WaCardFacet
import com.raavivi.sysmon.core.model.WaCardLex
import com.raavivi.sysmon.core.model.WaCardMoment
import com.raavivi.sysmon.core.model.WaChatSearchResponse
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaSubCard
import com.raavivi.sysmon.core.model.WaTopicCard
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.ui.whatsapp.CardPhase
import com.raavivi.sysmon.ui.whatsapp.CardSection
import com.raavivi.sysmon.ui.whatsapp.WhatsAppSearch
import com.raavivi.sysmon.ui.whatsapp.actText
import com.raavivi.sysmon.ui.whatsapp.dismissesKeyboard
import com.raavivi.sysmon.ui.whatsapp.intensityDots
import com.raavivi.sysmon.ui.whatsapp.kindLabel
import com.raavivi.sysmon.ui.whatsapp.messagesLabel
import com.raavivi.sysmon.ui.whatsapp.momentOpenable
import com.raavivi.sysmon.ui.whatsapp.resultsHeader
import com.raavivi.sysmon.ui.whatsapp.sortedFacets
import com.raavivi.sysmon.ui.whatsapp.visibleSections
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The full-card sheet's wire shape, state and pure helpers. Made-up text only. */
@OptIn(ExperimentalCoroutinesApi::class)
class CardSheetTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeWhatsAppRepository()
    private val alice = "27820000001@s.whatsapp.net"
    private lateinit var search: WhatsAppSearch

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        search = WhatsAppSearch(repo, CoroutineScope(dispatcher), { alice }, { }, { })
        repo.chatSearchStatusResult = ApiResult.Ok(WaChatSearchStatus(enabled = true, auto = true, nodeVectors = 50))
        search.viewShown()
        repo.chatSearchResult = ApiResult.Ok(
            WaChatSearchResponse(results = listOf(WaConvResult(nodeId = 12, chat = alice, family = listOf(alice), nMsgs = 4))),
        )
        search.openFor("cinema night")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val detail = WaCardDetail(id = 12, card = WaTopicCard(title = "Plan the trip", summary = "They planned a trip."))

    // -- wire -----------------------------------------------------------------------------------

    @Test
    fun `a card detail decodes, with null moment refs and missing optional fields`() {
        val d = SysMonJson.decodeFromString(
            WaCardDetail.serializer(),
            """{"id": 12, "of_parent": true,
               "card": {"title": "T", "summary": "S", "said": ["a"], "meant": ["b"], "topics": ["x"],
                        "acts": [{"kind":"plan","intensity":2,"resolved":true}], "facets": [{"name":"plans","confidence":0.5}],
                        "entities": ["e"], "recall_queries": ["q1"], "details": [{"kinds":["plan"],"who":"both","text":"d"}],
                        "lexicon": [{"term":"t","meaning":"m"}], "future_field": 1},
               "subtopics": [{"title":"st","summary":"ss"}],
               "moments": [{"kind":"plan","one_line":"o","said":"s","chat_jid":"27820000001@s.whatsapp.net","msg_id":"MSG9","ts":1700000009},
                           {"kind":"idea","one_line":"o2","said":"s2","chat_jid":null,"msg_id":null,"ts":null}]}""",
        )
        assertEquals(12L, d.id)
        assertTrue(d.ofParent)
        assertEquals(listOf("q1"), d.card.recallQueries)
        assertEquals(WaCardAct("plan", 2, true), d.card.acts.single())
        assertEquals(WaCardFacet("plans", 0.5), d.card.facets.single())
        assertEquals(WaCardLex("t", "m"), d.card.lexicon.single())
        assertEquals(listOf("plan"), d.card.details.single().kinds)
        assertEquals("st", d.subtopics.single().title)
        assertTrue(d.subtopics.single().said.isEmpty())
        assertEquals("MSG9", d.moments[0].msgId)
        assertEquals(1_700_000_009L, d.moments[0].ts)
        assertNull(d.moments[1].chatJid)
        assertNull(d.moments[1].ts)
    }

    @Test
    fun `an empty card body decodes to defaults`() {
        val d = SysMonJson.decodeFromString(WaCardDetail.serializer(), "{}")
        assertTrue(visibleSections(d).isEmpty())
        assertFalse(d.ofParent)
    }

    // -- state ----------------------------------------------------------------------------------

    @Test
    fun `opening a card reads it by node id and shows it`() {
        repo.cardResult = ApiResult.Ok(detail)

        search.conv.openCard(0)

        assertEquals(listOf(12L), repo.cardRequests)
        val sheet = search.conv.cardSheet!!
        assertEquals(0, sheet.index)
        assertEquals(CardPhase.DONE, sheet.phase)
        assertEquals("Plan the trip", sheet.detail!!.card.title)
    }

    @Test
    fun `the sheet is loading while the card is in flight`() {
        val gate = CompletableDeferred<Unit>()
        repo.cardGate = gate
        repo.cardResult = ApiResult.Ok(detail)

        search.conv.openCard(0)
        assertEquals(CardPhase.LOADING, search.conv.cardSheet!!.phase)
        assertNull(search.conv.cardSheet!!.detail)

        gate.complete(Unit)
        assertEquals(CardPhase.DONE, search.conv.cardSheet!!.phase)
    }

    @Test
    fun `a 404 means the result has no card`() {
        repo.cardResult = ApiResult.Err("not found", 404)

        search.conv.openCard(0)

        assertEquals(CardPhase.MISSING, search.conv.cardSheet!!.phase)
    }

    @Test
    fun `another failure is an error and retry recovers`() {
        repo.cardResult = ApiResult.Err("down", 502)
        search.conv.openCard(0)
        assertEquals(CardPhase.ERROR, search.conv.cardSheet!!.phase)

        repo.cardResult = ApiResult.Ok(detail)
        search.conv.retryCard()

        assertEquals(CardPhase.DONE, search.conv.cardSheet!!.phase)
        assertEquals(2, repo.cardRequests.size)
    }

    @Test
    fun `an unknown result index opens nothing`() {
        search.conv.openCard(5)
        assertNull(search.conv.cardSheet)
        assertTrue(repo.cardRequests.isEmpty())
    }

    @Test
    fun `closing drops the sheet and a late reply`() {
        val gate = CompletableDeferred<Unit>()
        repo.cardGate = gate
        search.conv.openCard(0)

        search.conv.closeCard()
        gate.complete(Unit)

        assertNull(search.conv.cardSheet)
    }

    @Test
    fun `a new query drops a reply that lands after it`() {
        val gate = CompletableDeferred<Unit>()
        repo.cardGate = gate
        repo.cardResult = ApiResult.Ok(detail)
        search.conv.openCard(0)

        search.openFor("another query")
        gate.complete(Unit)

        assertNull(search.conv.cardSheet)
    }

    @Test
    fun `clearing the results closes the sheet`() {
        repo.cardResult = ApiResult.Ok(detail)
        search.conv.openCard(0)
        assertNotNull(search.conv.cardSheet)

        search.conv.clear()

        assertNull(search.conv.cardSheet)
    }

    // -- pure helpers ---------------------------------------------------------------------------

    @Test
    fun `only non-empty sections show, in order`() {
        val d = WaCardDetail(
            card = WaTopicCard(said = listOf("a"), topics = listOf("t"), facets = listOf(WaCardFacet("f", 0.1)), recallQueries = listOf("q")),
            subtopics = listOf(WaSubCard(title = "s")),
            moments = listOf(WaCardMoment(oneLine = "m")),
        )
        assertEquals(
            listOf(CardSection.MOMENTS, CardSection.SAID, CardSection.TOPICS, CardSection.MOOD, CardSection.SUBTOPICS, CardSection.RECALL),
            visibleSections(d),
        )
        assertTrue(visibleSections(null).isEmpty())
    }

    @Test
    fun `a moment opens only when it names chat, message and time`() {
        val full = WaCardMoment(chatJid = alice, msgId = "M1", ts = 5)
        assertTrue(momentOpenable(full))
        assertFalse(momentOpenable(full.copy(chatJid = null)))
        assertFalse(momentOpenable(full.copy(msgId = null)))
        assertFalse(momentOpenable(full.copy(msgId = "")))
        assertFalse(momentOpenable(full.copy(ts = null)))
    }

    @Test
    fun `intensity dots stay within five`() {
        assertEquals(0, intensityDots(WaCardAct(intensity = -2)))
        assertEquals(3, intensityDots(WaCardAct(intensity = 3)))
        assertEquals(5, intensityDots(WaCardAct(intensity = 9)))
    }

    @Test
    fun `labels read as plain words`() {
        assertEquals("Small talk", kindLabel("small_talk"))
        assertEquals("Plan · settled", actText(WaCardAct("plan", 2, true)))
        assertEquals("Plan · unsettled", actText(WaCardAct("plan", 2, false)))
        assertEquals("1 message", messagesLabel(1))
        assertEquals("4 messages", messagesLabel(4))
        assertEquals("1 conversation · tap it to see its full card", resultsHeader(1))
        assertEquals("3 conversations · tap one to see its full card", resultsHeader(3))
    }

    @Test
    fun `facets are ordered by confidence`() {
        val sorted = sortedFacets(listOf(WaCardFacet("low", 0.1), WaCardFacet("high", 0.9), WaCardFacet("mid", 0.5)))
        assertEquals(listOf("high", "mid", "low"), sorted.map { it.name })
    }

    @Test
    fun `only a user drag drops the keyboard`() {
        assertTrue(dismissesKeyboard(Offset(0f, 12f), userInput = true))
        assertFalse(dismissesKeyboard(Offset(0f, 12f), userInput = false))
        assertFalse(dismissesKeyboard(Offset.Zero, userInput = true))
    }
}
