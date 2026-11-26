package com.raavivi.sysmon.core.data.chatindex

import com.raavivi.sysmon.core.model.CiCardFull
import com.raavivi.sysmon.core.model.CiCardsPage
import com.raavivi.sysmon.core.model.CiFamiliesPage
import com.raavivi.sysmon.core.model.CiMessagesPage
import com.raavivi.sysmon.core.model.CiNodeDetail
import com.raavivi.sysmon.core.model.CiNodesPage
import com.raavivi.sysmon.core.model.CiOverview
import com.raavivi.sysmon.core.model.CiStorage
import com.raavivi.sysmon.core.model.LexEntry
import com.raavivi.sysmon.core.model.LexRejected
import com.raavivi.sysmon.core.model.Lexicon
import com.raavivi.sysmon.core.net.ApiResult

/**
 * The chat index explorer and the private-words glossary (admin). Every failure is an [ApiResult.Err] carrying
 * the status and the envelope's `code`; [chatIndexErrorOf] turns it into a typed reason. Everything read here is
 * private chat content: callers never log it.
 */
interface ChatIndexRepository {
    suspend fun overview(): ApiResult<CiOverview>

    /** One page of chats, most messages first; a page's `nextOffset` is the next [offset], null on the last. */
    suspend fun families(offset: Int, limit: Int = FAMILIES_PAGE): ApiResult<CiFamiliesPage>

    /** The sessions of chat [family]. */
    suspend fun sessions(family: Long, offset: Int, limit: Int = NODES_PAGE): ApiResult<CiNodesPage>

    /** The children of node [parent] (topics of a session, sub-topics of a topic). */
    suspend fun children(parent: Long, offset: Int, limit: Int = NODES_PAGE): ApiResult<CiNodesPage>

    suspend fun node(id: Long): ApiResult<CiNodeDetail>

    /** A node's messages, oldest first; deleted ones are left out. 503: the message store cannot be read. */
    suspend fun nodeMessages(id: Long, offset: Int, limit: Int = MESSAGES_PAGE): ApiResult<CiMessagesPage>

    suspend fun cards(family: Long, offset: Int, limit: Int = CARDS_PAGE): ApiResult<CiCardsPage>
    suspend fun card(id: Long): ApiResult<CiCardFull>
    suspend fun storage(): ApiResult<CiStorage>

    /** Delete a card the owner asked for. 409 `not_manual`: a topic card cannot be deleted; 404: already gone. */
    suspend fun deleteCard(id: Long): ApiResult<Unit>

    suspend fun lexicon(): ApiResult<Lexicon>
    suspend fun addEntry(terms: List<String>, meaning: String): ApiResult<LexEntry>

    /** Replace an entry; [version] is the one the client read (409 `stale` when it moved on). */
    suspend fun saveEntry(id: Long, version: Long, terms: List<String>, meaning: String): ApiResult<LexEntry>
    suspend fun deleteEntry(id: Long, version: Long): ApiResult<Unit>
    suspend fun reject(term: String): ApiResult<LexRejected>
    suspend fun unreject(term: String): ApiResult<Unit>

    companion object {
        const val FAMILIES_PAGE = 50
        const val NODES_PAGE = 100
        const val MESSAGES_PAGE = 50
        const val CARDS_PAGE = 50
    }
}
