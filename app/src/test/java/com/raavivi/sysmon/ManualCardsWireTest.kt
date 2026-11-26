package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.whatsapp.emptyResult
import com.raavivi.sysmon.core.data.whatsapp.manualErrorOf
import com.raavivi.sysmon.core.model.ManualCardError
import com.raavivi.sysmon.core.model.WaCardDetail
import com.raavivi.sysmon.core.model.WaChatSearchResponse
import com.raavivi.sysmon.core.model.WaManualCardReq
import com.raavivi.sysmon.core.model.WaManualJobResponse
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** The wire shapes of "your own cards" and how a failure becomes a typed error. Made-up data only. */
class ManualCardsWireTest {

    @Test
    fun `your_cards is parsed beside the results`() {
        val json = """{"results": [], "took_ms": 3.0, "reranked": false, "your_cards": [
            {"card_id": 12, "chat": "27820000001@s.whatsapp.net", "chat_name": "Test Chat", "title": "Pottery plan",
             "summary": "Picking a class.", "instruction": "the pottery class", "start": {"ts": 100, "rowid": 4},
             "end": {"ts": 900, "rowid": 20}, "n_msgs": 3, "msg_ids": ["a", "b", "c"], "score": 0.7}]}"""
        val r = SysMonJson.decodeFromString(WaChatSearchResponse.serializer(), json)
        assertTrue(r.results.isEmpty())
        val y = r.yourCards.single()
        assertEquals(12L, y.cardId)
        assertEquals("Pottery plan", y.title)
        assertEquals("the pottery class", y.instruction)
        assertEquals(100L, y.start.ts)
        assertEquals(20L, y.end.rowid)
        assertEquals(listOf("a", "b", "c"), y.msgIds)
    }

    @Test
    fun `a search without your_cards has none`() {
        val r = SysMonJson.decodeFromString(WaChatSearchResponse.serializer(), """{"results": []}""")
        assertTrue(r.yourCards.isEmpty())
    }

    @Test
    fun `a card detail carries origin, instruction and msg_ids`() {
        val d = SysMonJson.decodeFromString(
            WaCardDetail.serializer(),
            """{"id": 5, "of_parent": false, "card": {"title": "T"}, "origin": "manual", "instruction": "focus", "msg_ids": ["a", "b"]}""",
        )
        assertEquals("manual", d.origin)
        assertEquals("focus", d.instruction)
        assertEquals(listOf("a", "b"), d.msgIds)
        val auto = SysMonJson.decodeFromString(WaCardDetail.serializer(), """{"id": 6, "card": {}, "instruction": null}""")
        assertEquals("auto", auto.origin)
        assertNull(auto.instruction)
        assertTrue(auto.msgIds.isEmpty())
    }

    @Test
    fun `a job decodes from the 202 body`() {
        val r = SysMonJson.decodeFromString(
            WaManualJobResponse.serializer(),
            """{"job": {"id": 3, "state": "writing", "chat": "c", "n_msgs": 2, "created_at": 1, "updated_at": 2, "card_id": null, "error": null, "waiting": "model_starting"}}""",
        )
        assertEquals(3L, r.job.id)
        assertEquals("writing", r.job.state)
        assertEquals("model_starting", r.job.waiting)
        assertNull(r.job.cardId)
    }

    @Test
    fun `the request is snake case and leaves a missing description out`() {
        val withText = SysMonJson.encodeToString(WaManualCardReq.serializer(), WaManualCardReq("c", listOf("a"), "plan"))
        assertEquals("""{"chat":"c","msg_ids":["a"],"instruction":"plan"}""", withText)
        val without = SysMonJson.encodeToString(WaManualCardReq.serializer(), WaManualCardReq("c", listOf("a")))
        assertEquals("""{"chat":"c","msg_ids":["a"]}""", without)
    }

    @Test
    fun `statuses and codes become typed errors`() {
        assertEquals(ManualCardError.Refused("select 1 to 400 messages"), manualErrorOf(ApiResult.Err("select 1 to 400 messages", 400)))
        assertEquals(ManualCardError.TooMany, manualErrorOf(ApiResult.Err("x", 409, "too_many")))
        assertEquals(ManualCardError.NoModel, manualErrorOf(ApiResult.Err("x", 409, "no_model")))
        assertEquals(ManualCardError.Finished, manualErrorOf(ApiResult.Err("x", 409, "finished")))
        assertEquals(ManualCardError.NotManual, manualErrorOf(ApiResult.Err("x", 409, "not_manual")))
        assertEquals(ManualCardError.Other, manualErrorOf(ApiResult.Err("x", 409, "something")))
        assertEquals(ManualCardError.Off, manualErrorOf(ApiResult.Err("x", 404)))
        assertEquals(ManualCardError.Unavailable, manualErrorOf(ApiResult.Err("x", 503, "cards_store_unreadable")))
        assertEquals(ManualCardError.Other, manualErrorOf(ApiResult.Err("Network error: x")))
        assertTrue(manualErrorOf(ApiResult.Err("HTTP 400", 400)) is ManualCardError.Refused)
    }

    @Test
    fun `an empty 204 is Ok and a refusal keeps the envelope code`() {
        assertEquals(ApiResult.Ok(Unit), emptyResult(Response.success<Unit>(204, Unit)))
        val body = """{"ok": false, "error": "already finished", "code": "finished"}""".toResponseBody("application/json".toMediaType())
        val err = emptyResult(Response.error<Unit>(409, body)) as ApiResult.Err
        assertEquals(409, err.code)
        assertEquals("finished", err.apiCode)
        assertEquals("already finished", err.message)
        assertEquals(ManualCardError.Finished, manualErrorOf(err))
    }
}
