package com.raavivi.sysmon.core.data.chatindex

import com.raavivi.sysmon.core.data.whatsapp.emptyResult
import com.raavivi.sysmon.core.model.CiCardFull
import com.raavivi.sysmon.core.model.CiCardsPage
import com.raavivi.sysmon.core.model.CiFamiliesPage
import com.raavivi.sysmon.core.model.CiMessage
import com.raavivi.sysmon.core.model.CiMessagesPage
import com.raavivi.sysmon.core.model.CiMessagesWire
import com.raavivi.sysmon.core.model.CiNodeDetail
import com.raavivi.sysmon.core.model.CiNodesPage
import com.raavivi.sysmon.core.model.CiOverview
import com.raavivi.sysmon.core.model.CiStorage
import com.raavivi.sysmon.core.model.LexEntry
import com.raavivi.sysmon.core.model.LexEntryPutReq
import com.raavivi.sysmon.core.model.LexEntryReq
import com.raavivi.sysmon.core.model.LexRejected
import com.raavivi.sysmon.core.model.LexTermReq
import com.raavivi.sysmon.core.model.Lexicon
import com.raavivi.sysmon.core.model.toWaMessage
import com.raavivi.sysmon.core.net.ApiProvider
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.safeCall
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** [ChatIndexRepository] over the sys-mon REST API. */
class HttpChatIndexRepository(private val api: ApiProvider) : ChatIndexRepository {

    override suspend fun overview(): ApiResult<CiOverview> = safeCall { api.api.ciOverview() }

    override suspend fun families(offset: Int, limit: Int): ApiResult<CiFamiliesPage> =
        safeCall { api.api.ciFamilies(offset, limit) }

    override suspend fun sessions(family: Long, offset: Int, limit: Int): ApiResult<CiNodesPage> =
        safeCall { api.api.ciNodes(family = family, parent = null, offset = offset, limit = limit) }

    override suspend fun children(parent: Long, offset: Int, limit: Int): ApiResult<CiNodesPage> =
        safeCall { api.api.ciNodes(family = null, parent = parent, offset = offset, limit = limit) }

    override suspend fun node(id: Long): ApiResult<CiNodeDetail> = safeCall { api.api.ciNode(id) }

    override suspend fun nodeMessages(id: Long, offset: Int, limit: Int): ApiResult<CiMessagesPage> =
        when (val r = safeCall { api.api.ciNodeMessages(id, offset, limit) }) {
            is ApiResult.Ok -> ApiResult.Ok(messagesPageOf(r.value))
            is ApiResult.Err -> r
        }

    override suspend fun cards(family: Long, offset: Int, limit: Int): ApiResult<CiCardsPage> =
        safeCall { api.api.ciCards(family, offset, limit) }

    override suspend fun card(id: Long): ApiResult<CiCardFull> = safeCall { api.api.ciCard(id) }

    override suspend fun storage(): ApiResult<CiStorage> = safeCall { api.api.ciStorage() }

    override suspend fun deleteCard(id: Long): ApiResult<Unit> =
        when (val r = safeCall { api.api.waCardDelete(id) }) {
            is ApiResult.Ok -> emptyResult(r.value)
            is ApiResult.Err -> r
        }

    override suspend fun lexicon(): ApiResult<Lexicon> = safeCall { api.api.lexicon() }

    override suspend fun addEntry(terms: List<String>, meaning: String): ApiResult<LexEntry> =
        safeCall { api.api.lexiconAdd(LexEntryReq(terms, meaning)) }

    override suspend fun saveEntry(id: Long, version: Long, terms: List<String>, meaning: String): ApiResult<LexEntry> =
        safeCall { api.api.lexiconSave(id, LexEntryPutReq(terms, meaning, version)) }

    override suspend fun deleteEntry(id: Long, version: Long): ApiResult<Unit> =
        when (val r = safeCall { api.api.lexiconDelete(id, version) }) {
            is ApiResult.Ok -> emptyResult(r.value)
            is ApiResult.Err -> r
        }

    override suspend fun reject(term: String): ApiResult<LexRejected> =
        safeCall { api.api.lexiconReject(LexTermReq(term)) }

    override suspend fun unreject(term: String): ApiResult<Unit> =
        when (val r = safeCall { api.api.lexiconUnreject(LexTermReq(term)) }) {
            is ApiResult.Ok -> emptyResult(r.value)
            is ApiResult.Err -> r
        }
}

/** The wire page as app messages: each wacli row through [toWaMessage], keeping its `rowid` (0 when absent). */
internal fun messagesPageOf(w: CiMessagesWire): CiMessagesPage = CiMessagesPage(
    nodeId = w.nodeId,
    messages = w.messages.map { row ->
        CiMessage(rowid = row["rowid"]?.jsonPrimitive?.longOrNull ?: 0L, message = row.toWaMessage())
    },
    nextOffset = w.nextOffset,
)
