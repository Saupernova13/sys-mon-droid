package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.whatsapp.chatSearchResult
import com.raavivi.sysmon.core.model.WaChatSearchResponse
import com.raavivi.sysmon.core.model.WaChatSearchStatus
import com.raavivi.sysmon.core.model.WaPickHit
import com.raavivi.sysmon.core.model.WaPickReq
import com.raavivi.sysmon.core.model.WaSearchResponse
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** Wire decoding of the search routes, with made-up data only. */
class WhatsAppSearchWireTest {

    private val json = "application/json".toMediaType()

    @Test
    fun `a message search page decodes its hits, paging and terms`() {
        val page = SysMonJson.decodeFromString(
            WaSearchResponse.serializer(),
            """{"results":[{"chat_jid":"27820000001@s.whatsapp.net","chat_name":"Test Chat","msg_id":"M1",
               "ts":1700000000,"from_me":false,"snippet":"go to the \u0002cinema\u0003","voice":true,"extra":1}],
               "total":123,"total_capped":true,"offset":0,"next_offset":100,
               "terms":[{"text":"cinema","prefix":true,"phrase":false,"negated":false,"indexable":true}],"engine":"fts"}""",
        )

        assertEquals(1, page.results.size)
        val hit = page.results[0]
        assertEquals("27820000001@s.whatsapp.net", hit.chatJid)
        assertEquals("Test Chat", hit.chatName)
        assertEquals("M1", hit.msgId)
        assertEquals(1_700_000_000L, hit.ts)
        assertTrue(hit.voice)
        assertEquals(123, page.total)
        assertTrue(page.totalCapped)
        assertEquals(100, page.nextOffset)
        assertTrue(page.terms[0].prefix)
    }

    @Test
    fun `the last page has no next offset`() {
        val page = SysMonJson.decodeFromString(WaSearchResponse.serializer(), """{"results":[],"total":0}""")
        assertNull(page.nextOffset)
        assertFalse(page.totalCapped)
    }

    @Test
    fun `a conversation result decodes its card, breadcrumb and preview`() {
        val body = SysMonJson.decodeFromString(
            WaChatSearchResponse.serializer(),
            """{"results":[{"node_id":7,"level":2,"chat":"27820000001@s.whatsapp.net",
               "family":["27820000001@s.whatsapp.net","111@lid"],"chat_name":"Test Chat",
               "start_ts":1741971600,"start_rowid":10,"end_ts":1741975200,"end_rowid":40,"n_msgs":30,
               "breadcrumb":{"node_id":3,"level":1,"start_ts":1741971000,"end_ts":1741976000,"n_msgs":50},
               "score":0.5,"legs":{"dense":1,"window":null},
               "preview":[{"chat_jid":"x","msg_id":"M2","ts":1741971601,"from_me":true,"sender_name":"Sam","text":"cinema?"}],
               "card":{"id":4,"title":"Film night","summary":"Planning","of_parent":true}}],
               "took_ms":12.5,"reranked":false}""",
        )

        val r = body.results.single()
        assertEquals(2, r.level)
        assertEquals(listOf("27820000001@s.whatsapp.net", "111@lid"), r.family)
        assertEquals(10L, r.startRowid)
        assertEquals(50, r.breadcrumb!!.nMsgs)
        assertTrue(r.preview[0].fromMe)
        assertEquals("Film night", r.card!!.title)
        assertTrue(r.card!!.ofParent)
        assertNull(body.degraded)
    }

    @Test
    fun `a status decodes the index and card fields and tolerates a bare enabled false`() {
        val off = SysMonJson.decodeFromString(WaChatSearchStatus.serializer(), """{"enabled": false}""")
        assertFalse(off.enabled)
        assertNull(off.cards)

        val st = SysMonJson.decodeFromString(
            WaChatSearchStatus.serializer(),
            """{"enabled":true,"auto":false,"run_requested":true,"messages_indexed":10,"messages_total":20,
               "node_vectors":5,"voice_notes":{"pending":3,"mode":"http"},
               "cards":{"configured":true,"requested":false,"auto":false,"mode":"break","eligible_pending":null,
                        "done":2,"break_until":1700000000.5,"last_error":"server_unavailable"}}""",
        )
        assertEquals(false, st.auto)
        assertTrue(st.runRequested)
        assertEquals(5L, st.nodeVectors)
        assertEquals(3, st.voiceNotes!!.pending)
        assertNull(st.cards!!.eligiblePending)
        assertEquals(1_700_000_000.5, st.cards!!.breakUntil!!, 0.0)
        assertTrue(st.cards!!.lastError != null)
    }

    @Test
    fun `a pick request leaves out the hit and rank for not here`() {
        val notHere = SysMonJson.encodeToString(WaPickReq.serializer(), WaPickReq(q = "cinema", verdict = "not_here", shown = 4))
        assertEquals("""{"q":"cinema","verdict":"not_here","shown":4}""", notHere)

        val found = SysMonJson.encodeToString(
            WaPickReq.serializer(),
            WaPickReq("cinema", "found", WaPickHit(listOf("a@s.whatsapp.net"), 1, 2, 3, 4), rank = 2, shown = 4),
        )
        assertEquals(
            """{"q":"cinema","verdict":"found","hit":{"family":["a@s.whatsapp.net"],"start_ts":1,"start_rowid":2,"end_ts":3,"end_rowid":4},"rank":2,"shown":4}""",
            found,
        )
    }

    // -- the 503 that still carries results --------------------------------------------------

    @Test
    fun `a 503 with a degraded body is a usable result`() {
        val r = Response.error<WaChatSearchResponse>(
            503,
            """{"results":[{"node_id":1,"chat":"a@s.whatsapp.net","n_msgs":3}],"degraded":"keyword"}""".toResponseBody(json),
        )

        val out = chatSearchResult(r) as ApiResult.Ok
        assertEquals("keyword", out.value.degraded)
        assertEquals(1, out.value.results.size)
    }

    @Test
    fun `a 503 without a degraded body is an error`() {
        val r = Response.error<WaChatSearchResponse>(503, """{"ok":false,"error":"store unavailable"}""".toResponseBody(json))

        val out = chatSearchResult(r) as ApiResult.Err
        assertEquals(503, out.code)
        assertEquals("store unavailable", out.message)
    }

    @Test
    fun `a 400 keeps the server's message and a 404 stays an error`() {
        val bad = chatSearchResult(
            Response.error<WaChatSearchResponse>(400, """{"ok":false,"error":"query is empty"}""".toResponseBody(json)),
        ) as ApiResult.Err
        assertEquals(400, bad.code)
        assertEquals("query is empty", bad.message)

        val off = chatSearchResult(Response.error<WaChatSearchResponse>(404, "".toResponseBody(json))) as ApiResult.Err
        assertEquals(404, off.code)
    }

    @Test
    fun `a 200 is ok`() {
        val out = chatSearchResult(Response.success(WaChatSearchResponse(reranked = true))) as ApiResult.Ok
        assertTrue(out.value.reranked)
    }
}
