package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.chatindex.ChatIndexError
import com.raavivi.sysmon.core.data.chatindex.chatIndexErrorOf
import com.raavivi.sysmon.core.data.chatindex.editErrorText
import com.raavivi.sysmon.core.data.chatindex.editNeedsReload
import com.raavivi.sysmon.core.model.CiCardEdit
import com.raavivi.sysmon.core.model.CiCardFull
import com.raavivi.sysmon.core.model.CiCardsPage
import com.raavivi.sysmon.core.model.CiEditAct
import com.raavivi.sysmon.core.model.CiEditDetail
import com.raavivi.sysmon.core.model.CiEditFacet
import com.raavivi.sysmon.core.model.CiEditLex
import com.raavivi.sysmon.core.model.CiEditOptions
import com.raavivi.sysmon.core.model.CiEditSub
import com.raavivi.sysmon.core.model.CiEditTopic
import com.raavivi.sysmon.core.model.WaCardDetail
import com.raavivi.sysmon.core.model.WaChatSearchResponse
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card edit wire shapes against the documented JSON (docs/API.md), with made-up data. */
class CardEditWireTest {

    @Test
    fun `edit options decode with the recall range and the kind maximum`() {
        val o = SysMonJson.decodeFromString(
            CiEditOptions.serializer(),
            """{"facets":["plans","mood"],"detail_kinds":["plan","joke"],"detail_kinds_max":3,"recall_queries":{"min":3,"max":5}}""",
        )
        assertEquals(listOf("plans", "mood"), o.facets)
        assertEquals(listOf("plan", "joke"), o.detailKinds)
        assertEquals(3, o.detailKindsMax)
        assertEquals(3, o.recallQueries.min)
        assertEquals(5, o.recallQueries.max)
    }

    private val body = CiCardEdit(
        card = CiEditTopic(
            title = "Trip", summary = "A date.", said = emptyList(), meant = listOf("m1"), topics = emptyList(),
            acts = listOf(CiEditAct("plan", 3, false)), facets = listOf(CiEditFacet("plans", 0.8)),
            entities = emptyList(), recallQueries = listOf("a", "b", "c"),
            details = listOf(CiEditDetail(listOf("plan"), "", "text")), lexicon = listOf(CiEditLex("zorp", "a cuddle")),
        ),
        subtopics = listOf(CiEditSub("Sub", "S", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())),
        keepMoments = listOf(0, 2),
        updatedAt = 1_700_000_000,
    )

    @Test
    fun `the PUT body sends every field in snake_case, empty lists and false flags included`() {
        val json = SysMonJson.encodeToString(CiCardEdit.serializer(), body)
        assertEquals(
            """{"card":{"title":"Trip","summary":"A date.","said":[],"meant":["m1"],"topics":[],""" +
                """"acts":[{"kind":"plan","intensity":3,"resolved":false}],"facets":[{"name":"plans","confidence":0.8}],""" +
                """"entities":[],"recall_queries":["a","b","c"],"details":[{"kinds":["plan"],"who":"","text":"text"}],""" +
                """"lexicon":[{"term":"zorp","meaning":"a cuddle"}]},""" +
                """"subtopics":[{"title":"Sub","summary":"S","said":[],"meant":[],"acts":[],"facets":[],"details":[]}],""" +
                """"keep_moments":[0,2],"updated_at":1700000000}""",
            json,
        )
    }

    @Test
    fun `a 200 answers the card with edited_at and a later updated_at`() {
        val c = SysMonJson.decodeFromString(
            CiCardFull.serializer(),
            """{"id":5,"family_jids":[],"start":{"ts":1,"rowid":1},"end":{"ts":2,"rowid":2},"n_msgs":3,"state":"done",
              |"updated_at":1700000500,"edited_at":1700000500,"node_id":9,"origin":"auto","card":{"title":"Trip"},"subtopics":[],"moments":[]}""".trimMargin(),
        )
        assertEquals(1_700_000_500L, c.editedAt)
        assertEquals(1_700_000_500L, c.updatedAt)
        assertNull(SysMonJson.decodeFromString(CiCardFull.serializer(), """{"id":5,"state":"done"}""").editedAt)
    }

    @Test
    fun `edited_at is on a card row, a search card, the card detail and a your-card hit`() {
        val page = SysMonJson.decodeFromString(
            CiCardsPage.serializer(),
            """{"cards":[{"id":1,"title":"A","edited_at":1700000000},{"id":2,"title":"B","edited_at":null},{"id":3,"title":"C"}],"next_offset":null}""",
        )
        assertEquals(1_700_000_000L, page.cards[0].editedAt)
        assertNull(page.cards[1].editedAt)
        assertNull(page.cards[2].editedAt)
        val detail = SysMonJson.decodeFromString(WaCardDetail.serializer(), """{"id":4,"card":{"title":"D"},"edited_at":1700000100}""")
        assertEquals(1_700_000_100L, detail.editedAt)
        val search = SysMonJson.decodeFromString(
            WaChatSearchResponse.serializer(),
            """{"results":[{"node_id":1,"card":{"id":4,"title":"D","summary":"s","edited_at":1700000200}}],
              |"your_cards":[{"card_id":7,"title":"Y","edited_at":1700000300}]}""".trimMargin(),
        )
        assertEquals(1_700_000_200L, search.results[0].card?.editedAt)
        assertEquals(1_700_000_300L, search.yourCards[0].editedAt)
    }

    @Test
    fun `each documented failure maps to a typed reason`() {
        assertEquals(
            ChatIndexError.Refused("the title cannot be empty"),
            chatIndexErrorOf(ApiResult.Err("the title cannot be empty", 400)),
        )
        assertEquals(ChatIndexError.Stale, chatIndexErrorOf(ApiResult.Err("stale", 409, "stale")))
        assertEquals(ChatIndexError.NotDone, chatIndexErrorOf(ApiResult.Err("failed card", 409, "not_done")))
        assertEquals(ChatIndexError.Missing, chatIndexErrorOf(ApiResult.Err("no such card", 404)))
        assertEquals(ChatIndexError.Unavailable, chatIndexErrorOf(ApiResult.Err("cards_write_failed", 503)))
        assertEquals(ChatIndexError.ReadBack, chatIndexErrorOf(ApiResult.Err("the edited card could not be read back", 500)))
        assertTrue(chatIndexErrorOf(ApiResult.Err("boom", 500)) is ChatIndexError.Other)
    }

    @Test
    fun `the user sentences say what to do, and only a stale or read-back failure offers Reload`() {
        val stale = ApiResult.Err("stale", 409, "stale")
        assertEquals("This card changed since you opened it. Reload it to see the latest, then edit again.", editErrorText(stale))
        assertTrue(editNeedsReload(stale))
        val readBack = ApiResult.Err("the edited card could not be read back", 500)
        assertTrue(editNeedsReload(readBack))
        assertTrue(editErrorText(readBack).contains("do not save again"))
        val refused = ApiResult.Err("the recall queries must number 3 to 5", 400)
        assertEquals("The recall queries must number 3 to 5.", editErrorText(refused))
        assertFalse(editNeedsReload(refused))
        assertFalse(editNeedsReload(ApiResult.Err("cards_store_unreadable", 503)))
        assertTrue(editErrorText(ApiResult.Err("cards_store_unreadable", 503)).contains("card store"))
        assertEquals("A failed card has nothing to edit.", editErrorText(ApiResult.Err("x", 409, "not_done")))
        assertTrue(editErrorText(ApiResult.Err("Network error: down")).startsWith("Could not reach"))
    }
}
