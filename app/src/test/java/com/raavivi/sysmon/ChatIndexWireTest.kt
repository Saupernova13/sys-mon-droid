package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.chatindex.messagesPageOf
import com.raavivi.sysmon.core.model.CiCardFull
import com.raavivi.sysmon.core.model.CiCardsPage
import com.raavivi.sysmon.core.model.CiFamiliesPage
import com.raavivi.sysmon.core.model.CiMessagesWire
import com.raavivi.sysmon.core.model.CiNodeDetail
import com.raavivi.sysmon.core.model.CiNodesPage
import com.raavivi.sysmon.core.model.CiOverview
import com.raavivi.sysmon.core.model.CiStorage
import com.raavivi.sysmon.core.model.LexEntry
import com.raavivi.sysmon.core.model.LexEntryPutReq
import com.raavivi.sysmon.core.model.LexEntryReq
import com.raavivi.sysmon.core.model.LexTermReq
import com.raavivi.sysmon.core.model.Lexicon
import com.raavivi.sysmon.core.net.SysMonJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat-index wire shapes against the documented JSON (docs/API.md), with made-up data. */
class ChatIndexWireTest {

    private fun <T> decode(s: kotlinx.serialization.KSerializer<T>, raw: String): T = SysMonJson.decodeFromString(s, raw)

    @Test
    fun `the overview decodes, and an empty index is zeros with index_exists false`() {
        val o = decode(
            CiOverview.serializer(),
            """{"index_exists":true,"families":3,"nodes":{"sessions":10,"topics":20,"subtopics":30},"messages_indexed":1000,
              |"vectors":5,"node_vectors":6,"cards":{"done":7,"failed":1,"outdated":2}}""".trimMargin(),
        )
        assertTrue(o.indexExists)
        assertEquals(30L, o.nodes.subtopics)
        assertEquals(1000L, o.messagesIndexed)
        assertEquals(2L, o.cards.outdated)
        val none = decode(
            CiOverview.serializer(),
            """{"index_exists":false,"families":0,"nodes":{"sessions":0,"topics":0,"subtopics":0},"messages_indexed":0,
              |"vectors":0,"node_vectors":0,"cards":{"done":0,"failed":0,"outdated":0}}""".trimMargin(),
        )
        assertFalse(none.indexExists)
    }

    @Test
    fun `a families page carries next_offset, null on the last`() {
        val p = decode(
            CiFamiliesPage.serializer(),
            """{"families":[{"id":1,"jids":["a@s.whatsapp.net","b@g.us"],"name":"","messages":9,
              |"nodes":{"sessions":1,"topics":2,"subtopics":3},"cards":4}],"next_offset":50}""".trimMargin(),
        )
        assertEquals(50, p.nextOffset)
        assertEquals("", p.families[0].name)
        assertEquals(2, p.families[0].jids.size)
        assertNull(decode(CiFamiliesPage.serializer(), """{"families":[],"next_offset":null}""").nextOffset)
    }

    @Test
    fun `span ends are ts and rowid, and a topic's card is a reference`() {
        val p = decode(
            CiNodesPage.serializer(),
            """{"nodes":[{"id":5,"family_id":1,"parent_id":null,"level":1,"start":{"ts":1700000000,"rowid":11},
              |"end":{"ts":1700000600,"rowid":22},"n_msgs":8,"has_vector":true,"n_children":2,"card":{"id":3,"title":"Trip plans"}},
              |{"id":6,"family_id":1,"parent_id":5,"level":2,"start":{"ts":1,"rowid":1},"end":{"ts":2,"rowid":2},
              |"n_msgs":1,"has_vector":false,"n_children":0,"card":null}],"next_offset":null}""".trimMargin(),
        )
        val n = p.nodes[0]
        assertNull(n.parentId)
        assertEquals(1_700_000_000L, n.start.ts)
        assertEquals(22L, n.end.rowid)
        assertEquals("Trip plans", n.card?.title)
        assertNull(p.nodes[1].card)
        assertEquals(5L, p.nodes[1].parentId)
    }

    @Test
    fun `a node detail has the path of its ancestors`() {
        val d = decode(
            CiNodeDetail.serializer(),
            """{"node":{"id":6,"family_id":1,"parent_id":5,"level":2,"start":{"ts":1,"rowid":1},"end":{"ts":2,"rowid":2},
              |"n_msgs":1,"has_vector":false,"n_children":0,"card":null},"path":[{"id":4,"level":0},{"id":5,"level":1}]}""".trimMargin(),
        )
        assertEquals(listOf(4L to 0, 5L to 1), d.path.map { it.id to it.level })
    }

    @Test
    fun `a card row marks manual cards`() {
        val p = decode(
            CiCardsPage.serializer(),
            """{"cards":[{"id":1,"title":"A","start":{"ts":1,"rowid":1},"end":{"ts":2,"rowid":2},"n_msgs":3,"state":"done","updated_at":9,"origin":"auto"},
              |{"id":2,"title":"","start":{"ts":1,"rowid":1},"end":{"ts":2,"rowid":2},"n_msgs":3,"state":"failed","updated_at":9,"origin":"manual"}],"next_offset":null}""".trimMargin(),
        )
        assertEquals(listOf(false, true), p.cards.map { it.isManual })
        assertEquals("failed", p.cards[1].state)
    }

    @Test
    fun `a card in full decodes its moments with and without a message`() {
        val c = decode(
            CiCardFull.serializer(),
            """{"id":3,"family_jids":["a@s.whatsapp.net"],"start":{"ts":1,"rowid":1},"end":{"ts":2,"rowid":2},"n_msgs":8,"state":"done",
              |"model":"m","prompt_version":4,"lines_sent":8,"completion_tokens":100,"seconds":1.5,"attempts":1,"fail_reason":null,
              |"updated_at":9,"node_id":5,"origin":"manual","instruction":"the plans","msg_ids":["m1","m2"],
              |"card":{"title":"Trip plans","summary":"s","said":["x"],"meant":[],"topics":["trip"],"acts":[{"kind":"plan","intensity":3,"resolved":true}],
              |"facets":[{"name":"excited","confidence":0.9}],"entities":[],"recall_queries":["trip"],"details":[],"lexicon":[{"term":"zorp","meaning":"cuddle"}]},
              |"subtopics":[{"title":"Sub","summary":"ss"}],
              |"moments":[{"kind":"plan","one_line":"A date","said":"x","msg_index":0,"message":{"rowid":9,"chat_jid":"a@s.whatsapp.net","msg_id":"3EB0","ts":1700000100}},
              |{"kind":"idea","one_line":"None kept","said":"y","msg_index":1,"message":null}]}""".trimMargin(),
        )
        assertTrue(c.isManual)
        assertEquals("the plans", c.instruction)
        assertEquals(4L, c.promptVersion)
        assertEquals(5L, c.nodeId)
        assertEquals("Trip plans", c.card?.title)
        assertEquals("zorp", c.card?.lexicon?.single()?.term)
        assertEquals("Sub", c.subtopics.single().title)
        assertEquals("3EB0", c.moments[0].message?.msgId)
        assertNull(c.moments[1].message)
    }

    @Test
    fun `a failed card has no card body and a reason`() {
        val c = decode(
            CiCardFull.serializer(),
            """{"id":4,"family_jids":[],"start":{"ts":1,"rowid":1},"end":{"ts":2,"rowid":2},"n_msgs":3,"state":"failed","model":null,
              |"prompt_version":null,"attempts":3,"fail_reason":"bad_json","updated_at":9,"node_id":null,"origin":"auto","instruction":null,
              |"msg_ids":[],"card":null,"subtopics":[],"moments":[]}""".trimMargin(),
        )
        assertNull(c.card)
        assertEquals("bad_json", c.failReason)
        assertFalse(c.isManual)
    }

    @Test
    fun `node messages keep their rowid and become app messages`() {
        val wire = decode(
            CiMessagesWire.serializer(),
            """{"node_id":5,"messages":[{"rowid":41,"MsgID":"3EB0AA","ChatJID":"a@s.whatsapp.net","FromMe":false,"Timestamp":1700000000,"Text":"hello","media_type":"image"},
              |{"rowid":42,"MsgID":"3EB0AB","ChatJID":"a@s.whatsapp.net","FromMe":true,"Timestamp":1700000010,"Text":"hi"}],"next_offset":2}""".trimMargin(),
        )
        val page = messagesPageOf(wire)
        assertEquals(5L, page.nodeId)
        assertEquals(2, page.nextOffset)
        assertEquals(listOf(41L, 42L), page.messages.map { it.rowid })
        assertEquals("hello", page.messages[0].message.text)
        assertEquals("image", page.messages[0].message.mediaType)
        assertTrue(page.messages[1].message.fromMe)
    }

    @Test
    fun `storage has null row counts for virtual tables and a null size for a missing file`() {
        val s = decode(
            CiStorage.serializer(),
            """{"dbs":[{"key":"index","file":"chat_search.db","exists":true,"size_bytes":4096,"tables":[{"name":"nodes","rows":12},{"name":"node_vec","rows":null}],"error":null},
              |{"key":"cards","file":"cards.db","exists":false,"size_bytes":null,"tables":[],"error":null},
              |{"key":"wacli","file":"wacli.db","exists":true,"size_bytes":10,"tables":[],"error":"unreadable"}]}""".trimMargin(),
        )
        assertEquals(12L, s.dbs[0].tables[0].rows)
        assertNull(s.dbs[0].tables[1].rows)
        assertNull(s.dbs[1].sizeBytes)
        assertEquals("unreadable", s.dbs[2].error)
    }

    @Test
    fun `the lexicon decodes entries, rejected, suggestions and the legacy import`() {
        val l = decode(
            Lexicon.serializer(),
            """{"entries":[{"id":3,"version":2,"terms":["zorp","w/e"],"meaning":"a cuddle","updated_at":1700000000}],
              |"rejected":[{"term":"blip","created_at":1700000000}],
              |"suggestions":[{"term":"noodle","meaning":"a pet name","seen":5,"first_seen":1,"last_seen":2},{"term":"x","meaning":null,"seen":1,"first_seen":1,"last_seen":1}],
              |"import":{"done":true,"source":"","imported":4,"skipped":1,"error":null}}""".trimMargin(),
        )
        assertEquals(2L, l.entries[0].version)
        assertEquals(listOf("zorp", "w/e"), l.entries[0].terms)
        assertEquals("blip", l.rejected[0].term)
        assertEquals(5, l.suggestions[0].seen)
        assertNull(l.suggestions[1].meaning)
        assertNotNull(l.legacyImport)
        assertEquals(4, l.legacyImport?.imported)
        assertNull(decode(Lexicon.serializer(), """{"entries":[],"rejected":[],"suggestions":[]}""").legacyImport)
    }

    @Test
    fun `request bodies keep every punctuation character and never split on commas`() {
        val add = SysMonJson.encodeToString(LexEntryReq.serializer(), LexEntryReq(listOf("a, b", "=!#/\\'\".?&():"), "m, n"))
        assertEquals("""{"terms":["a, b","=!#/\\'\".?&():"],"meaning":"m, n"}""", add)
        val put = SysMonJson.encodeToString(LexEntryPutReq.serializer(), LexEntryPutReq(listOf("zorp"), "x", 2))
        assertEquals("""{"terms":["zorp"],"meaning":"x","version":2}""", put)
        assertEquals("""{"term":"blip"}""", SysMonJson.encodeToString(LexTermReq.serializer(), LexTermReq("blip")))
    }

    @Test
    fun `an entry round trips emoji and right-to-left text`() {
        val e = LexEntry(1, 1, listOf("🦊", "ゾルプ", "مرحبا"), "meaning")
        val back = SysMonJson.decodeFromString(LexEntry.serializer(), SysMonJson.encodeToString(LexEntry.serializer(), e))
        assertEquals(e, back)
    }
}
